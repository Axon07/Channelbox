package io.github.axon07.channelbox.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DummyExoMediaDrm
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.UnsupportedDrmException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import io.github.axon07.channelbox.data.StorageSettings
import io.github.axon07.channelbox.data.StreamSpec
import io.github.axon07.channelbox.data.StreamSpecs
import io.github.axon07.channelbox.data.UserAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.util.Locale
import java.util.UUID

sealed interface PlayerStatus {
    data object Idle : PlayerStatus
    data object Buffering : PlayerStatus
    data object Playing : PlayerStatus
    data class Error(val message: String) : PlayerStatus
}

/** What's playing right now, for the info banner. */
data class StreamInfo(val video: String?, val audio: String?, val bitrate: String?)

/** An audio, video or subtitle track the viewer can pick. [group] null = "Off" / "Auto". */
data class TrackOption(val label: String, val group: Tracks.Group?, val index: Int, val selected: Boolean)

/**
 * One ExoPlayer for the whole app.
 *
 * Live channels: every failure (bad link, unsupported format, DRM error) becomes an
 * on-screen message and an automatic retry – never a crash. When the stream type isn't
 * known it tries plain/TS, then HLS, then DASH. Widevine channels that fail are retried
 * once at security level L3, which fixes many cheap boxes.
 *
 * Catch-up programmes and recordings play "on demand": seekable, no auto-reconnect.
 */
