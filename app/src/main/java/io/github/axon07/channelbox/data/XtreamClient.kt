package io.github.axon07.channelbox.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import okhttp3.OkHttpClient
import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.io.InputStreamReader
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

        val categories = categories(login, "get_live_categories")
        val channels = ArrayList<ParsedChannel>()
        streamObjects(login.api("get_live_streams")) { o ->
            val id = o["stream_id"] ?: return@streamObjects
            channels += ParsedChannel(
                name = o["name"]?.trim().orEmpty().ifBlank { "Channel $id" },
                number = o["num"]?.toIntOrNull(),
                group = o["category_id"]?.let { categories[it] } ?: "Uncategorized",
                logo = o["stream_icon"]?.takeIf { it.isNotBlank() },
                url = login.liveUrl(id),
                epgId = o["epg_channel_id"]?.takeIf { it.isNotBlank() },
                catchupDays = if (o["tv_archive"] == "1") o["tv_archive_duration"]?.toIntOrNull() ?: 0 else 0,
                catchupType = "default",
                catchupSource = login.timeshiftTemplate(id) + (serverZone?.let { "{tz=$it}" } ?: ""),
            )
        }
        return ParsedPlaylist(channels, listOf(login.xmltvUrl))
    }

    /**
     * Streams movies and shows into [writer] (episodes come later, from [episodes]).
     * A part that fails is skipped. [onProgress] gets the running count.
     */
    suspend fun loadVod(login: Login, playlistId: String, writer: VodWriter, onProgress: (Int) -> Unit = {}) {
        var n = 0
        try {
            val cats = categories(login, "get_vod_categories")
            streamObjects(login.api("get_vod_streams")) { o ->
                val id = o["stream_id"] ?: return@streamObjects
                val url = login.movieUrl(id, o["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4")
                writer.add(
                    VodEntity(
                        id = url, playlistId = playlistId, kind = VodKind.MOVIE,
                        title = o["name"]?.trim().orEmpty().ifBlank { "Movie $id" },
                        category = o["category_id"]?.let { cats[it] } ?: "Uncategorized",
                        poster = o["stream_icon"]?.takeIf { it.isNotBlank() }, url = url, headers = null,
                        seriesKey = null, season = 0, episode = 0, added = o["added"]?.toLongOrNull() ?: 0,
                        sortIndex = n, plot = null, rating = o["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                        year = o["year"]?.takeIf { it.isNotBlank() },
                    )
                )
                if (++n % 2_000 == 0) onProgress(n)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Movies unavailable – carry on with shows.
        }
        try {
            val cats = categories(login, "get_series_categories")
            streamObjects(login.api("get_series")) { o ->
                val id = o["series_id"] ?: return@streamObjects
                writer.add(
                    VodEntity(
                        id = "xseries:$playlistId:$id", playlistId = playlistId, kind = VodKind.SERIES,
                        title = o["name"]?.trim().orEmpty().ifBlank { "Show $id" },
                        category = o["category_id"]?.let { cats[it] } ?: "Uncategorized",
                        poster = o["cover"]?.takeIf { it.isNotBlank() }, url = null, headers = null, seriesKey = id,
                        season = 0, episode = 0, added = o["last_modified"]?.toLongOrNull() ?: 0, sortIndex = n,
                        plot = o["plot"]?.takeIf { it.isNotBlank() },
                        rating = o["rating"]?.takeIf { it.isNotBlank() && it != "0" },
                        year = (o["releaseDate"] ?: o["release_date"])?.take(4)?.takeIf { it.isNotBlank() },
                    )
                )
                if (++n % 2_000 == 0) onProgress(n)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Shows unavailable.
        }
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

    private suspend fun categories(login: Login, action: String): Map<String, String> {
        val out = HashMap<String, String>()
        streamObjects(login.api(action)) { o -> out[o["category_id"] ?: ""] = o["category_name"] ?: "Uncategorized" }
        return out
    }

    /**
     * Reads a JSON array of objects one object at a time, keeping only their simple fields.
     * Provider lists can hold tens of thousands of entries – far too many to load in one piece.
     */
    private suspend fun streamObjects(url: String, onItem: (Map<String, String>) -> Unit) {
        http.download(url) { input ->
            val r = JsonReader(InputStreamReader(input, Charsets.UTF_8))
            r.isLenient = true
            if (r.peek() != JsonToken.BEGIN_ARRAY) {
                r.skipValue() // e.g. {} or [] variants for "nothing here"
                return@download
            }
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    continue
                }
                val o = HashMap<String, String>(32)
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    when (r.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> o[name] = r.nextString()
                        JsonToken.BOOLEAN -> o[name] = r.nextBoolean().toString()
                        JsonToken.NULL -> r.nextNull()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                onItem(o)
            }
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun getJson(url: String): JsonElement =
        http.download(url) { Json.decodeFromStream(JsonElement.serializer(), it) }

    private fun JsonObject.str(key: String): String? {
        val v = this[key] as? JsonPrimitive ?: return null
        return if (v is JsonNull) null else v.content
    }
}
