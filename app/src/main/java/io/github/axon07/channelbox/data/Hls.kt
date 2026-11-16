package io.github.axon07.channelbox.data

import java.net.URI

/** Just enough HLS playlist parsing to record a live stream. */
object Hls {

    data class Key(val method: String, val uri: String?, val iv: String?)
    data class Segment(val sequence: Long, val uri: String, val key: Key?)
    data class Media(
        val targetDuration: Int,
        val segments: List<Segment>,
        val mapUri: String?,
        val ended: Boolean,
    )

    private val attrRegex = Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""")

    fun isPlaylist(text: String) = text.trimStart('﻿', ' ', '\n', '\r').startsWith("#EXTM3U")

    fun isMaster(text: String) = "#EXT-X-STREAM-INF" in text

    private fun attributes(line: String): Map<String, String> =
        attrRegex.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    /** Highest-bandwidth variant of a master playlist. */
    fun bestVariant(text: String, baseUrl: String): String? {
        var best: String? = null
        var bestBandwidth = -1L
        var pendingBandwidth: Long? = null
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-STREAM-INF") ->
                    pendingBandwidth = attributes(line)["BANDWIDTH"]?.toLongOrNull() ?: 0L
                line.isNotEmpty() && !line.startsWith("#") && pendingBandwidth != null -> {
                    val bw = pendingBandwidth ?: 0L
                    if (bw > bestBandwidth) {
                        bestBandwidth = bw
                        best = resolve(baseUrl, line)
                    }
                    pendingBandwidth = null
                }
            }
        }
        return best
    }

    fun parseMedia(text: String, baseUrl: String): Media {
        var sequence = 0L
        var target = 6
        var key: Key? = null
        var map: String? = null
        var ended = false
        var expectUri = false
        val segments = ArrayList<Segment>()
        text.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    target = line.substringAfter(':').trim().toDoubleOrNull()?.toInt() ?: 6
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line)
                    val method = a["METHOD"] ?: "NONE"
                    key = if (method == "NONE") null else Key(method, a["URI"]?.let { resolve(baseUrl, it) }, a["IV"])
                }
                line.startsWith("#EXT-X-MAP:") -> map = attributes(line)["URI"]?.let { resolve(baseUrl, it) }
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXTINF") -> expectUri = true
                line.isEmpty() || line.startsWith("#") -> Unit
                expectUri -> {
                    segments += Segment(sequence, resolve(baseUrl, line), key)
                    sequence++
                    expectUri = false
                }
            }
        }
        return Media(target, segments, map, ended)
    }

    fun resolve(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            URI(base.substringBefore('|')).resolve(ref).toString()
        } catch (e: Exception) {
            // URI() is strict about odd characters; fall back to plain string joining.
            val noQuery = base.substringBefore('?')
            when {
                ref.startsWith("/") -> {
                    val schemeEnd = noQuery.indexOf("://")
                    val hostEnd = noQuery.indexOf('/', if (schemeEnd >= 0) schemeEnd + 3 else 0)
                    (if (hostEnd >= 0) noQuery.substring(0, hostEnd) else noQuery) + ref
                }
                else -> noQuery.substringBeforeLast('/') + "/" + ref
            }
        }
    }
}
