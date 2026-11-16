package io.github.axon07.channelbox.data

import java.io.BufferedReader

data class ParsedChannel(
    val name: String,
    val number: Int?,
    val group: String,
    val logo: String?,
    val url: String,
    val epgId: String?,
    val catchupDays: Int,
    val headers: Map<String, String> = emptyMap(),
    val drmType: String? = null,
    val drmKey: String? = null,
    val manifestType: String? = null,
)

data class ParsedPlaylist(val channels: List<ParsedChannel>, val epgUrl: String?)

/**
 * Parses extended M3U playlists:
 *
 *   #EXTM3U url-tvg="http://…/guide.xml.gz"
 *   #KODIPROP:inputstream.adaptive.license_type=clearkey
 *   #KODIPROP:inputstream.adaptive.license_key=<kid>:<key>
 *   #EXTINF:-1 tvg-id="cbc.ca" tvg-logo="…" group-title="News" tvg-chno="5",CBC News
 *   #EXTVLCOPT:http-user-agent=Mozilla/5.0
 *   http://provider/stream/123.mpd|Referer=http://site
 *
 * #KODIPROP / #EXTVLCOPT / #EXTHTTP lines may come before or after #EXTINF;
 * they apply to the next stream URL.
 */
object M3uParser {

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    private val jsonPair = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")

    private class Pending {
        var info: String? = null
        var group: String? = null
        val headers = LinkedHashMap<String, String>()
        var drmType: String? = null
        var drmKey: String? = null
        var manifestType: String? = null
    }

    fun parse(reader: BufferedReader): ParsedPlaylist {
        var epgUrl: String? = null
        var p = Pending()
        val out = ArrayList<ParsedChannel>()

        reader.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    val a = attributes(line)
                    epgUrl = a["url-tvg"] ?: a["x-tvg-url"] ?: epgUrl
                }
                line.startsWith("#EXTINF") -> p.info = line
                line.startsWith("#EXTGRP:") -> p.group = line.substringAfter(':').trim()
                line.startsWith("#KODIPROP:", ignoreCase = true) -> kodiProp(p, line.substringAfter(':'))
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> vlcOpt(p, line.substringAfter(':'))
                line.startsWith("#EXTHTTP:", ignoreCase = true) ->
                    jsonPair.findAll(line.substringAfter(':')).forEach { p.headers[it.groupValues[1]] = it.groupValues[2] }
                line.startsWith("#") -> Unit
                else -> {
                    val info = p.info
                    if (info != null) out += build(info, p, line)
                    p = Pending()
                }
            }
        }

        val guide = epgUrl?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        return ParsedPlaylist(out, guide)
    }

    private fun kodiProp(p: Pending, kv: String) {
        val key = kv.substringBefore('=').trim().lowercase()
        val value = kv.substringAfter('=', "").trim()
        when (key) {
            "inputstream.adaptive.license_type" -> p.drmType = value
            "inputstream.adaptive.license_key" -> p.drmKey = value
            "inputstream.adaptive.manifest_type" -> p.manifestType = value
            "inputstream.adaptive.stream_headers",
            "inputstream.adaptive.common_headers",
            "inputstream.adaptive.manifest_headers" -> p.headers += StreamSpecs.parseHeaderString(value)
        }
    }

    private fun vlcOpt(p: Pending, kv: String) {
        val key = kv.substringBefore('=').trim().lowercase()
        val value = kv.substringAfter('=', "").trim()
        if (value.isEmpty()) return
        when (key) {
            "http-user-agent" -> p.headers["User-Agent"] = value
            "http-referrer", "http-referer" -> p.headers["Referer"] = value
            "http-origin" -> p.headers["Origin"] = value
            "http-cookie" -> p.headers["Cookie"] = value
        }
    }

    internal fun attributes(line: String): Map<String, String> =
        attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    /** Text after the first comma that is not inside quotes. */
    internal fun displayName(info: String): String {
        var inQuote = false
        for (i in info.indices) {
            val c = info[i]
            if (c == '"') inQuote = !inQuote
            else if (c == ',' && !inQuote) return info.substring(i + 1).trim()
        }
        return ""
    }

    private fun build(info: String, p: Pending, url: String): ParsedChannel {
        val a = attributes(info)
        val name = displayName(info)
            .ifBlank { a["tvg-name"].orEmpty() }
            .ifBlank { url }
        return ParsedChannel(
            name = name,
            number = a["tvg-chno"]?.trim()?.toIntOrNull(),
            group = a["group-title"]?.takeIf { it.isNotBlank() } ?: p.group ?: "Uncategorized",
            logo = a["tvg-logo"]?.takeIf { it.isNotBlank() },
            url = url,
            epgId = a["tvg-id"]?.takeIf { it.isNotBlank() },
            catchupDays = (a["catchup-days"] ?: a["tvg-rec"] ?: a["timeshift"])?.trim()?.toIntOrNull() ?: 0,
            headers = LinkedHashMap(p.headers),
            drmType = p.drmType,
            drmKey = p.drmKey,
            manifestType = p.manifestType,
        )
    }
}
