package io.github.axon07.channelbox.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .build()

/** Downloads [url] and hands the body stream to [block] on an IO thread. */
suspend fun <T> OkHttpClient.download(url: String, block: (InputStream) -> T): T =
    withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            throw IOException("Not a valid web address: ${redact(url)}")
        }
        newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server answered HTTP ${response.code} for ${redact(url)}")
            val body = response.body ?: throw IOException("Empty response from ${redact(url)}")
            block(body.byteStream())
        }
    }

/** Transparently un-gzips .gz guide files (detected by magic bytes, not file name). */
fun maybeGunzip(input: InputStream): InputStream {
    val buffered = BufferedInputStream(input, 64 * 1024)
    buffered.mark(2)
    val b1 = buffered.read()
    val b2 = buffered.read()
    buffered.reset()
    return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buffered, 64 * 1024) else buffered
}

/** Keeps usernames/passwords out of error messages shown on screen. */
fun redact(url: String): String = url
    .replace(Regex("(?i)(password|username)=[^&]*"), "$1=***")
    .substringBefore("/live/")
