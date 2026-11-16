package io.github.axon07.channelbox.data

import android.content.Context
import io.github.axon07.channelbox.player.TvPlayer
import io.github.axon07.channelbox.system.RecordingWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Records live channels to files on the box.
 *
 * - MPEG-TS / plain streams: the bytes are saved as they arrive (reconnecting if the stream drops).
 * - HLS: the playlist is polled and each new segment is appended (AES-128 encrypted HLS is decrypted).
 * - DASH and DRM-protected channels can't be recorded.
 */
class Recorder(
    private val context: Context,
    private val db: AppDatabase,
    private val http: OkHttpClient,
) {
    private val dao = db.recordings()

    val recordings: Flow<List<RecordingEntity>> = dao.observeAll()

    fun directory(): File =
        (context.getExternalFilesDir("recordings") ?: File(context.filesDir, "recordings")).also { it.mkdirs() }

    suspend fun schedule(channel: ChannelEntity, title: String, start: Long, stop: Long, programStart: Long?): Long {
        val id = dao.insert(
            RecordingEntity(
                channelId = channel.id,
                channelName = channel.name,
                title = title,
                streamUrl = channel.streamUrl,
                headers = channel.headers,
                drmType = channel.drmType,
                drmKey = channel.drmKey,
                mimeHint = channel.mimeHint,
                start = start,
                stop = stop,
                programStart = programStart,
            )
        )
        RecordingWorker.enqueue(context, id, (start - System.currentTimeMillis()).coerceAtLeast(0))
        return id
    }

    /** Stops a running recording (keeping what was recorded) or cancels a scheduled one. */
    suspend fun stop(recording: RecordingEntity) {
        RecordingWorker.cancel(context, recording.id)
        val r = dao.get(recording.id) ?: return
        if (r.status == RecStatus.SCHEDULED) dao.update(r.copy(status = RecStatus.CANCELLED))
    }

    suspend fun delete(recording: RecordingEntity) {
        RecordingWorker.cancel(context, recording.id)
        dao.get(recording.id)?.filePath?.let { File(it).delete() }
        dao.delete(recording.id)
    }

    /** Recordings left "recording" by a crash or reboot get closed off. */
    suspend fun tidyUp() {
        val now = System.currentTimeMillis()
        dao.getAll().filter { it.status == RecStatus.RECORDING && it.stop < now }.forEach {
            val size = it.filePath?.let { p -> File(p).length() } ?: 0L
            dao.update(it.copy(status = if (size > 0) RecStatus.DONE else RecStatus.FAILED, sizeBytes = size))
        }
    }

    // ---- The actual recording, run by RecordingWorker -------------------------------------------

    suspend fun record(id: Long) = withContext(Dispatchers.IO) {
        val rec = dao.get(id) ?: return@withContext
        if (rec.status != RecStatus.SCHEDULED && rec.status != RecStatus.RECORDING) return@withContext
        if (System.currentTimeMillis() >= rec.stop) {
            dao.update(rec.copy(status = RecStatus.FAILED, error = "Missed – the box was off or asleep"))
            return@withContext
        }
        val spec = StreamSpecs.from(rec)
        if (spec.drm != null) {
            dao.update(rec.copy(status = RecStatus.FAILED, error = "Encrypted (DRM) channels can't be recorded"))
            return@withContext
        }
        if (spec.mime == StreamSpecs.MIME_DASH || spec.mime == StreamSpecs.MIME_SS) {
            dao.update(rec.copy(status = RecStatus.FAILED, error = "Recording DASH (.mpd) channels isn't supported yet"))
            return@withContext
        }

        val session = Session(rec.copy(status = RecStatus.RECORDING))
        dao.update(session.rec)
        try {
            val playlist = if (spec.mime == StreamSpecs.MIME_HLS) fetchText(spec, spec.url) else probeHls(spec)
            if (playlist != null) recordHls(spec, playlist, session) else recordProgressive(spec, session)
            session.close(null)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { session.close(null) } // stopped by the user: keep what we have
            throw e
        } catch (e: Exception) {
            withContext(NonCancellable) { session.close(e.message ?: e.javaClass.simpleName) }
        }
    }

    private inner class Session(var rec: RecordingEntity) {
        private var file: File? = null
        private var out: OutputStream? = null
        private var lastUpdate = 0L

        suspend fun open(extension: String) {
            val f = File(directory(), "${fileName(rec)}.$extension")
            file = f
            out = BufferedOutputStream(FileOutputStream(f, true), 256 * 1024)
            rec = rec.copy(filePath = f.path)
            dao.update(rec)
        }

        suspend fun write(bytes: ByteArray, length: Int = bytes.size) {
            out?.write(bytes, 0, length) ?: return
            val now = System.currentTimeMillis()
            if (now - lastUpdate > 10_000) {
                lastUpdate = now
                out?.flush()
                rec = rec.copy(sizeBytes = file?.length() ?: 0)
                dao.update(rec)
            }
        }

        suspend fun close(error: String?) {
            runCatching { out?.flush(); out?.close() }
            val size = file?.length() ?: 0L
            val status = when {
                size > 0 -> RecStatus.DONE
                error != null -> RecStatus.FAILED
                else -> RecStatus.CANCELLED
            }
            rec = rec.copy(status = status, sizeBytes = size, error = error)
            dao.update(rec)
        }
    }

    private suspend fun recordProgressive(spec: StreamSpec, session: Session) {
        session.open("ts")
        val buffer = ByteArray(64 * 1024)
        var failures = 0
        while (System.currentTimeMillis() < session.rec.stop) {
            currentCoroutineContext().ensureActive()
            try {
                execute(spec, spec.url).use { response ->
                    if (!response.isSuccessful) throw IOException("Server answered HTTP ${response.code}")
                    val input = response.body?.byteStream() ?: throw IOException("Empty response")
                    while (System.currentTimeMillis() < session.rec.stop) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        session.write(buffer, n)
                        failures = 0
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (++failures > 20) throw e
            }
            if (System.currentTimeMillis() < session.rec.stop) delay(2_000) // stream dropped: reconnect
        }
    }

    private suspend fun recordHls(spec: StreamSpec, firstText: String, session: Session) {
        var playlistUrl = spec.url
        var text = firstText
        if (Hls.isMaster(text)) {
            playlistUrl = Hls.bestVariant(text, playlistUrl) ?: throw IOException("No playable stream in the playlist")
            text = fetchText(spec, playlistUrl)
        }
        var media = Hls.parseMedia(text, playlistUrl)
        session.open(if (media.mapUri != null) "mp4" else "ts")
        media.mapUri?.let { session.write(fetchBytes(spec, it)) }

        val seen = LinkedHashSet<String>()
        val keys = HashMap<String, ByteArray>()
        var failures = 0
        while (true) {
            for (segment in media.segments) {
                currentCoroutineContext().ensureActive()
                if (System.currentTimeMillis() >= session.rec.stop) return
                if (segment.uri in seen) continue
                seen += segment.uri
                if (seen.size > 3000) seen.remove(seen.first())
                try {
                    var data = fetchBytes(spec, segment.uri)
                    val key = segment.key
                    if (key != null) {
                        if (key.method != "AES-128" || key.uri == null) {
                            throw IllegalStateException("Unsupported HLS encryption (${key.method})")
                        }
                        val keyBytes = keys.getOrPut(key.uri) { fetchBytes(spec, key.uri) }
                        data = decrypt(data, keyBytes, key.iv, segment.sequence)
                    }
                    session.write(data)
                    failures = 0
                } catch (e: IOException) {
                    if (++failures > 30) throw e // skip the odd broken segment
                }
            }
            if (media.ended || System.currentTimeMillis() >= session.rec.stop) return
            delay((media.targetDuration * 500L).coerceIn(1_000L, 6_000L))
            try {
                media = Hls.parseMedia(fetchText(spec, playlistUrl), playlistUrl)
            } catch (e: IOException) {
                if (++failures > 30) throw e
                delay(2_000)
            }
        }
    }

    private fun decrypt(data: ByteArray, key: ByteArray, ivHex: String?, sequence: Long): ByteArray {
        val iv = ByteArray(16)
        val hex = ivHex?.removePrefix("0x")?.removePrefix("0X")
        if (hex != null && hex.length <= 32) {
            hex.padStart(32, '0').chunked(2).forEachIndexed { i, h -> iv[i] = h.toInt(16).toByte() }
        } else {
            for (i in 0 until 8) iv[15 - i] = (sequence ushr (8 * i)).toByte()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** Returns the playlist text if the URL serves HLS, or null for a plain/TS stream. */
    private suspend fun probeHls(spec: StreamSpec): String? = execute(spec, spec.url).use { response ->
        if (!response.isSuccessful) throw IOException("Server answered HTTP ${response.code}")
        val body = response.body ?: return@use null
        val type = response.header("Content-Type").orEmpty().lowercase()
        val input = BufferedInputStream(body.byteStream())
        input.mark(16)
        val head = ByteArray(7)
        val n = input.read(head)
        input.reset()
        val looksHls = "mpegurl" in type || (n == 7 && String(head) == "#EXTM3U")
        if (looksHls) input.readBytes().decodeToString() else null
    }

    private suspend fun fetchText(spec: StreamSpec, url: String): String = fetchBytes(spec, url).decodeToString()

    private suspend fun fetchBytes(spec: StreamSpec, url: String): ByteArray = execute(spec, url).use { response ->
        if (!response.isSuccessful) throw IOException("Server answered HTTP ${response.code}")
        response.body?.bytes() ?: throw IOException("Empty response")
    }

    /** Runs a request with the channel's headers; cancelling the coroutine aborts it. */
    private suspend fun execute(spec: StreamSpec, url: String): Response {
        val builder = Request.Builder().url(url)
            .header("User-Agent", spec.header("User-Agent") ?: TvPlayer.DEFAULT_USER_AGENT)
        spec.headers.forEach { (k, v) -> if (!k.equals("User-Agent", ignoreCase = true)) builder.header(k, v) }
        val call: Call = http.newCall(builder.build())
        val handle = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            return call.execute()
        } finally {
            handle.dispose()
        }
    }

    private fun fileName(rec: RecordingEntity): String {
        val date = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date(rec.start))
        val label = "${rec.channelName} - ${rec.title}".replace(Regex("[^A-Za-z0-9 ._-]"), "_").take(80).trim()
        return "${date}_${label}_${rec.id}"
    }
}
