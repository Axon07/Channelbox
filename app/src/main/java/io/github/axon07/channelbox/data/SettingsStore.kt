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
    suspend fun setAutoUpdateCheck(on: Boolean) = context.dataStore.edit { it[Keys.AUTO_UPDATE] = on }
    suspend fun setSkippedVersion(version: String) = context.dataStore.edit { it[Keys.SKIPPED_VERSION] = version }
    suspend fun setLastRefresh(time: Long) = context.dataStore.edit { it[Keys.LAST_REFRESH] = time }
}
