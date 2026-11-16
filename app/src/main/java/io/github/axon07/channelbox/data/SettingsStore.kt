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

enum class SourceType { NONE, M3U, XTREAM }

/** What the last Back press does (TV → guide → groups → this). */
enum class BackAction(val label: String) {
    ASK_EXIT("Ask before exiting"),
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
    val sourceType: SourceType = SourceType.NONE,
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val xtServer: String = "",
    val xtUser: String = "",
    val xtPass: String = "",
    val startOnBoot: Boolean = false,
    val invertChannelKeys: Boolean = false,
    val refreshHours: Int = 12,
    val backAction: BackAction = BackAction.ASK_EXIT,
    val autoUpdateCheck: Boolean = true,
    /** Decode audio with FFmpeg even when the box has its own decoder (fixes silent channels on some boxes). */
    val preferSoftwareAudio: Boolean = false,
    /** Picture size: 0 = fit, 1 = zoom (fill, crop edges), 2 = stretch. */
    val videoScale: Int = 0,
    /** Highest resolution to pick automatically (0 = no limit). */
    val maxQuality: Int = 0,
    /** Keep playing in a small window when you press Home. */
    val pipOnLeave: Boolean = false,
    val network: NetworkSettings = NetworkSettings(),
    val storage: StorageSettings = StorageSettings(),
    /** Version the user chose "Skip this version" for. */
    val skippedVersion: String? = null,
    val lastChannelId: String? = null,
    val lastRefresh: Long = 0L,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val SOURCE = stringPreferencesKey("source_type")
        val M3U_URL = stringPreferencesKey("m3u_url")
        val EPG_URL = stringPreferencesKey("epg_url")
        val XT_SERVER = stringPreferencesKey("xt_server")
        val XT_USER = stringPreferencesKey("xt_user")
        val XT_PASS = stringPreferencesKey("xt_pass")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val INVERT_KEYS = booleanPreferencesKey("invert_channel_keys")
        val LAST_CHANNEL = stringPreferencesKey("last_channel")
        val LAST_REFRESH = longPreferencesKey("last_refresh")
        val REFRESH_HOURS = intPreferencesKey("refresh_hours")
        val BACK_ACTION = stringPreferencesKey("back_action")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update_check")
        val SOFTWARE_AUDIO = booleanPreferencesKey("prefer_software_audio")
        val VIDEO_SCALE = intPreferencesKey("video_scale")
        val MAX_QUALITY = intPreferencesKey("max_quality")
        val PIP = booleanPreferencesKey("pip_on_leave")
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
        val SMB_SHARE = stringPreferencesKey("smb_share")
        val SMB_PATH = stringPreferencesKey("smb_path")
        val SMB_USER = stringPreferencesKey("smb_user")
        val SMB_PASS = stringPreferencesKey("smb_pass")
        val SMB_DOMAIN = stringPreferencesKey("smb_domain")
        val DAV_URL = stringPreferencesKey("dav_url")
        val DAV_USER = stringPreferencesKey("dav_user")
        val DAV_PASS = stringPreferencesKey("dav_pass")
        val SKIPPED_VERSION = stringPreferencesKey("skipped_version")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    private fun Preferences.toSettings() = AppSettings(
        sourceType = runCatching { SourceType.valueOf(this[Keys.SOURCE] ?: "NONE") }
            .getOrDefault(SourceType.NONE),
        m3uUrl = this[Keys.M3U_URL].orEmpty(),
        epgUrl = this[Keys.EPG_URL].orEmpty(),
        xtServer = this[Keys.XT_SERVER].orEmpty(),
        xtUser = this[Keys.XT_USER].orEmpty(),
        xtPass = this[Keys.XT_PASS].orEmpty(),
        startOnBoot = this[Keys.START_ON_BOOT] ?: false,
        invertChannelKeys = this[Keys.INVERT_KEYS] ?: false,
        refreshHours = this[Keys.REFRESH_HOURS] ?: 12,
        backAction = runCatching { BackAction.valueOf(this[Keys.BACK_ACTION] ?: "") }
            .getOrDefault(BackAction.ASK_EXIT),
        autoUpdateCheck = this[Keys.AUTO_UPDATE] ?: true,
        preferSoftwareAudio = this[Keys.SOFTWARE_AUDIO] ?: false,
        videoScale = this[Keys.VIDEO_SCALE] ?: 0,
        maxQuality = this[Keys.MAX_QUALITY] ?: 0,
        pipOnLeave = this[Keys.PIP] ?: false,
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
        skippedVersion = this[Keys.SKIPPED_VERSION],
        lastChannelId = this[Keys.LAST_CHANNEL],
        lastRefresh = this[Keys.LAST_REFRESH] ?: 0L,
    )

    suspend fun saveM3u(url: String, epgUrl: String) {
        context.dataStore.edit {
            it[Keys.SOURCE] = SourceType.M3U.name
            it[Keys.M3U_URL] = url.trim()
            it[Keys.EPG_URL] = epgUrl.trim()
            it[Keys.LAST_REFRESH] = 0L
        }
    }

    suspend fun saveXtream(server: String, user: String, pass: String, epgUrl: String) {
        context.dataStore.edit {
            it[Keys.SOURCE] = SourceType.XTREAM.name
            it[Keys.XT_SERVER] = server.trim()
            it[Keys.XT_USER] = user.trim()
            it[Keys.XT_PASS] = pass
            it[Keys.EPG_URL] = epgUrl.trim()
            it[Keys.LAST_REFRESH] = 0L
        }
    }

    suspend fun setStartOnBoot(on: Boolean) = context.dataStore.edit { it[Keys.START_ON_BOOT] = on }
    suspend fun setInvertChannelKeys(on: Boolean) = context.dataStore.edit { it[Keys.INVERT_KEYS] = on }
    suspend fun setLastChannel(id: String) = context.dataStore.edit { it[Keys.LAST_CHANNEL] = id }
    suspend fun setRefreshHours(hours: Int) = context.dataStore.edit { it[Keys.REFRESH_HOURS] = hours }
    suspend fun setBackAction(action: BackAction) = context.dataStore.edit { it[Keys.BACK_ACTION] = action.name }
    suspend fun setPreferSoftwareAudio(on: Boolean) = context.dataStore.edit { it[Keys.SOFTWARE_AUDIO] = on }
    suspend fun setVideoScale(scale: Int) = context.dataStore.edit { it[Keys.VIDEO_SCALE] = scale }
    suspend fun setMaxQuality(height: Int) = context.dataStore.edit { it[Keys.MAX_QUALITY] = height }
    suspend fun setPipOnLeave(on: Boolean) = context.dataStore.edit { it[Keys.PIP] = on }

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
}
