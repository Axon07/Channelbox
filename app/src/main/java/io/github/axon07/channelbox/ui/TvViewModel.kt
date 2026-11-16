package io.github.axon07.channelbox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.axon07.channelbox.container
import io.github.axon07.channelbox.data.AppSettings
import io.github.axon07.channelbox.data.BackAction
import io.github.axon07.channelbox.data.REFRESH_CHOICES
import io.github.axon07.channelbox.system.RefreshWorker
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.ProgramEntity
import io.github.axon07.channelbox.data.RecStatus
import io.github.axon07.channelbox.data.RecordingEntity
import io.github.axon07.channelbox.data.ReleaseInfo
import io.github.axon07.channelbox.data.StreamSpecs
import io.github.axon07.channelbox.data.RefreshState
import io.github.axon07.channelbox.data.Playlist
import io.github.axon07.channelbox.data.TvRepository.Companion.HOUR
import io.github.axon07.channelbox.player.TvPlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.github.axon07.channelbox.data.NetworkSettings
import io.github.axon07.channelbox.data.StorageSettings
import io.github.axon07.channelbox.data.UserAgent
import io.github.axon07.channelbox.data.download
import io.github.axon07.channelbox.system.VpnManager
import io.github.axon07.channelbox.data.Catchup
import io.github.axon07.channelbox.data.StreamSpec

sealed interface ChannelFilter {
    val label: String

    data object All : ChannelFilter {
        override val label = "All channels"
    }

    data object Favorites : ChannelFilter {
        override val label = "Favourites"
    }

    /** Every channel from one playlist (shown when more than one is in use). */
    data class Source(val id: String, val name: String) : ChannelFilter {
        override val label: String get() = name
    }

    data class Group(val name: String) : ChannelFilter {
        override val label: String get() = if (name == UNGROUPED) "Other channels" else name
    }

    companion object {
        /** Group name the playlist parsers give channels without a group. */
        const val UNGROUPED = "Uncategorized"
    }
}

/** How much of the timeline the guide shows at once. */
const val GUIDE_SPAN = 2 * HOUR
const val GUIDE_STEP = HOUR / 2

@OptIn(ExperimentalCoroutinesApi::class)
class TvViewModel(app: Application) : AndroidViewModel(app) {

    private val c = app.container
    private val repo = c.repository
    // The audio decoder choice is read once at start (changing it takes effect after a restart).
    private val softwareAudio = runBlocking { c.settings.current().preferSoftwareAudio }
    val player = TvPlayer(app, c.http, softwareAudio)

    /** Extra players for multi-view (released when multi-view closes). */
    fun newTilePlayer(): TvPlayer = TvPlayer(getApplication(), c.http, softwareAudio).also {
        it.storageSettings = { settings.value?.storage ?: StorageSettings() }
    }

    /** True while the app is shrunk to a picture-in-picture window. */
    val inPip = MutableStateFlow(false)

    val settings: StateFlow<AppSettings?> = c.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val refreshState: StateFlow<RefreshState> = repo.state

    val allChannels: StateFlow<List<ChannelEntity>> = repo.channels
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favorites: StateFlow<Set<String>> = repo.favorites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _filter = MutableStateFlow<ChannelFilter>(ChannelFilter.All)
    val filter: StateFlow<ChannelFilter> = _filter.asStateFlow()

