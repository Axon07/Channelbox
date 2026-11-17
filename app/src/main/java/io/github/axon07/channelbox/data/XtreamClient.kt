package io.github.axon07.channelbox.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
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

        fun api(action: String? = null, extra: String = "") =
            "$base/player_api.php?username=$u&password=$p" + (action?.let { "&action=$it" } ?: "") + extra

        fun movieUrl(id: String, ext: String) = "$base/movie/$u/$p/$id.$ext"
        fun episodeUrl(id: String, ext: String) = "$base/series/$u/$p/$id.$ext"

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

    /** Movies and shows (episodes come later, from [episodes]). A part that fails is skipped. */
    suspend fun loadVod(login: Login, playlistId: String): List<VodEntity> {
        val out = ArrayList<VodEntity>()
        runCatching {
            val cats = categories(login, "get_vod_categories")
            (getJson(login.api("get_vod_streams")) as? JsonArray).orEmpty().forEachIndexed { i, el ->
                val o = el as? JsonObject ?: return@forEachIndexed
                val id = o.str("stream_id") ?: return@forEachIndexed
                val url = login.movieUrl(id, o.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4")
                out += VodEntity(
                    id = url, playlistId = playlistId, kind = VodKind.MOVIE,
                    title = o.str("name")?.trim().orEmpty().ifBlank { "Movie $id" },
                    category = o.str("category_id")?.let { cats[it] } ?: "Uncategorized",
                    poster = o.str("stream_icon")?.takeIf { it.isNotBlank() }, url = url, headers = null,
                    seriesKey = null, season = 0, episode = 0, added = o.str("added")?.toLongOrNull() ?: 0,
                    sortIndex = i, plot = null, rating = o.str("rating")?.takeIf { it.isNotBlank() && it != "0" },
                    year = o.str("year")?.takeIf { it.isNotBlank() },
                )
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        runCatching {
            val cats = categories(login, "get_series_categories")
            (getJson(login.api("get_series")) as? JsonArray).orEmpty().forEachIndexed { i, el ->
                val o = el as? JsonObject ?: return@forEachIndexed
                val id = o.str("series_id") ?: return@forEachIndexed
                out += VodEntity(
                    id = "xseries:$playlistId:$id", playlistId = playlistId, kind = VodKind.SERIES,
                    title = o.str("name")?.trim().orEmpty().ifBlank { "Show $id" },
                    category = o.str("category_id")?.let { cats[it] } ?: "Uncategorized",
                    poster = o.str("cover")?.takeIf { it.isNotBlank() }, url = null, headers = null, seriesKey = id,
                    season = 0, episode = 0, added = o.str("last_modified")?.toLongOrNull() ?: 0, sortIndex = i,
                    plot = o.str("plot")?.takeIf { it.isNotBlank() },
                    rating = o.str("rating")?.takeIf { it.isNotBlank() && it != "0" },
                    year = (o.str("releaseDate") ?: o.str("release_date"))?.take(4)?.takeIf { it.isNotBlank() },
                )
            }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        return out
    }

    /** Episodes of one Xtream show, in season order. */
    suspend fun episodes(login: Login, playlistId: String, seriesId: String): List<VodEntity> {
        val root = getJson(login.api("get_series_info", "&series_id=$seriesId")) as? JsonObject ?: return emptyList()
        val seasons = root["episodes"]
        val lists: List<JsonElement> = when (seasons) {
            is JsonObject -> seasons.values.flatMap { (it as? JsonArray).orEmpty() }
            is JsonArray -> seasons.flatMap { (it as? JsonArray) ?: listOf(it) }
            else -> emptyList()
        }
        return lists.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val id = o.str("id") ?: return@mapIndexedNotNull null
            val info = o["info"] as? JsonObject
            val url = login.episodeUrl(id, o.str("container_extension")?.takeIf { it.isNotBlank() } ?: "mp4")
            VodEntity(
                id = url, playlistId = playlistId, kind = VodKind.EPISODE,
                title = o.str("title")?.trim().orEmpty().ifBlank { "Episode ${o.str("episode_num") ?: i + 1}" },
                category = "", poster = info?.str("movie_image")?.takeIf { it.isNotBlank() }, url = url, headers = null,
                seriesKey = seriesId, season = o.str("season")?.toIntOrNull() ?: 0,
                episode = o.str("episode_num")?.toIntOrNull() ?: (i + 1), added = 0, sortIndex = i,
                plot = info?.str("plot")?.takeIf { it.isNotBlank() },
                rating = info?.str("duration")?.takeIf { it.isNotBlank() }, // shown as the episode's length
                year = null,
            )
        }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private suspend fun categories(login: Login, action: String): Map<String, String> =
        (getJson(login.api(action)) as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .associate { (it.str("category_id") ?: "") to (it.str("category_name") ?: "Uncategorized") }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun getJson(url: String): JsonElement =
        http.download(url) { Json.decodeFromStream(JsonElement.serializer(), it) }

    private fun JsonObject.str(key: String): String? {
        val v = this[key] as? JsonPrimitive ?: return null
        return if (v is JsonNull) null else v.content
    }
}
