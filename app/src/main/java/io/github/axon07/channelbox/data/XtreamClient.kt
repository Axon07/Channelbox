package io.github.axon07.channelbox.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.URLEncoder

/** Minimal Xtream Codes "player_api.php" client for live TV. */
class XtreamClient(private val http: OkHttpClient) {

    data class Login(val server: String, val user: String, val pass: String) {
        val base: String
            get() = server.trim().trimEnd('/').let {
                if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it"
            }

        private val u get() = enc(user)
        private val p get() = enc(pass)

        fun api(action: String? = null) =
            "$base/player_api.php?username=$u&password=$p" + (action?.let { "&action=$it" } ?: "")

        fun liveUrl(streamId: String) = "$base/live/$u/$p/$streamId.ts"

        val xmltvUrl: String get() = "$base/xmltv.php?username=$u&password=$p"

        fun timeshiftTemplate(streamId: String) =
            "$base/timeshift/$u/$p/{duration:60}/{Y}-{m}-{d}:{H}-{M}/$streamId.ts"

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }

    suspend fun load(login: Login): ParsedPlaylist {
        val auth = getJson(login.api()) as? JsonObject
            ?: throw IOException("Login failed – check the server, username and password")
        val userInfo = auth["user_info"] as? JsonObject
        if (userInfo == null || userInfo.str("auth") == "0") {
            throw IOException("Login failed – check the server, username and password")
        }
        userInfo.str("status")?.let { status ->
            if (!status.equals("Active", ignoreCase = true)) throw IOException("Account status: $status")
        }
        // Archive times are given in the server's own time zone.
        val serverZone = (auth["server_info"] as? JsonObject)?.str("timezone")?.takeIf { it.isNotBlank() }

        val categories = (getJson(login.api("get_live_categories")) as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .associate { (it.str("category_id") ?: "") to (it.str("category_name") ?: "Uncategorized") }

        val streams = (getJson(login.api("get_live_streams")) as? JsonArray).orEmpty()
        val channels = streams.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("stream_id") ?: return@mapNotNull null
            ParsedChannel(
                name = o.str("name")?.trim().orEmpty().ifBlank { "Channel $id" },
                number = o.str("num")?.toIntOrNull(),
                group = o.str("category_id")?.let { categories[it] } ?: "Uncategorized",
                logo = o.str("stream_icon")?.takeIf { it.isNotBlank() },
                url = login.liveUrl(id),
                epgId = o.str("epg_channel_id")?.takeIf { it.isNotBlank() },
                catchupDays = if (o.str("tv_archive") == "1") o.str("tv_archive_duration")?.toIntOrNull() ?: 0 else 0,
                catchupType = "default",
                catchupSource = login.timeshiftTemplate(id) + (serverZone?.let { "{tz=$it}" } ?: ""),
            )
        }
        return ParsedPlaylist(channels, listOf(login.xmltvUrl))
    }

    private suspend fun getJson(url: String): JsonElement =
        http.download(url) { Json.parseToJsonElement(it.bufferedReader().readText()) }

    private fun JsonObject.str(key: String): String? {
        val v = this[key] as? JsonPrimitive ?: return null
        return if (v is JsonNull) null else v.content
    }
}