    val filters: StateFlow<List<ChannelFilter>> = combine(allChannels, c.settings.flow) { list, s ->
        // Playlist order, with channels that have no group collected at the very end.
        val groups = list.map { it.groupTitle }.distinct()
        val used = list.mapNotNull { it.playlistId }.toSet()
        val sources = if (used.size > 1) {
            s.playlists.filter { it.id in used }.map { ChannelFilter.Source(it.id, it.displayName) }
        } else emptyList()
        listOf(ChannelFilter.All, ChannelFilter.Favorites) + sources +
            (groups.filter { it != ChannelFilter.UNGROUPED } + groups.filter { it == ChannelFilter.UNGROUPED })
                .map { ChannelFilter.Group(it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, listOf(ChannelFilter.All, ChannelFilter.Favorites))

    /** Number of channels in each group, for the groups menu. */
    val filterCounts: StateFlow<Map<ChannelFilter, Int>> = combine(filters, allChannels, favorites) { fs, list, favs ->
        val map = HashMap<ChannelFilter, Int>()
        map[ChannelFilter.All] = list.size
        map[ChannelFilter.Favorites] = list.count { it.id in favs }
        list.groupingBy { it.groupTitle }.eachCount().forEach { (g, n) -> map[ChannelFilter.Group(g)] = n }
        val perSource = list.groupingBy { it.playlistId }.eachCount()
        fs.filterIsInstance<ChannelFilter.Source>().forEach { map[it] = perSource[it.id] ?: 0 }
        map
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Channels per playlist id, for Settings. */
    val playlistCounts: StateFlow<Map<String?, Int>> = allChannels
        .map { list -> list.groupingBy { it.playlistId }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Where recordings are saved, and free space there. */
    val recordingsFolder: java.io.File get() = c.recorder.directory()

    val visibleChannels: StateFlow<List<ChannelEntity>> =
        combine(allChannels, favorites, _filter) { list, favs, f ->
            when (f) {
                ChannelFilter.All -> list
                ChannelFilter.Favorites -> list.filter { it.id in favs }
                is ChannelFilter.Group -> list.filter { it.groupTitle == f.name }
                is ChannelFilter.Source -> list.filter { (it.playlistId ?: io.github.axon07.channelbox.data.SettingsStore.LEGACY_ID) == f.id }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Wall clock, ticking every 15 s – drives progress bars and the "now" line. */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(15_000)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, System.currentTimeMillis())

    /** Programmes from now until +6 h, keyed by epg id. Used by the banner and channel list. */
    val upcoming: StateFlow<Map<String, List<ProgramEntity>>> = now
        .map { it / (5 * 60_000L) }
        .distinctUntilChanged()
        .flatMapLatest {
            val t = System.currentTimeMillis()
            repo.programs(t, t + 6 * HOUR)
        }
        .map { list -> list.groupBy { it.epgId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _guideStart = MutableStateFlow(roundDown(System.currentTimeMillis()))
    val guideStart: StateFlow<Long> = _guideStart.asStateFlow()

    val guidePrograms: StateFlow<Map<String, List<ProgramEntity>>> = _guideStart
        .flatMapLatest { start -> repo.programs(start, start + GUIDE_SPAN) }
        .map { list -> list.groupBy { it.epgId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _current = MutableStateFlow<ChannelEntity?>(null)
    val current: StateFlow<ChannelEntity?> = _current.asStateFlow()
    private var previous: ChannelEntity? = null

    /** Bumped on every channel change so the UI can flash the info banner. */
    private val _bannerToken = MutableStateFlow(0)
    val bannerToken: StateFlow<Int> = _bannerToken.asStateFlow()

    /** Short on-screen message ("Recording started…"). */
    data class Toast(val id: Int, val text: String)
    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast.asStateFlow()
    private var toastId = 0

    fun showToast(text: String) {
        _toast.value = Toast(++toastId, text)
    }

    fun clearToast(id: Int) {
        if (_toast.value?.id == id) _toast.value = null
    }

    // ---- Updates --------------------------------------------------------------------------

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data object UpToDate : UpdateState
        data class Available(val release: ReleaseInfo) : UpdateState
        data class Downloading(val release: ReleaseInfo, val percent: Int) : UpdateState
        data class Failed(val message: String) : UpdateState
    }

    private val updater = c.updater
    val appVersion: String get() = updater.currentVersion
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /** True when the update dialog should be on screen. */
    private val _updatePrompt = MutableStateFlow(false)
    val updatePrompt: StateFlow<Boolean> = _updatePrompt.asStateFlow()

    /**
     * [manual] = the user pressed "Check for updates": always report the result.
     * Automatic checks stay silent unless there's a new version they haven't skipped.
     */
    fun checkForUpdates(manual: Boolean) {
        if (_update.value is UpdateState.Checking || _update.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _update.value = UpdateState.Checking
            val result = runCatching { updater.latest() }
            val release = result.getOrNull()
            _update.value = when {
                result.isFailure -> UpdateState.Failed(result.exceptionOrNull()?.message ?: "Couldn't reach GitHub")
                release != null && updater.isNewer(release) -> UpdateState.Available(release)
                else -> UpdateState.UpToDate
            }
            val state = _update.value
            if (state is UpdateState.Available) {
                val skipped = c.settings.current().skippedVersion
                if (manual) _updatePrompt.value = false
                _updatePrompt.value = manual || state.release.version != skipped
            } else if (!manual) {
                _update.value = UpdateState.Idle
            }
        }
    }

    fun startUpdate() {
        val release = when (val s = _update.value) {
            is UpdateState.Available -> s.release
            else -> return
        }
        if (!updater.canInstall()) {
            // Android 8+: the user has to allow Channelbox to install apps (once).
            // When they come back from that screen, resumePendingUpdate() carries on by itself.
            if (updater.openInstallPermission()) {
                pendingInstall = true
                showToast("Turn on \"Allow\" for Channelbox, then press Back")
            } else {
                showToast("Allow \"Install unknown apps\" for Channelbox in Android settings")
            }
            return
        }
        viewModelScope.launch {
            _update.value = UpdateState.Downloading(release, 0)
            try {
                val apk = updater.download(release) { percent ->
                    _update.value = UpdateState.Downloading(release, percent)
                }
                _update.value = UpdateState.Available(release)
                _updatePrompt.value = false
                updater.install(apk)
            } catch (e: Exception) {
                _update.value = UpdateState.Failed(e.message ?: "Download failed")
            }
        }
    }

    /** Set while the user is on Android's "install unknown apps" screen. */
    private var pendingInstall = false

    /** Called when the app comes back to the front: finish an update that was waiting on permission. */
    fun resumePendingUpdate() {
        if (!pendingInstall) return
        pendingInstall = false
        if (updater.canInstall()) startUpdate()
        else showToast("Channelbox still isn't allowed to install apps")
    }

    fun dismissUpdate(skipThisVersion: Boolean) {
        _updatePrompt.value = false
        val s = _update.value
        if (skipThisVersion && s is UpdateState.Available) {
            viewModelScope.launch { c.settings.setSkippedVersion(s.release.version) }
        }
    }

    fun setAutoUpdateCheck(on: Boolean) = viewModelScope.launch { c.settings.setAutoUpdateCheck(on) }

    // ---- Recordings -----------------------------------------------------------------------

    private val recorder = c.recorder

    val recordings: StateFlow<List<RecordingEntity>> = recorder.recordings
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** "channelId@programStart" for every scheduled/running recording – marks programmes in the guide. */
    val recordingKeys: StateFlow<Set<String>> = recordings
        .map { list ->
            list.filter { it.status in RecStatus.ACTIVE }
                .mapNotNull { r -> r.programStart?.let { recordingKey(r.channelId, it) } }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Channels being recorded right now. */
    val recordingChannels: StateFlow<Set<String>> = recordings
        .map { list -> list.filter { it.status == RecStatus.RECORDING }.map { it.channelId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Record (or stop recording) what's on the current channel now. */
    fun toggleRecordCurrent() {
        val ch = _current.value ?: return
        val running = recordings.value.firstOrNull { it.channelId == ch.id && it.status == RecStatus.RECORDING }
        if (running != null) {
            stopRecording(running)
            return
        }
        val now = System.currentTimeMillis()
        val program = nowAndNext(ch.epgId?.let { upcoming.value[it] }, now).first
        viewModelScope.launch {
            val title = program?.title ?: ch.name
            recorder.schedule(ch, title, now, program?.stop?.plus(2 * 60_000L) ?: (now + 2 * HOUR), program?.start)
            showToast("● Recording: $title")
        }
    }

    /** Record a programme from the guide, or cancel it if it's already scheduled. */
    fun toggleRecordProgram(channel: ChannelEntity, program: ProgramEntity) {
        val existing = recordings.value.firstOrNull {
            it.channelId == channel.id && it.programStart == program.start && it.status in RecStatus.ACTIVE
        }
        if (existing != null) {
            stopRecording(existing)
            return
        }
        val now = System.currentTimeMillis()
        if (program.stop <= now) {
            showToast("That programme has already finished")
            return
        }
        viewModelScope.launch {
            recorder.schedule(
                channel, program.title,
                start = maxOf(now, program.start - 60_000L),
                stop = program.stop + 2 * 60_000L,
                programStart = program.start,
            )
            showToast(if (program.start > now) "Recording scheduled: ${program.title}" else "● Recording: ${program.title}")
        }
    }

    fun stopRecording(recording: RecordingEntity) {
        viewModelScope.launch {
            recorder.stop(recording)
            showToast(if (recording.status == RecStatus.SCHEDULED) "Recording cancelled" else "Recording stopped")
        }
    }

    fun deleteRecording(recording: RecordingEntity) {
        viewModelScope.launch {
            recorder.delete(recording)
            showToast("Recording deleted")
        }
    }

    fun playRecording(recording: RecordingEntity) {
        val path = recording.filePath ?: return
        val st = settings.value?.storage ?: StorageSettings()
        when {
            path.startsWith("/") -> player.playFile(path)
            path.startsWith("http") -> player.playOnDemand(
                StreamSpec(path, mapOf("Authorization" to okhttp3.Credentials.basic(st.davUser, st.davPass)), null, null)
            )
            else -> player.playOnDemand(StreamSpec(path, emptyMap(), null, null)) // smb://
        }
    }

    /** True if [program] on [channel] can be watched from the archive (or from its start, if it's on now). */
    fun canCatchUp(channel: ChannelEntity, program: ProgramEntity, now: Long = System.currentTimeMillis()): Boolean =
        Catchup.isAvailable(channel.catchupType, channel.catchupSource, channel.catchupDays) &&
            program.start < now && program.start >= now - channel.catchupDays * 24 * HOUR

    /** Plays a past (or current, from the start) programme from the provider's archive. */
    fun playCatchUp(channel: ChannelEntity, program: ProgramEntity): Boolean {
        val live = StreamSpecs.from(channel)
        val url = Catchup.buildUrl(channel.streamUrl, channel.catchupType, channel.catchupSource, program.start, program.stop)
            ?: return false
        val archive = StreamSpecs.parse(url, live.headers, channel.drmType, channel.drmKey, null)
        player.playOnDemand(StreamSpec(archive.url, archive.headers, archive.drm, archive.mime))
        return true
    }

    fun setPreferSoftwareAudio(on: Boolean) = viewModelScope.launch {
        c.settings.setPreferSoftwareAudio(on)
        showToast("Takes effect the next time Channelbox starts")
    }

    fun setVideoScale(scale: Int) = viewModelScope.launch { c.settings.setVideoScale(scale) }
    fun setMaxQuality(height: Int) = viewModelScope.launch { c.settings.setMaxQuality(height) }
    fun setPipOnLeave(on: Boolean) = viewModelScope.launch { c.settings.setPipOnLeave(on) }

    // ---- Network: VPN, proxy, DNS, user agent ----------------------------------------------

    val vpnState = VpnManager.state

    /** Proxy and DNS are built into the HTTP client at start-up, so changing them needs a restart. */
    fun networkNeedsRestart(now: NetworkSettings): Boolean {
        val start = c.startupNetwork
        return now.dns != start.dns || now.dnsCustomUrl != start.dnsCustomUrl ||
            now.proxyType != start.proxyType || now.proxyHost != start.proxyHost || now.proxyPort != start.proxyPort ||
            now.proxyUser != start.proxyUser || now.proxyPass != start.proxyPass
    }

    fun saveNetwork(n: NetworkSettings) = viewModelScope.launch { c.settings.saveNetwork(n) }

    /** Call after Android's VPN permission has been granted (or if it already was). */
    fun setVpnEnabled(on: Boolean) = viewModelScope.launch {
        val n = c.settings.current().network
        if (on && n.vpnConfig.isBlank()) {
            showToast("Add a WireGuard config first")
            return@launch
        }
        c.settings.saveNetwork(n.copy(vpnEnabled = on))
        if (on) {
            if (VpnManager.connect(getApplication(), n.vpnConfig, n.vpnAllApps)) {
                showToast("VPN connected")
                _current.value?.let { player.play(StreamSpecs.from(it)) } // reconnect through the tunnel
            }
        } else {
            VpnManager.disconnect()
            showToast("VPN off")
        }
    }

    /** Saves a WireGuard config (and reconnects if the VPN is on). Returns an error message, or null. */
    fun saveVpnConfig(config: String, allApps: Boolean): String? {
        VpnManager.validate(config)?.let { return it }
        viewModelScope.launch {
            val n = c.settings.current().network.copy(vpnConfig = config.trim(), vpnAllApps = allApps)
            c.settings.saveNetwork(n)
            if (n.vpnEnabled) {
                VpnManager.disconnect()
                VpnManager.connect(getApplication(), n.vpnConfig, n.vpnAllApps)
            }
        }
        return null
    }

    /** Downloads a WireGuard config from a link (e.g. one you uploaded or your provider's). */
    suspend fun fetchText(url: String): String = c.http.download(url) { it.bufferedReader().readText() }

    // ---- Recording storage ------------------------------------------------------------------

    fun saveStorage(st: StorageSettings) = viewModelScope.launch { c.settings.saveStorage(st) }

    suspend fun testStorage(st: StorageSettings): String? = c.storage.test(st)

    suspend fun describeStorage(st: StorageSettings): String = c.storage.describe(st)

    fun removableDrives(): Int = c.storage.removableDirectories().size

    // ---- Device info for Settings → About --------------------------------------------------

    fun widevineLevel(): String = runCatching {
        val drm = android.media.MediaDrm(androidx.media3.common.C.WIDEVINE_UUID)
        try {
            drm.getPropertyString("securityLevel")
        } finally {
            if (android.os.Build.VERSION.SDK_INT >= 28) drm.close() else @Suppress("DEPRECATION") drm.release()
        }
    }.getOrDefault("not available")

    /** Back to live TV after watching a recording. */
    fun resumeLive() {
        _current.value?.let { player.play(StreamSpecs.from(it)) }
    }

    init {
        player.storageSettings = { settings.value?.storage ?: StorageSettings() }
        // Settings that apply immediately.
        viewModelScope.launch {
            c.settings.flow.collect { s ->
                UserAgent.apply(s.network.userAgent)
                player.setMaxQuality(s.maxQuality)
            }
        }
        // Startup update check, a few seconds in so it doesn't slow down tuning the first channel.
        viewModelScope.launch {
            delay(8_000)
            if (c.settings.current().autoUpdateCheck) checkForUpdates(manual = false)
        }
        viewModelScope.launch {
            val s = c.settings.current()
            // Bring the VPN up first so the playlist and streams go through it.
            if (s.network.vpnEnabled && s.network.vpnConfig.isNotBlank()) {
                if (VpnManager.permissionIntent(app) == null) {
                    if (!VpnManager.connect(app, s.network.vpnConfig, s.network.vpnAllApps)) {
                        showToast("VPN didn't connect – check Settings → Network")
                    }
                } else {
                    showToast("Allow the VPN in Settings → Network")
                }
            }
            if (!s.hasSource) return@launch
            val hasChannels = repo.allChannels().isNotEmpty()
            if (hasChannels) ensurePlaying()
            val stale = s.refreshHours > 0 && System.currentTimeMillis() - s.lastRefresh > s.refreshHours * HOUR
            if (!hasChannels || stale) {
                if (repo.refresh()) ensurePlaying()
            }
        }
    }

    // ---- Playback -------------------------------------------------------------------------

    fun tune(channel: ChannelEntity) {
        if (_current.value?.id != channel.id) {
            previous = _current.value
            _current.value = channel
            player.play(StreamSpecs.from(channel))
            viewModelScope.launch { c.settings.setLastChannel(channel.id) }
        } else if (!player.isLive) {
            player.play(StreamSpecs.from(channel))
        }
        showBanner()
    }

    fun showBanner() {
        _bannerToken.value++
    }

    /** Channel up/down within the current group, wrapping around. */
    fun zap(delta: Int) {
        val visible = visibleChannels.value
        val list = if (visible.any { it.id == _current.value?.id }) visible else allChannels.value
        if (list.isEmpty()) return
        val index = list.indexOfFirst { it.id == _current.value?.id }
        val next = if (index < 0) 0 else (index + delta).mod(list.size)
        tune(list[next])
    }

    fun tuneNumber(number: Int): Boolean {
        val ch = allChannels.value.firstOrNull { it.number == number } ?: return false
        tune(ch)
        return true
    }

    fun previousChannel() {
        val p = previous ?: return
        allChannels.value.firstOrNull { it.id == p.id }?.let { tune(it) }
    }

    suspend fun ensurePlaying() {
        if (_current.value != null) return
        val list = repo.allChannels()
        if (list.isEmpty()) return
        val last = c.settings.current().lastChannelId
        tune(list.firstOrNull { it.id == last } ?: list.first())
    }

    fun onForeground() = player.resume()
    fun onBackground() = player.stop()

    override fun onCleared() {
        player.release()
    }

    // ---- Lists, guide, favourites ---------------------------------------------------------

    fun setFilter(filter: ChannelFilter) {
        _filter.value = filter
    }

    fun cycleFilter(delta: Int) {
        val list = filters.value
        val index = list.indexOf(_filter.value).coerceAtLeast(0)
        _filter.value = list[(index + delta).mod(list.size)]
    }

    fun toggleFavorite(channel: ChannelEntity) {
        val fav = channel.id !in favorites.value
        viewModelScope.launch { repo.setFavorite(channel.id, fav) }
    }

    fun setGuideStart(time: Long) {
        _guideStart.value = roundDown(time)
    }

    // ---- Settings -------------------------------------------------------------------------

    fun refreshNow() {
        viewModelScope.launch { if (repo.refresh()) ensurePlaying() }
    }

    fun savePlaylist(p: Playlist) = viewModelScope.launch {
        c.settings.savePlaylist(p)
        reloadAfterSourceChange()
    }

    fun removePlaylist(id: String) = viewModelScope.launch {
        c.settings.removePlaylist(id)
        reloadAfterSourceChange()
    }

    fun setPlaylistEnabled(id: String, on: Boolean) = viewModelScope.launch {
        c.settings.setPlaylistEnabled(id, on)
        reloadAfterSourceChange()
    }

    fun movePlaylist(id: String, delta: Int) = viewModelScope.launch {
        c.settings.movePlaylist(id, delta)
        reloadAfterSourceChange()
    }

    private suspend fun reloadAfterSourceChange() {
        val ok = repo.refresh()
        val ids = repo.allChannels().map { it.id }.toSet()
        // Keep watching the same channel if it's still there (e.g. after adding a second playlist).
        if (_current.value?.id !in ids) {
            if (_current.value != null) player.stop()
            _current.value = null
            previous = null
        }
        if (_filter.value != ChannelFilter.All && _filter.value !in filters.value) _filter.value = ChannelFilter.All
        if (ok) ensurePlaying()
    }

    fun setStartOnBoot(on: Boolean) = viewModelScope.launch { c.settings.setStartOnBoot(on) }
    fun setInvertChannelKeys(on: Boolean) = viewModelScope.launch { c.settings.setInvertChannelKeys(on) }
    fun setBackAction(action: BackAction) = viewModelScope.launch { c.settings.setBackAction(action) }

    fun setRefreshHours(hours: Int) = viewModelScope.launch {
        c.settings.setRefreshHours(hours)
        RefreshWorker.schedule(getApplication<Application>(), hours)
    }

    /** Steps through 6 h → 12 h → 24 h → 48 h → off. */
    fun cycleRefreshHours(currentHours: Int) = viewModelScope.launch {
        val index = REFRESH_CHOICES.indexOf(currentHours)
        val next = REFRESH_CHOICES[(index + 1).mod(REFRESH_CHOICES.size)]
        c.settings.setRefreshHours(next)
        RefreshWorker.schedule(getApplication<Application>(), next)
    }

    companion object {
        fun roundDown(time: Long) = time - time.mod(GUIDE_STEP)
        fun recordingKey(channelId: String, programStart: Long) = "$channelId@$programStart"
    }
}
