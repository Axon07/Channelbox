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
import io.github.axon07.channelbox.data.SourceType
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

    data class Group(val name: String) : ChannelFilter {
        override val label: String get() = name
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
    val player = TvPlayer(app, c.http, runBlocking { c.settings.current().preferSoftwareAudio })

    val settings: StateFlow<AppSettings?> = c.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val refreshState: StateFlow<RefreshState> = repo.state

    val allChannels: StateFlow<List<ChannelEntity>> = repo.channels
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favorites: StateFlow<Set<String>> = repo.favorites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _filter = MutableStateFlow<ChannelFilter>(ChannelFilter.All)
    val filter: StateFlow<ChannelFilter> = _filter.asStateFlow()

    val filters: StateFlow<List<ChannelFilter>> = allChannels
        .map { list ->
            listOf(ChannelFilter.All, ChannelFilter.Favorites) +
                list.map { it.groupTitle }.distinct().map { ChannelFilter.Group(it) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(ChannelFilter.All, ChannelFilter.Favorites))

    val visibleChannels: StateFlow<List<ChannelEntity>> =
        combine(allChannels, favorites, _filter) { list, favs, f ->
            when (f) {
                ChannelFilter.All -> list
                ChannelFilter.Favorites -> list.filter { it.id in favs }
                is ChannelFilter.Group -> list.filter { it.groupTitle == f.name }
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
            if (updater.openInstallPermission()) {
                showToast("Allow Channelbox to install apps, then choose Update again")
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
        recording.filePath?.let { player.playFile(it) }
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

    /** Back to live TV after watching a recording. */
    fun resumeLive() {
        _current.value?.let { player.play(StreamSpecs.from(it)) }
    }

    init {
        // Startup update check, a few seconds in so it doesn't slow down tuning the first channel.
        viewModelScope.launch {
            delay(8_000)
            if (c.settings.current().autoUpdateCheck) checkForUpdates(manual = false)
        }
        viewModelScope.launch {
            val s = c.settings.current()
            if (s.sourceType == SourceType.NONE) return@launch
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

    fun saveM3u(url: String, epgUrl: String) = viewModelScope.launch {
        c.settings.saveM3u(url, epgUrl)
        reloadAfterSourceChange()
    }

    fun saveXtream(server: String, user: String, pass: String, epgUrl: String) = viewModelScope.launch {
        c.settings.saveXtream(server, user, pass, epgUrl)
        reloadAfterSourceChange()
    }

    private suspend fun reloadAfterSourceChange() {
        if (repo.refresh()) {
            _current.value = null
            previous = null
            _filter.value = ChannelFilter.All
            ensurePlaying()
        }
    }

    fun setStartOnBoot(on: Boolean) = viewModelScope.launch { c.settings.setStartOnBoot(on) }
    fun setInvertChannelKeys(on: Boolean) = viewModelScope.launch { c.settings.setInvertChannelKeys(on) }
    fun setBackAction(action: BackAction) = viewModelScope.launch { c.settings.setBackAction(action) }

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