@OptIn(UnstableApi::class)
class TvPlayer(
    private val context: Context,
    private val http: OkHttpClient,
    preferSoftwareAudio: Boolean = false,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow<PlayerStatus>(PlayerStatus.Idle)
    val status: StateFlow<PlayerStatus> = _status.asStateFlow()

    /** Bumped whenever the available/selected audio & subtitle tracks change. */
    private val _tracksVersion = MutableStateFlow(0)
    val tracksVersion: StateFlow<Int> = _tracksVersion.asStateFlow()

    private var spec: StreamSpec? = null
    private var candidates: List<String?> = listOf(null)
    private var attempt = 0
    private var confirmed = false // the current type has played at least once
    private var retries = 0
    private var widevineL3 = false
    private var onDemand = false

    /** False while a recording or catch-up programme is playing. */
    val isLive: Boolean get() = !onDemand

    // Assume a fast connection at the start, so streams don't open at their lowest quality.
    private val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
        .setInitialBitrateEstimate(20_000_000L)
        .build()

    /** Login for playing recordings saved on an SMB share. */
    var storageSettings: () -> StorageSettings = { StorageSettings() }

    // FFmpeg (bundled) decodes AC3 / E-AC3 / DTS / TrueHD audio when the box can't.
    private val renderers = DefaultRenderersFactory(context)
        .setExtensionRendererMode(
            if (preferSoftwareAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        )
        .setEnableDecoderFallback(true)

    val exo: ExoPlayer = ExoPlayer.Builder(context, renderers)
        .setBandwidthMeter(bandwidthMeter)
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            setWakeMode(C.WAKE_MODE_NETWORK)
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            retries = 0
                            confirmed = true
                            _status.value = PlayerStatus.Playing
                        }
                        Player.STATE_BUFFERING -> _status.value = PlayerStatus.Buffering
                        // A live stream "ending" means the server closed the connection: reconnect.
                        Player.STATE_ENDED -> {
                            if (isLive) scheduleRetry("Reconnecting…") else _status.value = PlayerStatus.Idle
                        }
                        Player.STATE_IDLE -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) = handleError(error)

                override fun onTracksChanged(tracks: Tracks) {
                    _tracksVersion.value++
                }
            })
        }

    /** Live channel. */
    fun play(stream: StreamSpec) = open(stream, onDemand = false)

    /** Catch-up programme: seekable, plays once. */
    fun playOnDemand(stream: StreamSpec, startAt: Long = 0) {
        open(stream, onDemand = true)
        if (startAt > 0) exo.seekTo(startAt)
    }

    /** Recorded file. */
    fun playFile(path: String) =
        open(StreamSpec(Uri.fromFile(File(path)).toString(), emptyMap(), null, null), onDemand = true)

    private fun open(stream: StreamSpec, onDemand: Boolean) {
        handler.removeCallbacksAndMessages(null)
        this.onDemand = onDemand
        spec = stream
        val isHttp = stream.url.startsWith("http://", true) || stream.url.startsWith("https://", true)
        candidates = when {
            stream.mime != null -> listOf(stream.mime)
            !isHttp -> listOf(null) // rtsp://, rtmp://, udp://, file:// – the player knows these
            // DRM-protected IPTV streams are almost always DASH, even without ".mpd" in the link.
            stream.drm != null -> listOf(StreamSpecs.MIME_DASH, null, StreamSpecs.MIME_HLS)
            else -> listOf(null, StreamSpecs.MIME_HLS, StreamSpecs.MIME_DASH)
        }
        attempt = 0
        confirmed = false
        retries = 0
        widevineL3 = false
        start()
    }

    /** Called when the app comes back to the foreground. */
    fun resume() {
        if (onDemand) exo.prepare() // keeps the position
        else if (spec != null) start()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        exo.stop()
        _status.value = PlayerStatus.Idle
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        exo.release()
    }

    private fun start() {
        val s = spec ?: return
        val mime = candidates.getOrNull(attempt)
        try {
            exo.setMediaSource(buildSource(s, mime))
            exo.prepare()
            exo.playWhenReady = true
            _status.value = PlayerStatus.Buffering
        } catch (e: Exception) {
            // e.g. an unsupported stream type – try the next one instead of crashing.
            failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildSource(s: StreamSpec, mime: String?): MediaSource {
        val userAgent = s.header("User-Agent") ?: UserAgent.current
        val httpSource = OkHttpDataSource.Factory(http)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(s.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        // DefaultDataSource adds udp://, rtmp://, file:// … and uses OkHttp for http(s).
        val dataSource = if (s.url.startsWith("smb://")) SmbDataSource.Factory(storageSettings)
        else DefaultDataSource.Factory(context, httpSource)
        val sources = DefaultMediaSourceFactory(context).setDataSourceFactory(dataSource)
        val item = MediaItem.Builder().setUri(s.url)
        if (mime != null) item.setMimeType(mime)

        val drm = s.drm
        if (drm != null) {
            if (drm.clearKeys.isNotEmpty()) {
                // ClearKey with the keys in the playlist: answer license requests locally.
                val manager = ClearKeyDrmSessionManager(drm.clearKeys)
                sources.setDrmSessionManagerProvider { manager }
            } else {
                val scheme = when (drm.type) {
                    StreamSpecs.CLEARKEY -> C.CLEARKEY_UUID
                    // Many Android TV boxes have no PlayReady; most license servers also speak Widevine.
                    StreamSpecs.PLAYREADY -> if (supported(C.PLAYREADY_UUID)) C.PLAYREADY_UUID else C.WIDEVINE_UUID
                    else -> C.WIDEVINE_UUID
                }
                val callback = HttpMediaDrmCallback(drm.licenseUrl, httpSource).apply {
                    drm.licenseHeaders.forEach { (k, v) -> setKeyRequestProperty(k, v) }
                }
                val forceL3 = widevineL3 && scheme == C.WIDEVINE_UUID
                val manager = DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(scheme, drmProvider(forceL3))
                    .setMultiSession(scheme == C.CLEARKEY_UUID)
                    .setPlayClearSamplesWithoutKeys(true)
                    .build(callback)
                sources.setDrmSessionManagerProvider { manager }
            }
        }
        return sources.createMediaSource(item.build())
    }

    private fun drmProvider(forceL3: Boolean) = ExoMediaDrm.Provider { uuid: UUID ->
        try {
            FrameworkMediaDrm.newInstance(uuid).also { drm ->
                if (forceL3) runCatching { drm.setPropertyString("securityLevel", "L3") }
            }
        } catch (e: UnsupportedDrmException) {
            DummyExoMediaDrm()
        }
    }

    private fun supported(scheme: UUID): Boolean =
        runCatching { android.media.MediaDrm.isCryptoSchemeSupported(scheme) }.getOrDefault(false)

    private fun handleError(error: PlaybackException) {
        val s = spec
        // Widevine at L1 fails on many cheap boxes; L3 (software) usually works.
        val isDrmError = error.errorCode in 6000..6999
        if (isDrmError && !widevineL3 && s?.drm != null && s.drm.clearKeys.isEmpty() &&
            (s.drm.type == StreamSpecs.WIDEVINE || (s.drm.type == StreamSpecs.PLAYREADY && !supported(C.PLAYREADY_UUID)))
        ) {
            widevineL3 = true
            handler.post { start() }
            return
        }
        if (!isLive) {
            _status.value = PlayerStatus.Error("Can't play this (${describe(error)})")
            if (!confirmed && attempt < candidates.lastIndex) {
                attempt++
                handler.post { start() }
            }
            return
        }
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition()
            exo.prepare()
            return
        }
        failed(describe(error))
    }

    /** What went wrong, in words. For HTTP errors: the server's status code and what it usually means. */
    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null && cause !is HttpDataSource.InvalidResponseCodeException) cause = cause.cause
        val code = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            ?: return error.errorCodeName
        val hint = when (code) {
            401, 403 -> "the provider refused it – the account may have expired, be in use on too many " +
                "devices, or block this app's user agent (Settings → Network → User agent)"
            404, 410 -> "the link no longer works – reload the playlist"
            429, 456, 458, 509 -> "too many connections on this account – stop it on other devices"
            in 500..599 -> "the provider's server has a problem"
            else -> null
        }
        return if (hint != null) "HTTP $code: $hint" else "HTTP $code"
    }

    private fun failed(reason: String) {
        if (spec == null) return
        if (!confirmed && attempt < candidates.lastIndex) {
            attempt++
            handler.post { start() }
            return
        }
        if (!isLive) {
            _status.value = PlayerStatus.Error("Can't play this ($reason)")
            return
        }
        scheduleRetry("Channel unavailable – retrying… ($reason)")
    }

    private fun scheduleRetry(message: String) {
        val current = spec ?: return
        retries++
        _status.value = PlayerStatus.Error(message)
        val delayMs = (retries * 3_000L).coerceAtMost(30_000L)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (spec === current && isLive) {
                if (!confirmed) attempt = 0
                start()
            }
        }, delayMs)
    }

    // ---- Audio / subtitle tracks ---------------------------------------------------------------

    fun audioTracks(): List<TrackOption> = tracks(C.TRACK_TYPE_AUDIO)

    fun subtitleTracks(): List<TrackOption> {
        val list = tracks(C.TRACK_TYPE_TEXT)
        val disabled = C.TRACK_TYPE_TEXT in exo.trackSelectionParameters.disabledTrackTypes
        val anySelected = !disabled && list.any { it.selected }
        return listOf(TrackOption("Off", null, 0, !anySelected)) + if (disabled) list.map { it.copy(selected = false) } else list
    }

    private fun tracks(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        exo.currentTracks.groups.filter { it.type == type }.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                out += TrackOption(describe(group.getTrackFormat(i), out.size + 1), group, i, group.isTrackSelected(i))
            }
        }
        return out
    }

    /** Video qualities, "Auto" first. */
    fun videoTracks(): List<TrackOption> {
        val list = tracks(C.TRACK_TYPE_VIDEO)
        val overridden = exo.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
        val playing = exo.videoFormat?.height?.takeIf { it > 0 }?.let { " (now ${it}p)" }.orEmpty()
        return listOf(TrackOption("Auto$playing", null, 0, !overridden)) +
            list.sortedByDescending { it.group?.getTrackFormat(it.index)?.height ?: 0 }
                .map { it.copy(selected = overridden && it.selected) }
    }

    /**
     * Quality choice: 0 = best available, >0 = best up to that height, both held steady;
     * [ADAPTIVE] = switch with the connection speed. Switching levels mid-stream can show green
     * blocks on some streams, so steady is the default.
     */
    fun setMaxQuality(height: Int) {
        val b = exo.trackSelectionParameters.buildUpon()
        if (height <= 0) b.clearVideoSizeConstraints() else b.setMaxVideoSize(Int.MAX_VALUE, height)
        b.setForceHighestSupportedBitrate(height != ADAPTIVE)
        exo.trackSelectionParameters = b.build()
    }

    /** Languages of the subtitles / closed captions the stream offers. */
    fun captionLanguages(): List<String> = tracks(C.TRACK_TYPE_TEXT).map { it.label.substringBefore(" · ") }.distinct()

    val captionsOn: Boolean
        get() = C.TRACK_TYPE_TEXT !in exo.trackSelectionParameters.disabledTrackTypes &&
            tracks(C.TRACK_TYPE_TEXT).any { it.selected }

    /** CC button: turns captions off, or on in the box's language (else the first one). Returns the new state. */
    fun toggleCaptions(): Boolean {
        val text = tracks(C.TRACK_TYPE_TEXT)
        if (captionsOn || text.isEmpty()) {
            select(C.TRACK_TYPE_TEXT, TrackOption("Off", null, 0, false))
            return false
        }
        val lang = Locale.getDefault().language
        val pick = text.firstOrNull { it.group?.getTrackFormat(it.index)?.language?.startsWith(lang) == true } ?: text.first()
        select(C.TRACK_TYPE_TEXT, pick)
        return true
    }

    fun streamInfo(): StreamInfo {
        val v = exo.videoFormat
        val a = exo.audioFormat
        val video = v?.let { f ->
            listOfNotNull(
                if (f.height > 0) "${f.height}p" else null,
                f.frameRate.takeIf { it > 0 }?.let { "${Math.round(it)} fps" },
                codecName(f.sampleMimeType ?: f.codecs),
            ).joinToString(" · ").ifEmpty { null }
        }
        val audio = a?.let { describe(it, 1).substringAfter("Track 1 · ", describe(it, 1)) }
        val bits = (v?.bitrate?.takeIf { it > 0 } ?: bandwidthMeter.bitrateEstimate.toInt().takeIf { it > 0 })
            ?.let { String.format(Locale.US, "%.1f Mbps", it / 1_000_000.0) }
        return StreamInfo(video, audio, bits)
    }

    private fun codecName(mime: String?): String? = when {
        mime == null -> null
        mime.contains("avc") || mime == MimeTypes.VIDEO_H264 -> "H.264"
        mime.contains("hev") || mime.contains("hvc") || mime == MimeTypes.VIDEO_H265 -> "H.265"
        mime.contains("av01") || mime == MimeTypes.VIDEO_AV1 -> "AV1"
        mime == MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        mime.contains("vp9") || mime == MimeTypes.VIDEO_VP9 -> "VP9"
        else -> null
    }

    fun select(type: Int, option: TrackOption) {
        val builder = exo.trackSelectionParameters.buildUpon()
        val group = option.group
        if (group == null && type == C.TRACK_TYPE_VIDEO) {
            builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else if (group == null) {
            builder.setTrackTypeDisabled(type, true)
        } else {
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.index))
        }
        exo.trackSelectionParameters = builder.build()
    }

    private fun describe(f: Format, number: Int): String {
        if (f.sampleMimeType?.startsWith("video/") == true || f.height > 0) {
            return listOfNotNull(
                if (f.height > 0) "${f.height}p" else "Video $number",
                f.frameRate.takeIf { it > 0 }?.let { "${Math.round(it)} fps" },
                f.bitrate.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f Mbps", it / 1_000_000.0) },
            ).joinToString(" · ")
        }
        val language = f.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale(it).displayLanguage.takeIf { name -> name.isNotBlank() } ?: it }
        val name = f.label?.takeIf { it.isNotBlank() } ?: language ?: "Track $number"
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MP2/MP3"
            MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "Closed captions"
            else -> null
        }
        val channels = when (f.channelCount) {
            1 -> "mono"
            2 -> "stereo"
            6 -> "5.1"
            8 -> "7.1"
            Format.NO_VALUE -> null
            else -> "${f.channelCount} ch"
        }
        return listOfNotNull(name, codec, channels).distinct().joinToString(" · ")
    }

    companion object {
        const val DEFAULT_USER_AGENT = UserAgent.DEFAULT
    }
}

/** [TvPlayer.setMaxQuality] value: let quality follow the connection speed. */
const val ADAPTIVE = -1
