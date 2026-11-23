package io.github.axon07.channelbox.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

enum class SourceType { NONE, M3U, XTREAM, STALKER }

/**
 * One TV service: an M3U link, an Xtream Codes login or a Stalker portal (portal URL + MAC
 * address), plus an optional guide. A Stalker portal keeps its URL in [xtServer] and the optional
 * portal login in [xtUser] / [xtPass].
 */
data class Playlist(
    val id: String,
    val name: String = "",
    val type: SourceType = SourceType.M3U,
    val m3uUrl: String = "",
    /** Extra XMLTV guide URL(s) for this playlist, one per line or comma separated. */
    val epgUrl: String = "",
    val xtServer: String = "",
    val xtUser: String = "",
    val xtPass: String = "",
    val enabled: Boolean = true,
    /** Load the playlist's movies & shows (off = live TV only, much faster for huge lists). */
    val includeVod: Boolean = true,
    /** Stalker portal: the MAC address the provider registered, and optional box identity. */
    val mac: String = "",
    val serial: String = "",
    val deviceId1: String = "",
    val deviceId2: String = "",
    /** Use the guide the provider gives with the playlist (M3U url-tvg, Xtream xmltv.php, portal guide). */
    val useProviderGuide: Boolean = true,
) {
    fun stalkerAccount() = StalkerAccount(
        portalUrl = xtServer, mac = mac, login = xtUser, password = xtPass,
        serial = serial, deviceId1 = deviceId1, deviceId2 = deviceId2,
    )

    /** Name to show: the one the user gave, else the server's host name. */
    val displayName: String
        get() = name.trim().ifEmpty {
            val url = if (type == SourceType.XTREAM || type == SourceType.STALKER) xtServer else m3uUrl
            runCatching { java.net.URI(url.trim()).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
                ?: "Playlist"
        }

    val summary: String
        get() = when (type) {
            SourceType.XTREAM -> "Xtream Codes · ${xtServer.trim()} · ${xtUser.trim()}"
            SourceType.STALKER -> "Stalker portal · ${xtServer.trim()} · ${StalkerClient.normalizeMac(mac)}"
            else -> "M3U · ${redact(m3uUrl.trim())}"
        }

    fun toJson() = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "type" to JsonPrimitive(type.name),
            "m3u" to JsonPrimitive(m3uUrl), "epg" to JsonPrimitive(epgUrl), "server" to JsonPrimitive(xtServer),
            "user" to JsonPrimitive(xtUser), "pass" to JsonPrimitive(xtPass), "enabled" to JsonPrimitive(enabled),
            "vod" to JsonPrimitive(includeVod), "mac" to JsonPrimitive(mac), "sn" to JsonPrimitive(serial),
            "dev1" to JsonPrimitive(deviceId1), "dev2" to JsonPrimitive(deviceId2),
            "providerGuide" to JsonPrimitive(useProviderGuide),
        )
    )

    companion object {
        fun newId(): String = java.util.UUID.randomUUID().toString().take(8)

        fun fromJson(o: JsonObject): Playlist? {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val id = str("id").ifEmpty { return null }
            return Playlist(
                id = id, name = str("name"),
                type = runCatching { SourceType.valueOf(str("type")) }.getOrDefault(SourceType.M3U),
                m3uUrl = str("m3u"), epgUrl = str("epg"), xtServer = str("server"), xtUser = str("user"),
                xtPass = str("pass"), enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                includeVod = (o["vod"] as? JsonPrimitive)?.booleanOrNull ?: true,
                mac = str("mac"), serial = str("sn"), deviceId1 = str("dev1"), deviceId2 = str("dev2"),
                useProviderGuide = (o["providerGuide"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }
    }
}

/**
 * A TV guide (XMLTV) source added in Settings → TV guide, like TiviMate's EPG sources. Sources are
 * used in list order (an earlier one wins for a channel), for the [playlists] they're attached to
 * (empty = every playlist). [shiftMinutes] moves all of its programmes (wrong time zone in the file).
 */
data class EpgSource(
    val id: String,
    val name: String = "",
    val url: String,
    val enabled: Boolean = true,
    val shiftMinutes: Int = 0,
    val playlists: List<String> = emptyList(),
) {
    val displayName: String
        get() = name.trim().ifEmpty {
            runCatching { java.net.URI(url.trim()).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "TV guide"
        }

    fun usedBy(playlistId: String) = playlists.isEmpty() || playlistId in playlists

    fun toJson() = JsonObject(
        mapOf(
            "id" to JsonPrimitive(id), "name" to JsonPrimitive(name), "url" to JsonPrimitive(url),
            "enabled" to JsonPrimitive(enabled), "shift" to JsonPrimitive(shiftMinutes),
            "playlists" to JsonArray(playlists.map { JsonPrimitive(it) }),
        )
    )

    companion object {
        fun newId(): String = "g" + java.util.UUID.randomUUID().toString().take(7)

        fun fromJson(o: JsonObject): EpgSource? {
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val url = str("url").ifBlank { return null }
            return EpgSource(
                id = str("id").ifEmpty { newId() }, name = str("name"), url = url,
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                shiftMinutes = str("shift").toIntOrNull() ?: 0,
                playlists = (o["playlists"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            )
        }
    }
}

/** How a guide link did at the last update: when, how many programmes and channels, or the error. */
data class EpgStatus(val updated: Long, val programmes: Int, val channels: Int, val error: String? = null)

/** Choices for "Update interval" in Settings → TV guide; 0 = together with the playlists. */
val GUIDE_REFRESH_CHOICES = listOf(0, 6, 12, 24, 48)

/** Choices for "Keep past programmes" (hours). */
val GUIDE_PAST_CHOICES = listOf(6, 24, 48, 72, 168)

/** What the last Back press does (TV → guide → groups → this). */
enum class BackAction(val label: String) {
    ASK_EXIT("Press Back twice to exit"),
    OPEN_GUIDE("Never exit (set-top box)"),
    EXIT("Exit right away"),
}

/** Network settings that help when an ISP blocks or throttles IPTV. */
data class NetworkSettings(
    /** system, cloudflare, google, quad9, adguard, custom */
    val dns: String = "system",
    val dnsCustomUrl: String = "",
    /** none, http, socks5 */
    val proxyType: String = "none",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUser: String = "",
    val proxyPass: String = "",
    val vpnEnabled: Boolean = false,
    /** WireGuard .conf text. */
    val vpnConfig: String = "",
    /** false = only Channelbox uses the VPN; true = the whole box. */
    val vpnAllApps: Boolean = false,
    /** Empty = Channelbox's default. */
    val userAgent: String = "",
)

/** Where recordings are saved. */
data class StorageSettings(
    /** local, removable, smb, webdav */
    val type: String = "local",
    val removableIndex: Int = 0,
    val smbHost: String = "",
    val smbShare: String = "",
    val smbPath: String = "Channelbox",
    val smbUser: String = "",
    val smbPass: String = "",
    val smbDomain: String = "",
    val davUrl: String = "",
    val davUser: String = "",
    val davPass: String = "",
)

/** Choices for automatic reloading; 0 = off. */
val REFRESH_CHOICES = listOf(6, 12, 24, 48, 0)

fun refreshLabel(hours: Int) = if (hours <= 0) "Off (only when you choose Reload)" else "Every $hours hours"

data class AppSettings(
    /** Every TV service, in lineup order. Enabled ones are merged into one channel list. */
    val playlists: List<Playlist> = emptyList(),
    val startOnBoot: Boolean = false,
    /** Open live TV when the box wakes from sleep. */
    val startOnWake: Boolean = false,
    val invertChannelKeys: Boolean = false,
    /** Turn subtitles / closed captions on when a channel or video starts (off unless chosen). */
    val captionsByDefault: Boolean = false,
    /** App language ("en" = English). */
    val language: String = "en",
    val refreshHours: Int = 12,
    val backAction: BackAction = BackAction.ASK_EXIT,
    val autoUpdateCheck: Boolean = true,
    /** Decode audio with FFmpeg even when the box has its own decoder (fixes silent channels on some boxes). */
    val preferSoftwareAudio: Boolean = false,
    /** Decode video in software instead of the box's hardware decoder. */
    val softwareVideo: Boolean = false,
    /** Dolby Vision: "auto" (when the TV shows it), "on" or "off" (play as HDR10). */
    val dolbyVision: String = "auto",
    /** Playback buffer: none, small, medium, large, xlarge. */
    val bufferSize: String = "medium",
    /** Send Dolby / DTS audio untouched to a receiver or soundbar that supports it. */
    val audioPassthrough: Boolean = true,
    /** Switch the TV's refresh rate to match the video (auto frame rate). */
    val autoFrameRate: Boolean = false,
    /** Picture size: 0 = fit, 1 = zoom (fill, crop edges), 2 = stretch. */
    val videoScale: Int = 0,
    /** Highest resolution to pick automatically (0 = no limit). */
    val maxQuality: Int = -1, // automatic
    /** Keep playing in a small window when you press Home. */
    val pipOnLeave: Boolean = false,
    /** Seconds without a key press before the guide/menus close back to TV; 0 = never. */
    val idleCloseSeconds: Int = 60,
    /** Days of TV guide to keep. Fewer days = faster loading with very big guides. */
    val guideDays: Int = 3,
    /** TV guide sources added in Settings → TV guide, in priority order. */
    val epgSources: List<EpgSource> = emptyList(),
    /** Guide links each playlist brought with it at the last reload (playlist id → links). */
    val playlistGuides: Map<String, List<String>> = emptyMap(),
    /** Last update of each guide link. */
    val epgStatus: Map<String, EpgStatus> = emptyMap(),
    /** Hours between guide-only updates; 0 = update it whenever the playlists reload. */
    val guideRefreshHours: Int = 0,
    /** Update the guide every time the app starts. */
    val guideOnStart: Boolean = false,
    /** Hours of past programmes to keep (catch-up shows them). */
    val guidePastHours: Int = 6,
    val lastGuideRefresh: Long = 0L,
    /** Parental PIN (hashed); null = no PIN. */
    val pinHash: String? = null,
    /** Ask for the PIN before Settings opens. */
    val pinForSettings: Boolean = false,
    val network: NetworkSettings = NetworkSettings(),
    val storage: StorageSettings = StorageSettings(),
    /** Jellyfin library for Movies & TV Shows. */
    val jellyfin: JellyfinSettings = JellyfinSettings(),
    /** Version the user chose "Skip this version" for. */
    val skippedVersion: String? = null,
    val lastChannelId: String? = null,
    /** Start playing the last channel when the app opens (otherwise open on the guide). */
    val playLastOnStart: Boolean = true,
    /** Show channel numbers beside the names in the TV guide. */
    val showChannelNumbers: Boolean = false,
    /** Groups menu: each playlist's groups start folded under its name (off = all shown). */
    val collapsePlaylists: Boolean = false,
    /** "Record series": every upcoming showing of these titles on these channels gets recorded. */
    val seriesRules: List<SeriesRule> = emptyList(),
    val lastRefresh: Long = 0L,
    /** When movies & shows were last downloaded (they're reloaded less often than channels). */
    val lastVodRefresh: Long = 0L,
) {
    /** False until at least one playlist has been added (first-run setup). */
    val hasSource: Boolean get() = playlists.isNotEmpty()
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    companion object {
        /** Id given to the single source carried over from older versions (its channels keep working). */
        const val LEGACY_ID = "main"
    }

    private object Keys {
        val SOURCE = stringPreferencesKey("source_type")
        val M3U_URL = stringPreferencesKey("m3u_url")
        val EPG_URL = stringPreferencesKey("epg_url")
        val XT_SERVER = stringPreferencesKey("xt_server")
        val XT_USER = stringPreferencesKey("xt_user")
        val XT_PASS = stringPreferencesKey("xt_pass")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val START_ON_WAKE = booleanPreferencesKey("start_on_wake")
        val INVERT_KEYS = booleanPreferencesKey("invert_channel_keys")
        val CAPTIONS_DEFAULT = booleanPreferencesKey("captions_by_default")
        val LANGUAGE = stringPreferencesKey("language")
        val LAST_CHANNEL = stringPreferencesKey("last_channel")
        val PLAY_LAST = booleanPreferencesKey("play_last_on_start")
        val SHOW_NUMBERS = booleanPreferencesKey("guide_show_numbers")
        val COLLAPSE_PLAYLISTS = booleanPreferencesKey("menu_collapse_playlists")
        val LAST_REFRESH = longPreferencesKey("last_refresh")
        val LAST_VOD_REFRESH = longPreferencesKey("last_vod_refresh")
        val REFRESH_HOURS = intPreferencesKey("refresh_hours")
        val BACK_ACTION = stringPreferencesKey("back_action")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update_check")
        val SOFTWARE_AUDIO = booleanPreferencesKey("prefer_software_audio")
        val SOFTWARE_VIDEO = booleanPreferencesKey("software_video")
        val DOLBY_VISION = stringPreferencesKey("dolby_vision")
        val BUFFER_SIZE = stringPreferencesKey("buffer_size")
        val PASSTHROUGH = booleanPreferencesKey("audio_passthrough")
        val AFR = booleanPreferencesKey("auto_frame_rate")
        val VIDEO_SCALE = intPreferencesKey("video_scale")
        val MAX_QUALITY = intPreferencesKey("max_quality")
        val PIP = booleanPreferencesKey("pip_on_leave")
        val IDLE_CLOSE = intPreferencesKey("idle_close_seconds")
        val GUIDE_DAYS = intPreferencesKey("guide_days")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SETTINGS = booleanPreferencesKey("pin_settings")
        val DNS = stringPreferencesKey("net_dns")
        val DNS_URL = stringPreferencesKey("net_dns_url")
        val PROXY_TYPE = stringPreferencesKey("net_proxy_type")
        val PROXY_HOST = stringPreferencesKey("net_proxy_host")
        val PROXY_PORT = intPreferencesKey("net_proxy_port")
        val PROXY_USER = stringPreferencesKey("net_proxy_user")
        val PROXY_PASS = stringPreferencesKey("net_proxy_pass")
        val VPN_ON = booleanPreferencesKey("vpn_enabled")
        val VPN_CONFIG = stringPreferencesKey("vpn_config")
        val VPN_ALL = booleanPreferencesKey("vpn_all_apps")
        val USER_AGENT = stringPreferencesKey("net_user_agent")
        val ST_TYPE = stringPreferencesKey("st_type")
        val ST_REMOVABLE = intPreferencesKey("st_removable")
        val SMB_HOST = stringPreferencesKey("smb_host")
        val JF_ON = booleanPreferencesKey("jellyfin_on")
        val JF_SERVER = stringPreferencesKey("jellyfin_server")
        val JF_USER = stringPreferencesKey("jellyfin_user")
        val JF_PASS = stringPreferencesKey("jellyfin_pass")
        val JF_MODE = stringPreferencesKey("jellyfin_mode")
        val SMB_SHARE = stringPreferencesKey("smb_share")
        val SMB_PATH = stringPreferencesKey("smb_path")
        val SMB_USER = stringPreferencesKey("smb_user")
        val SMB_PASS = stringPreferencesKey("smb_pass")
        val SMB_DOMAIN = stringPreferencesKey("smb_domain")
        val DAV_URL = stringPreferencesKey("dav_url")
        val DAV_USER = stringPreferencesKey("dav_user")
        val DAV_PASS = stringPreferencesKey("dav_pass")
        val SKIPPED_VERSION = stringPreferencesKey("skipped_version")
        val PLAYLISTS = stringPreferencesKey("playlists")
        val SERIES_RULES = stringPreferencesKey("series_rules")
        val EPG_SOURCES = stringPreferencesKey("epg_sources")
        val PLAYLIST_GUIDES = stringPreferencesKey("playlist_guides")
        val EPG_STATUS = stringPreferencesKey("epg_status")
        val GUIDE_REFRESH = intPreferencesKey("guide_refresh_hours")
        val GUIDE_ON_START = booleanPreferencesKey("guide_on_start")
        val GUIDE_PAST = intPreferencesKey("guide_past_hours")
        val LAST_GUIDE_REFRESH = longPreferencesKey("last_guide_refresh")
        val LAST_CHANNEL_SNAPSHOT = stringPreferencesKey("last_channel_snapshot")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    /** The playlist list; older versions stored a single source in separate keys. */
    private fun Preferences.playlists(): List<Playlist> {
        this[Keys.PLAYLISTS]?.let { text ->
            return runCatching {
                (Json.parseToJsonElement(text) as JsonArray).mapNotNull { (it as? JsonObject)?.let(Playlist::fromJson) }
            }.getOrDefault(emptyList())
        }
        val type = runCatching { SourceType.valueOf(this[Keys.SOURCE] ?: "NONE") }.getOrDefault(SourceType.NONE)
        if (type == SourceType.NONE) return emptyList()
        return listOf(
            Playlist(
                id = LEGACY_ID, type = type, m3uUrl = this[Keys.M3U_URL].orEmpty(), epgUrl = this[Keys.EPG_URL].orEmpty(),
                xtServer = this[Keys.XT_SERVER].orEmpty(), xtUser = this[Keys.XT_USER].orEmpty(), xtPass = this[Keys.XT_PASS].orEmpty(),
            )
        )
    }

    private fun Preferences.toSettings() = AppSettings(
        playlists = playlists(),
        seriesRules = seriesRules(),
        startOnBoot = this[Keys.START_ON_BOOT] ?: false,
        startOnWake = this[Keys.START_ON_WAKE] ?: false,
        invertChannelKeys = this[Keys.INVERT_KEYS] ?: false,
        captionsByDefault = this[Keys.CAPTIONS_DEFAULT] ?: false,
        language = this[Keys.LANGUAGE] ?: "en",
        refreshHours = this[Keys.REFRESH_HOURS] ?: 12,
        backAction = runCatching { BackAction.valueOf(this[Keys.BACK_ACTION] ?: "") }
            .getOrDefault(BackAction.ASK_EXIT),
        autoUpdateCheck = this[Keys.AUTO_UPDATE] ?: true,
        preferSoftwareAudio = this[Keys.SOFTWARE_AUDIO] ?: false,
        softwareVideo = this[Keys.SOFTWARE_VIDEO] ?: false,
        dolbyVision = this[Keys.DOLBY_VISION] ?: "auto",
        bufferSize = this[Keys.BUFFER_SIZE] ?: "medium",
        audioPassthrough = this[Keys.PASSTHROUGH] ?: true,
        autoFrameRate = this[Keys.AFR] ?: false,
        videoScale = this[Keys.VIDEO_SCALE] ?: 0,
        maxQuality = this[Keys.MAX_QUALITY] ?: -1,
        pipOnLeave = this[Keys.PIP] ?: false,
        idleCloseSeconds = this[Keys.IDLE_CLOSE] ?: 60,
        guideDays = this[Keys.GUIDE_DAYS] ?: 3,
        epgSources = epgSources(),
        playlistGuides = playlistGuides(),
        epgStatus = epgStatus(),
        guideRefreshHours = this[Keys.GUIDE_REFRESH] ?: 0,
        guideOnStart = this[Keys.GUIDE_ON_START] ?: false,
        guidePastHours = this[Keys.GUIDE_PAST] ?: 6,
        lastGuideRefresh = this[Keys.LAST_GUIDE_REFRESH] ?: 0L,
        pinHash = this[Keys.PIN_HASH],
        pinForSettings = this[Keys.PIN_SETTINGS] ?: false,
        network = NetworkSettings(
            dns = this[Keys.DNS] ?: "system",
            dnsCustomUrl = this[Keys.DNS_URL].orEmpty(),
            proxyType = this[Keys.PROXY_TYPE] ?: "none",
            proxyHost = this[Keys.PROXY_HOST].orEmpty(),
            proxyPort = this[Keys.PROXY_PORT] ?: 0,
            proxyUser = this[Keys.PROXY_USER].orEmpty(),
            proxyPass = this[Keys.PROXY_PASS].orEmpty(),
            vpnEnabled = this[Keys.VPN_ON] ?: false,
            vpnConfig = this[Keys.VPN_CONFIG].orEmpty(),
            vpnAllApps = this[Keys.VPN_ALL] ?: false,
            userAgent = this[Keys.USER_AGENT].orEmpty(),
        ),
        storage = StorageSettings(
            type = this[Keys.ST_TYPE] ?: "local",
            removableIndex = this[Keys.ST_REMOVABLE] ?: 0,
            smbHost = this[Keys.SMB_HOST].orEmpty(),
            smbShare = this[Keys.SMB_SHARE].orEmpty(),
            smbPath = this[Keys.SMB_PATH] ?: "Channelbox",
            smbUser = this[Keys.SMB_USER].orEmpty(),
            smbPass = this[Keys.SMB_PASS].orEmpty(),
            smbDomain = this[Keys.SMB_DOMAIN].orEmpty(),
            davUrl = this[Keys.DAV_URL].orEmpty(),
            davUser = this[Keys.DAV_USER].orEmpty(),
            davPass = this[Keys.DAV_PASS].orEmpty(),
        ),
        jellyfin = JellyfinSettings(
            enabled = this[Keys.JF_ON] ?: false,
            server = this[Keys.JF_SERVER].orEmpty(),
            user = this[Keys.JF_USER].orEmpty(),
            password = this[Keys.JF_PASS].orEmpty(),
            mode = this[Keys.JF_MODE] ?: "add",
        ),
        skippedVersion = this[Keys.SKIPPED_VERSION],
        lastChannelId = this[Keys.LAST_CHANNEL],
        playLastOnStart = this[Keys.PLAY_LAST] ?: true,
        showChannelNumbers = this[Keys.SHOW_NUMBERS] ?: false,
        collapsePlaylists = this[Keys.COLLAPSE_PLAYLISTS] ?: false,
        lastRefresh = this[Keys.LAST_REFRESH] ?: 0L,
        lastVodRefresh = this[Keys.LAST_VOD_REFRESH] ?: 0L,
    )

    private fun Preferences.seriesRules(): List<SeriesRule> = runCatching {
        (Json.parseToJsonElement(this[Keys.SERIES_RULES] ?: return emptyList()) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            SeriesRule(channelId = s("channel"), channelName = s("name"), title = s("title")).takeIf { it.channelId.isNotEmpty() && it.title.isNotEmpty() }
        }
    }.getOrDefault(emptyList())

    /** Adds a "record series" rule (or does nothing if it's there already). */
    suspend fun addSeriesRule(rule: SeriesRule) = editSeriesRules { list -> if (list.none { it.matches(rule) }) list += rule }

    suspend fun removeSeriesRule(rule: SeriesRule) = editSeriesRules { list -> list.removeAll { it.matches(rule) } }

    private suspend fun editSeriesRules(change: (MutableList<SeriesRule>) -> Unit) {
        context.dataStore.edit { prefs ->
            val list = prefs.seriesRules().toMutableList()
            change(list)
            prefs[Keys.SERIES_RULES] = JsonArray(list.map { r ->
                JsonObject(mapOf("channel" to JsonPrimitive(r.channelId), "name" to JsonPrimitive(r.channelName), "title" to JsonPrimitive(r.title)))
            }).toString()
        }
    }

    // ---- TV guide sources -----------------------------------------------------------------------

    private fun Preferences.epgSources(): List<EpgSource> = runCatching {
        (Json.parseToJsonElement(this[Keys.EPG_SOURCES] ?: return emptyList()) as JsonArray)
            .mapNotNull { (it as? JsonObject)?.let(EpgSource::fromJson) }
    }.getOrDefault(emptyList())

    private fun Preferences.playlistGuides(): Map<String, List<String>> = runCatching {
        (Json.parseToJsonElement(this[Keys.PLAYLIST_GUIDES] ?: return emptyMap()) as JsonObject).mapValues { (_, v) ->
            (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        }
    }.getOrDefault(emptyMap())

    private fun Preferences.epgStatus(): Map<String, EpgStatus> = runCatching {
        (Json.parseToJsonElement(this[Keys.EPG_STATUS] ?: return emptyMap()) as JsonObject).mapNotNull { (url, v) ->
            val o = v as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            url to EpgStatus(s("t")?.toLongOrNull() ?: 0L, s("p")?.toIntOrNull() ?: 0, s("c")?.toIntOrNull() ?: 0, s("e"))
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun MutableList<EpgSource>.store(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        prefs[Keys.EPG_SOURCES] = JsonArray(map { it.toJson() }).toString()
    }

    private suspend fun editEpgSources(change: (MutableList<EpgSource>) -> Unit) {
        context.dataStore.edit { prefs ->
            val list = prefs.epgSources().toMutableList()
            change(list)
            list.store(prefs)
        }
    }

    /** Adds the source, or replaces the one with the same id. */
    suspend fun saveEpgSource(src: EpgSource) = editEpgSources { list ->
        val clean = src.copy(name = src.name.trim(), url = src.url.trim(), shiftMinutes = src.shiftMinutes.coerceIn(-24 * 60, 24 * 60))
        val i = list.indexOfFirst { it.id == src.id }
        if (i >= 0) list[i] = clean else list += clean
    }

    suspend fun removeEpgSource(id: String) = editEpgSources { list -> list.removeAll { it.id == id } }

    /** Moves a source up (-1) or down (+1): earlier sources win when two have the same channel. */
    suspend fun moveEpgSource(id: String, delta: Int) = editEpgSources { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i >= 0 && j in list.indices) list.add(j, list.removeAt(i))
    }

    suspend fun setPlaylistGuides(map: Map<String, List<String>>) = context.dataStore.edit { prefs ->
        prefs[Keys.PLAYLIST_GUIDES] = JsonObject(map.mapValues { (_, v) -> JsonArray(v.map { JsonPrimitive(it) }) }).toString()
    }

    suspend fun setEpgStatus(map: Map<String, EpgStatus>) = context.dataStore.edit { prefs ->
        prefs[Keys.EPG_STATUS] = JsonObject(map.mapValues { (_, st) ->
            JsonObject(buildMap {
                put("t", JsonPrimitive(st.updated)); put("p", JsonPrimitive(st.programmes)); put("c", JsonPrimitive(st.channels))
                st.error?.let { put("e", JsonPrimitive(it)) }
            })
        }).toString()
    }

    suspend fun setGuideRefreshHours(hours: Int) = context.dataStore.edit { it[Keys.GUIDE_REFRESH] = hours }
    suspend fun setGuideOnStart(on: Boolean) = context.dataStore.edit { it[Keys.GUIDE_ON_START] = on }
    suspend fun setGuidePastHours(hours: Int) = context.dataStore.edit { it[Keys.GUIDE_PAST] = hours }
    suspend fun setLastGuideRefresh(time: Long) = context.dataStore.edit { it[Keys.LAST_GUIDE_REFRESH] = time }

    /**
     * Guide links typed into a playlist (older versions, the setup screen, the phone link) become
     * TV guide sources attached to that playlist, so they're all managed in one place.
     */
    suspend fun migrateGuides() {
        if (current().playlists.none { it.epgUrl.isNotBlank() }) return
        context.dataStore.edit { prefs ->
            val playlists = prefs.playlists().toMutableList()
            val sources = prefs.epgSources().toMutableList()
            playlists.forEachIndexed { i, p ->
                if (p.epgUrl.isBlank()) return@forEachIndexed
                splitUrls(p.epgUrl).forEach { url -> attachGuide(sources, url, p) }
                playlists[i] = p.copy(epgUrl = "")
            }
            prefs[Keys.PLAYLISTS] = JsonArray(playlists.map { it.toJson() }).toString()
            sources.store(prefs)
        }
    }

    private fun attachGuide(sources: MutableList<EpgSource>, url: String, p: Playlist) {
        val i = sources.indexOfFirst { it.url.trim().equals(url.trim(), ignoreCase = true) }
        if (i < 0) {
            sources += EpgSource(id = EpgSource.newId(), name = p.name.trim().takeIf { it.isNotEmpty() }?.let { "$it guide" }.orEmpty(), url = url.trim(), playlists = listOf(p.id))
        } else if (sources[i].playlists.isNotEmpty() && p.id !in sources[i].playlists) {
            sources[i] = sources[i].copy(playlists = sources[i].playlists + p.id)
        }
    }

    private suspend fun editPlaylists(change: (MutableList<Playlist>) -> Unit) {
        context.dataStore.edit { prefs ->
            val list = prefs.playlists().toMutableList()
            change(list)
            prefs[Keys.PLAYLISTS] = JsonArray(list.map { it.toJson() }).toString()
            prefs[Keys.LAST_REFRESH] = 0L
        }
    }

    /** Adds the playlist, or replaces the one with the same id. */
    suspend fun savePlaylist(p: Playlist) {
        editPlaylists { list ->
            val clean = p.copy(
                name = p.name.trim(), m3uUrl = p.m3uUrl.trim(), epgUrl = p.epgUrl.trim(),
                xtServer = p.xtServer.trim(), xtUser = p.xtUser.trim(),
            )
            val i = list.indexOfFirst { it.id == p.id }
            if (i >= 0) list[i] = clean else list += clean
        }
        // A guide link typed with the playlist becomes a TV guide source for it.
        migrateGuides()
    }

    /** Changes a playlist's options without reloading its channels. */
    suspend fun updatePlaylist(p: Playlist) = context.dataStore.edit { prefs ->
        val list = prefs.playlists().toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) {
            list[i] = p
            prefs[Keys.PLAYLISTS] = JsonArray(list.map { it.toJson() }).toString()
        }
    }

    suspend fun removePlaylist(id: String) {
        editPlaylists { list -> list.removeAll { it.id == id } }
        // Guides added only for that playlist go with it; shared ones just stop listing it.
        editEpgSources { list ->
            list.removeAll { it.playlists == listOf(id) }
            list.replaceAll { if (id in it.playlists) it.copy(playlists = it.playlists - id) else it }
        }
    }

    suspend fun setPlaylistEnabled(id: String, on: Boolean) = editPlaylists { list ->
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) list[i] = list[i].copy(enabled = on)
    }

    /** Moves a playlist up (-1) or down (+1); order decides channel numbering. */
    suspend fun movePlaylist(id: String, delta: Int) = editPlaylists { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i >= 0 && j in list.indices) list.add(j, list.removeAt(i))
    }

    suspend fun setStartOnBoot(on: Boolean) = context.dataStore.edit { it[Keys.START_ON_BOOT] = on }
    suspend fun setStartOnWake(on: Boolean) = context.dataStore.edit { it[Keys.START_ON_WAKE] = on }
    suspend fun setInvertChannelKeys(on: Boolean) = context.dataStore.edit { it[Keys.INVERT_KEYS] = on }
    suspend fun setCaptionsByDefault(on: Boolean) = context.dataStore.edit { it[Keys.CAPTIONS_DEFAULT] = on }
    suspend fun setLanguage(code: String) = context.dataStore.edit { it[Keys.LANGUAGE] = code }
    suspend fun setLastChannel(id: String) = context.dataStore.edit { it[Keys.LAST_CHANNEL] = id }

    /**
     * The channel playing, saved whole, so the next start can play it straight away – before the
     * channel list is read or the playlist reloaded.
     */
    suspend fun setLastChannel(ch: io.github.axon07.channelbox.data.ChannelEntity) = context.dataStore.edit {
        it[Keys.LAST_CHANNEL] = ch.id
        it[Keys.LAST_CHANNEL_SNAPSHOT] = JsonObject(
            listOfNotNull(
                "id" to JsonPrimitive(ch.id), "number" to JsonPrimitive(ch.number), "name" to JsonPrimitive(ch.name),
                "group" to JsonPrimitive(ch.groupTitle), "url" to JsonPrimitive(ch.streamUrl),
                "catchupDays" to JsonPrimitive(ch.catchupDays),
                ch.logoUrl?.let { v -> "logo" to JsonPrimitive(v) }, ch.epgId?.let { v -> "epg" to JsonPrimitive(v) },
                ch.headers?.let { v -> "headers" to JsonPrimitive(v) }, ch.drmType?.let { v -> "drmType" to JsonPrimitive(v) },
                ch.drmKey?.let { v -> "drmKey" to JsonPrimitive(v) }, ch.mimeHint?.let { v -> "mime" to JsonPrimitive(v) },
                ch.catchupType?.let { v -> "catchupType" to JsonPrimitive(v) },
                ch.catchupSource?.let { v -> "catchupSource" to JsonPrimitive(v) },
                ch.playlistId?.let { v -> "playlist" to JsonPrimitive(v) },
            ).toMap()
        ).toString()
    }

    /** The channel saved by [setLastChannel], or null. */
    suspend fun lastChannel(): io.github.axon07.channelbox.data.ChannelEntity? {
        val text = context.dataStore.data.first()[Keys.LAST_CHANNEL_SNAPSHOT] ?: return null
        return runCatching {
            val o = Json.parseToJsonElement(text) as JsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            io.github.axon07.channelbox.data.ChannelEntity(
                id = s("id")!!, number = s("number")?.toIntOrNull() ?: 0, name = s("name").orEmpty(),
                groupTitle = s("group").orEmpty(), logoUrl = s("logo"), streamUrl = s("url")!!, epgId = s("epg"),
                catchupDays = s("catchupDays")?.toIntOrNull() ?: 0, headers = s("headers"), drmType = s("drmType"),
                drmKey = s("drmKey"), mimeHint = s("mime"), catchupType = s("catchupType"),
                catchupSource = s("catchupSource"), playlistId = s("playlist"),
            )
        }.getOrNull()
    }
    suspend fun setPlayLastOnStart(on: Boolean) = context.dataStore.edit { it[Keys.PLAY_LAST] = on }
    suspend fun setShowChannelNumbers(on: Boolean) = context.dataStore.edit { it[Keys.SHOW_NUMBERS] = on }
    suspend fun setCollapsePlaylists(on: Boolean) = context.dataStore.edit { it[Keys.COLLAPSE_PLAYLISTS] = on }

    /** Every setting as JSON values (for a backup file). */
    suspend fun exportAll(): kotlinx.serialization.json.JsonObject {
        val prefs = context.dataStore.data.first().asMap()
        return kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in prefs) {
                val typed = when (v) {
                    is Boolean -> kotlinx.serialization.json.JsonPrimitive(v) to "b"
                    is Int -> kotlinx.serialization.json.JsonPrimitive(v) to "i"
                    is Long -> kotlinx.serialization.json.JsonPrimitive(v) to "l"
                    is Float -> kotlinx.serialization.json.JsonPrimitive(v) to "f"
                    is Double -> kotlinx.serialization.json.JsonPrimitive(v) to "d"
                    is String -> kotlinx.serialization.json.JsonPrimitive(v) to "s"
                    is Set<*> -> kotlinx.serialization.json.JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it.toString()) }) to "ss"
                    else -> continue
                }
                put(k.name, kotlinx.serialization.json.buildJsonObject {
                    put("t", kotlinx.serialization.json.JsonPrimitive(typed.second))
                    put("v", typed.first)
                })
            }
        }
    }

    /** Replaces every setting with the ones from a backup ([exportAll]'s format). */
    suspend fun importAll(json: kotlinx.serialization.json.JsonObject) = context.dataStore.edit { p ->
        p.clear()
        for ((k, e) in json) {
            val o = e as? kotlinx.serialization.json.JsonObject ?: continue
            val t = (o["t"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
            val v = o["v"] ?: continue
            val prim = v as? kotlinx.serialization.json.JsonPrimitive
            when (t) {
                "b" -> prim?.content?.toBooleanStrictOrNull()?.let { p[booleanPreferencesKey(k)] = it }
                "i" -> prim?.content?.toIntOrNull()?.let { p[androidx.datastore.preferences.core.intPreferencesKey(k)] = it }
                "l" -> prim?.content?.toLongOrNull()?.let { p[androidx.datastore.preferences.core.longPreferencesKey(k)] = it }
                "f" -> prim?.content?.toFloatOrNull()?.let { p[androidx.datastore.preferences.core.floatPreferencesKey(k)] = it }
                "d" -> prim?.content?.toDoubleOrNull()?.let { p[androidx.datastore.preferences.core.doublePreferencesKey(k)] = it }
                "s" -> prim?.content?.let { p[stringPreferencesKey(k)] = it }
                "ss" -> (v as? kotlinx.serialization.json.JsonArray)?.let { arr ->
                    p[androidx.datastore.preferences.core.stringSetPreferencesKey(k)] =
                        arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.toSet()
                }
            }
        }
    }
    suspend fun setRefreshHours(hours: Int) = context.dataStore.edit { it[Keys.REFRESH_HOURS] = hours }
    suspend fun setBackAction(action: BackAction) = context.dataStore.edit { it[Keys.BACK_ACTION] = action.name }
    suspend fun setPreferSoftwareAudio(on: Boolean) = context.dataStore.edit { it[Keys.SOFTWARE_AUDIO] = on }
    suspend fun setSoftwareVideo(on: Boolean) = context.dataStore.edit { it[Keys.SOFTWARE_VIDEO] = on }
    suspend fun setDolbyVision(mode: String) = context.dataStore.edit { it[Keys.DOLBY_VISION] = mode }
    suspend fun setBufferSize(size: String) = context.dataStore.edit { it[Keys.BUFFER_SIZE] = size }
    suspend fun setAudioPassthrough(on: Boolean) = context.dataStore.edit { it[Keys.PASSTHROUGH] = on }
    suspend fun setAutoFrameRate(on: Boolean) = context.dataStore.edit { it[Keys.AFR] = on }
    suspend fun setVideoScale(scale: Int) = context.dataStore.edit { it[Keys.VIDEO_SCALE] = scale }
    suspend fun setMaxQuality(height: Int) = context.dataStore.edit { it[Keys.MAX_QUALITY] = height }
    suspend fun setPipOnLeave(on: Boolean) = context.dataStore.edit { it[Keys.PIP] = on }
    suspend fun setIdleClose(seconds: Int) = context.dataStore.edit { it[Keys.IDLE_CLOSE] = seconds }
    suspend fun setGuideDays(days: Int) = context.dataStore.edit { it[Keys.GUIDE_DAYS] = days }
    suspend fun setPinHash(hash: String?) = context.dataStore.edit {
        if (hash == null) {
            it.remove(Keys.PIN_HASH)
            it[Keys.PIN_SETTINGS] = false
        } else it[Keys.PIN_HASH] = hash
    }
    suspend fun setPinForSettings(on: Boolean) = context.dataStore.edit { it[Keys.PIN_SETTINGS] = on }

    suspend fun saveNetwork(n: NetworkSettings) = context.dataStore.edit {
        it[Keys.DNS] = n.dns
        it[Keys.DNS_URL] = n.dnsCustomUrl.trim()
        it[Keys.PROXY_TYPE] = n.proxyType
        it[Keys.PROXY_HOST] = n.proxyHost.trim()
        it[Keys.PROXY_PORT] = n.proxyPort
        it[Keys.PROXY_USER] = n.proxyUser
        it[Keys.PROXY_PASS] = n.proxyPass
        it[Keys.VPN_ON] = n.vpnEnabled
        it[Keys.VPN_CONFIG] = n.vpnConfig
        it[Keys.VPN_ALL] = n.vpnAllApps
        it[Keys.USER_AGENT] = n.userAgent.trim()
    }

    suspend fun saveJellyfin(j: JellyfinSettings) = context.dataStore.edit {
        it[Keys.JF_ON] = j.enabled
        it[Keys.JF_SERVER] = j.server.trim()
        it[Keys.JF_USER] = j.user.trim()
        it[Keys.JF_PASS] = j.password
        it[Keys.JF_MODE] = j.mode
    }

    suspend fun saveStorage(st: StorageSettings) = context.dataStore.edit {
        it[Keys.ST_TYPE] = st.type
        it[Keys.ST_REMOVABLE] = st.removableIndex
        it[Keys.SMB_HOST] = st.smbHost.trim()
        it[Keys.SMB_SHARE] = st.smbShare.trim().trim('/', '\\')
        it[Keys.SMB_PATH] = st.smbPath.trim().trim('/', '\\')
        it[Keys.SMB_USER] = st.smbUser.trim()
        it[Keys.SMB_PASS] = st.smbPass
        it[Keys.SMB_DOMAIN] = st.smbDomain.trim()
        it[Keys.DAV_URL] = st.davUrl.trim().trimEnd('/')
        it[Keys.DAV_USER] = st.davUser.trim()
        it[Keys.DAV_PASS] = st.davPass
    }

    suspend fun setAutoUpdateCheck(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_UPDATE] = on }
    suspend fun setSkippedVersion(version: String) = context.dataStore.edit { it[Keys.SKIPPED_VERSION] = version }
    suspend fun setLastRefresh(time: Long) = context.dataStore.edit { it[Keys.LAST_REFRESH] = time }
    suspend fun setLastVodRefresh(time: Long) = context.dataStore.edit { it[Keys.LAST_VOD_REFRESH] = time }
}

/** "Record series": record every showing of [title] on one channel, as the guide lists them. */
data class SeriesRule(val channelId: String, val channelName: String, val title: String) {
    fun matches(other: SeriesRule) = channelId == other.channelId && title.equals(other.title, ignoreCase = true)
    fun matches(channelId: String, title: String) = this.channelId == channelId && this.title.equals(title.trim(), ignoreCase = true)
}
