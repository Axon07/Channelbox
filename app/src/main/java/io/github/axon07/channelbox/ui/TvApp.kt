package io.github.axon07.channelbox.ui

import android.view.KeyEvent as AndroidKeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import io.github.axon07.channelbox.data.VodKind
import io.github.axon07.channelbox.data.VodEntity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.axon07.channelbox.data.AppSettings
import io.github.axon07.channelbox.data.BackAction
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.ProgramEntity
import io.github.axon07.channelbox.data.RecStatus
import io.github.axon07.channelbox.data.RecordingEntity
import io.github.axon07.channelbox.data.RefreshState
import io.github.axon07.channelbox.player.PlayerStatus
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

/**
 * Screens. Back walks: TV → Guide → Groups menu → "Exit?" (configurable in Settings).
 */
enum class Mode {
    PLAYER, CHANNEL_LIST, GUIDE, GROUPS, PROGRAM_MENU,
    RECORDINGS, RECORDING_MENU, PLAYBACK,
    SETTINGS, SETUP, EXIT_CONFIRM, UPDATE, OPTIONS, MULTIVIEW,
    /** Movies / TV Shows (normal focus, like Settings). */
    VOD,
}

/** Screens where we read the remote ourselves (the others use normal focus). */
private val OWN_KEY_MODES = setOf(Mode.PLAYER, Mode.CHANNEL_LIST, Mode.GUIDE, Mode.GROUPS, Mode.RECORDINGS, Mode.PLAYBACK, Mode.MULTIVIEW)

/** Screens that show the guide with the small live preview. */
private val GUIDE_MODES = setOf(Mode.GUIDE, Mode.GROUPS, Mode.PROGRAM_MENU)

private class OkKeyTracker {
    var armed = false
    var longFired = false
}

private val OK_KEYS = setOf(
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
)

private val RECORD_KEYS = setOf(AndroidKeyEvent.KEYCODE_MEDIA_RECORD, AndroidKeyEvent.KEYCODE_PROG_RED)

/** Every key we act on. Anything else (volume, Home, Back…) is left to the system. */
private val HANDLED_KEYS = OK_KEYS + RECORD_KEYS + setOf(
    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT,
    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
    AndroidKeyEvent.KEYCODE_PAGE_UP, AndroidKeyEvent.KEYCODE_PAGE_DOWN,
    AndroidKeyEvent.KEYCODE_INFO, AndroidKeyEvent.KEYCODE_GUIDE, AndroidKeyEvent.KEYCODE_MENU,
    AndroidKeyEvent.KEYCODE_LAST_CHANNEL,
    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE,
    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, AndroidKeyEvent.KEYCODE_MEDIA_REWIND,
    AndroidKeyEvent.KEYCODE_CAPTIONS, AndroidKeyEvent.KEYCODE_PROG_GREEN,
) + (AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9) +
    (AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9)

private fun digitOf(code: Int): Int? = when (code) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> code - AndroidKeyEvent.KEYCODE_0
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 -> code - AndroidKeyEvent.KEYCODE_NUMPAD_0
    else -> null
}

@Composable
fun TvApp(vm: TvViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
    } else {
        TvAppContent(vm, s)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TvAppContent(vm: TvViewModel, settings: AppSettings) {
    val allChannels by vm.allChannels.collectAsStateWithLifecycle()
    val visible by vm.visibleChannels.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val current by vm.current.collectAsStateWithLifecycle()
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val guideStart by vm.guideStart.collectAsStateWithLifecycle()
    val guidePrograms by vm.guidePrograms.collectAsStateWithLifecycle()
    val refreshState by vm.refreshState.collectAsStateWithLifecycle()
    val playerStatus by vm.player.status.collectAsStateWithLifecycle()
    val bannerToken by vm.bannerToken.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val recordingKeys by vm.recordingKeys.collectAsStateWithLifecycle()
    val recordingChannels by vm.recordingChannels.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val updatePrompt by vm.updatePrompt.collectAsStateWithLifecycle()
    val tracksVersion by vm.player.tracksVersion.collectAsStateWithLifecycle()
    val filterCounts by vm.filterCounts.collectAsStateWithLifecycle()
    val inPip by vm.inPip.collectAsStateWithLifecycle()
    val vpnState by vm.vpnState.collectAsStateWithLifecycle()
    val timeFormat = rememberTimeFormatter()

    var mode by remember { mutableStateOf(if (!settings.hasSource) Mode.SETUP else Mode.PLAYER) }
    /** Playlist being edited on the setup screen; null = adding a new one. */
    var setupPlaylistId by remember { mutableStateOf<String?>(null) }
    /** Settings opened from first-time setup for network options. */
    var settingsFromSetup by remember { mutableStateOf(false) }
    /** When the groups menu was opened (for [groupsBack]). */
    var groupsOpenedAt by remember { mutableLongStateOf(0L) }
    val vodBrowser = remember { VodBrowser() }
    val movieCount by vm.movieCount.collectAsStateWithLifecycle()
    val showCount by vm.showCount.collectAsStateWithLifecycle()
    /** Movie/episode waiting for "Resume or start over?", with where you stopped. */
    var vodResume by remember { mutableStateOf<Triple<VodEntity, VodEntity?, Long>?>(null) }
    val scope = rememberCoroutineScope()
    /** Last key press or touch, for closing the guide/menus when idle. */
    var lastInput by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    var digits by remember { mutableStateOf("") }
    var bannerVisible by remember { mutableStateOf(false) }
    var touchBar by remember { mutableStateOf(false) }
    var touchBarToken by remember { mutableIntStateOf(0) }
    var listIndex by remember { mutableIntStateOf(0) }
    var groupsIndex by remember { mutableIntStateOf(0) }
    var recIndex by remember { mutableIntStateOf(0) }
    var exitReturn by remember { mutableStateOf(Mode.GROUPS) }
    var updateReturn by remember { mutableStateOf(Mode.PLAYER) }
    var optionsReturn by remember { mutableStateOf(Mode.PLAYER) }
    var optionsToken by remember { mutableIntStateOf(0) }
    var playbackReturn by remember { mutableStateOf(Mode.RECORDINGS) }
    val multi = remember { MultiViewState() }
    var multiPicking by remember { mutableStateOf(false) }
    var multiPickIndex by remember { mutableIntStateOf(0) }
    var streamInfo by remember { mutableStateOf<io.github.axon07.channelbox.player.StreamInfo?>(null) }
    var captionLangs by remember { mutableStateOf<List<String>>(emptyList()) }
    var menuChannel by remember { mutableStateOf<ChannelEntity?>(null) }
    var menuProgram by remember { mutableStateOf<ProgramEntity?>(null) }
    var menuRecording by remember { mutableStateOf<RecordingEntity?>(null) }
    var playbackTitle by remember { mutableStateOf("") }
    var playbackToken by remember { mutableIntStateOf(0) }
    var playbackOverlay by remember { mutableStateOf(true) }
    var pbPosition by remember { mutableLongStateOf(0L) }
    var pbDuration by remember { mutableLongStateOf(0L) }
    var pbPlaying by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val groupsListState = rememberLazyListState()
    val recListState = rememberLazyListState()
    val guide = remember { GuideState() }
    val ok = remember { OkKeyTracker() }
    val rootFocus = remember { FocusRequester() }
    val activity = LocalContext.current.findActivity()
    val swipeThreshold = with(LocalDensity.current) { 60.dp.toPx() }

    val weOwnKeys = mode in OWN_KEY_MODES

    // ---- Navigation helpers --------------------------------------------------------------------

    fun exitApp() {
        vm.player.stop()
        activity?.finishAndRemoveTask()
    }

    fun openGuide() {
        guide.open(visible, current?.id, vm)
        mode = Mode.GUIDE
    }

    fun openGroups(index: Int = filters.indexOf(filter).coerceAtLeast(0)) {
        groupsIndex = index
        groupsOpenedAt = android.os.SystemClock.uptimeMillis()
        mode = Mode.GROUPS
    }

    fun openList() {
        listIndex = visible.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
        mode = Mode.CHANNEL_LIST
    }

    fun askExit(returnTo: Mode) {
        exitReturn = returnTo
        mode = Mode.EXIT_CONFIRM
    }

    /** Back on the groups menu – the last step before leaving. */
    fun lastBack() {
        when (settings.backAction) {
            BackAction.ASK_EXIT -> askExit(Mode.GROUPS)
            BackAction.OPEN_GUIDE -> mode = Mode.PLAYER // never exit: go round to live TV
            BackAction.EXIT -> exitApp()
        }
    }

    /**
     * Back on the groups menu. Pressed quickly after arriving (Back, Back, Back from TV), it's the
     * last step: ask to exit. After spending time in the menu it just closes the menu, back to TV.
     */
    fun groupsBack() {
        if (android.os.SystemClock.uptimeMillis() - groupsOpenedAt < QUICK_BACK_MS) lastBack()
        else mode = Mode.PLAYER
    }

    fun openMultiView() {
        multi.start(vm, current)
        multiPicking = false
        mode = Mode.MULTIVIEW
    }

    /** Leaves multi-view; [watch] = the channel to continue full screen. */
    fun closeMultiView(watch: ChannelEntity? = null) {
        multi.release()
        multiPicking = false
        if (watch != null) vm.tune(watch) else vm.resumeLive()
        mode = Mode.PLAYER
    }

    fun toggleCaptions() {
        if (vm.player.captionLanguages().isEmpty()) {
            vm.showToast("No subtitles or captions on this channel")
        } else {
            vm.showToast(if (vm.player.toggleCaptions()) "Captions on" else "Captions off")
        }
    }

    // Groups menu layout: groups 0 until F, then Multi-view F, Recordings F+1, Settings F+2,
    // Movies F+3, TV Shows F+4 (shown above the shortcuts).
    val tileMovies = filters.size + 3
    val tileShows = filters.size + 4
    val libraryTiles = listOfNotNull(tileMovies.takeIf { movieCount > 0 }, tileShows.takeIf { showCount > 0 })

    fun openVod(kind: String) {
        vodBrowser.open(kind)
        mode = Mode.VOD
    }

    fun selectGroupItem(i: Int) {
        when {
            i == tileMovies -> openVod(VodKind.MOVIE)
            i == tileShows -> openVod(VodKind.SERIES)
            i < filters.size -> {
                vm.setFilter(filters[i])
                guide.row = 0
                mode = Mode.GUIDE
            }
            i == filters.size -> openMultiView()
            i == filters.size + 1 -> {
                recIndex = 0
                mode = Mode.RECORDINGS
            }
            else -> mode = Mode.SETTINGS
        }
    }

    fun openProgramMenu(channel: ChannelEntity, program: ProgramEntity?) {
        menuChannel = channel
        menuProgram = program
        mode = Mode.PROGRAM_MENU
    }

    fun tuneAndWatch(channel: ChannelEntity) {
        vm.tune(channel)
        mode = Mode.PLAYER
    }

    fun openOptions() {
        optionsReturn = mode
        optionsToken++
        mode = Mode.OPTIONS
    }

    /** Plays a past programme from the provider's archive. */
    fun watchCatchUp(channel: ChannelEntity, program: ProgramEntity) {
        if (vm.playCatchUp(channel, program)) {
            playbackTitle = "↺ ${channel.name}: ${program.title}"
            playbackReturn = Mode.GUIDE
            playbackToken++
            mode = Mode.PLAYBACK
        } else {
            vm.showToast("Catch-up isn't available for this channel")
        }
    }

    /** OK / tap on a programme: on now → watch, finished → catch-up, future → record menu. */
    fun programPressed(channel: ChannelEntity, program: ProgramEntity?) {
        val t = System.currentTimeMillis()
        when {
            program == null || (program.start <= t && t < program.stop) -> tuneAndWatch(channel)
            program.stop <= t ->
                if (vm.canCatchUp(channel, program)) watchCatchUp(channel, program)
                else vm.showToast("This programme has finished – the channel has no catch-up")
            else -> openProgramMenu(channel, program)
        }
    }

    fun openRecording(r: RecordingEntity) {
        val path = r.filePath
        if (path != null && File(path).length() > 0) {
            playbackTitle = r.title
            playbackReturn = Mode.RECORDINGS
            vm.playRecording(r)
            playbackToken++
            mode = Mode.PLAYBACK
        } else {
            menuRecording = r
            mode = Mode.RECORDING_MENU
        }
    }

    fun leavePlayback() {
        if (playbackReturn == Mode.VOD) {
            vm.stopVod()
            vodBrowser.focusToken++
        }
        vm.resumeLive()
        mode = playbackReturn
    }

    fun startVod(item: VodEntity, show: VodEntity?, at: Long) {
        vodResume = null
        playbackTitle = if (show != null) {
            val se = if (item.season > 0) "S${item.season} E${item.episode} · " else ""
            "${show.title} · $se${item.title}"
        } else item.title
        playbackReturn = Mode.VOD
        vm.playVod(item, at, show?.id)
        playbackToken++
        mode = Mode.PLAYBACK
    }

    fun playVod(item: VodEntity, show: VodEntity?) {
        scope.launch {
            val at = vm.vodResumeAt(item)
            if (at != null) vodResume = Triple(item, show, at) else startVod(item, show, 0)
        }
    }

    fun seekBy(delta: Long) {
        val exo = vm.player.exo
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        exo.seekTo((exo.currentPosition + delta).coerceIn(0L, duration))
        playbackToken++
    }

    fun togglePause() {
        val exo = vm.player.exo
        if (exo.isPlaying) exo.pause() else exo.play()
        playbackToken++
    }

    fun commitDigits() {
        val n = digits.toIntOrNull()
        digits = ""
        if (n == null) return
        if (mode == Mode.GUIDE) {
            val i = visible.indexOfFirst { it.number == n }
            if (i >= 0) guide.row = i
        } else {
            vm.tuneNumber(n)
        }
    }

    // ---- Remote control ------------------------------------------------------------------------

    fun onShortOk() {
        when (mode) {
            Mode.PLAYER -> when {
                digits.isNotEmpty() -> commitDigits()
                allChannels.isEmpty() -> mode = Mode.SETTINGS
                else -> openList()
            }
            Mode.CHANNEL_LIST -> visible.getOrNull(listIndex)?.let { tuneAndWatch(it) }
            Mode.GUIDE -> {
                if (digits.isNotEmpty()) {
                    commitDigits()
                } else {
                    val ch = visible.getOrNull(guide.row)
                    if (ch != null) programPressed(ch, guide.programAt(ch, guidePrograms))
                }
            }
            Mode.GROUPS -> selectGroupItem(groupsIndex)
            Mode.RECORDINGS -> recordings.getOrNull(recIndex)?.let { openRecording(it) }
            Mode.PLAYBACK -> togglePause()
            Mode.MULTIVIEW -> if (multiPicking) {
                visible.getOrNull(multiPickIndex)?.let { multi.assign(vm, multi.focused, it) }
                multiPicking = false
            } else {
                multiPickIndex = visible.indexOfFirst { it.id == multi.channels[multi.focused]?.id }.coerceAtLeast(0)
                multiPicking = true
            }
            else -> Unit
        }
    }

    fun onLongOk() {
        when (mode) {
            Mode.PLAYER -> openOptions()
            Mode.CHANNEL_LIST -> visible.getOrNull(listIndex)?.let { vm.toggleFavorite(it) }
            Mode.GUIDE -> visible.getOrNull(guide.row)?.let { openProgramMenu(it, guide.programAt(it, guidePrograms)) }
            Mode.RECORDINGS -> recordings.getOrNull(recIndex)?.let {
                menuRecording = it
                mode = Mode.RECORDING_MENU
            }
            Mode.MULTIVIEW -> if (!multiPicking) multi.channels[multi.focused]?.let { closeMultiView(it) }
            else -> Unit
        }
    }

    fun onKeyDown(code: Int): Boolean {
        if (mode == Mode.PLAYER || mode == Mode.CHANNEL_LIST || mode == Mode.GUIDE) {
            digitOf(code)?.let { d ->
                if (mode == Mode.CHANNEL_LIST) mode = Mode.PLAYER
                digits = (digits + d).takeLast(4)
                return true
            }
        }
        val up = if (settings.invertChannelKeys) -1 else 1
        when (mode) {
            Mode.PLAYER -> when (code) {
                AndroidKeyEvent.KEYCODE_DPAD_UP -> vm.zap(up)
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> vm.zap(-up)
                AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> vm.zap(1)
                AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> vm.zap(-1)
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> openList()
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_INFO -> {
                    if (bannerVisible) bannerVisible = false else vm.showBanner()
                }
                AndroidKeyEvent.KEYCODE_GUIDE -> openGuide()
                AndroidKeyEvent.KEYCODE_MENU -> openOptions()
                AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> vm.previousChannel()
                in RECORD_KEYS -> vm.toggleRecordCurrent()
                AndroidKeyEvent.KEYCODE_CAPTIONS -> toggleCaptions()
                AndroidKeyEvent.KEYCODE_PROG_GREEN -> openMultiView()
                else -> return false
            }
            Mode.CHANNEL_LIST -> {
                val size = visible.size
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> if (size > 0) listIndex = (listIndex - 1).mod(size)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> if (size > 0) listIndex = (listIndex + 1).mod(size)
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP ->
                        listIndex = (listIndex - 8).coerceAtLeast(0)
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN ->
                        listIndex = (listIndex + 8).coerceAtMost((size - 1).coerceAtLeast(0))
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { vm.cycleFilter(-1); listIndex = 0 }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { vm.cycleFilter(1); listIndex = 0 }
                    AndroidKeyEvent.KEYCODE_GUIDE, AndroidKeyEvent.KEYCODE_MENU -> openGuide()
                    AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> { vm.previousChannel(); mode = Mode.PLAYER }
                    else -> return false
                }
            }
            Mode.GUIDE -> {
                val channel = visible.getOrNull(guide.row)
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> guide.moveVertical(-1, visible.size)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> guide.moveVertical(1, visible.size)
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> guide.moveVertical(-7, visible.size)
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> guide.moveVertical(7, visible.size)
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> guide.moveHorizontal(-1, channel, guidePrograms, vm)
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> guide.moveHorizontal(1, channel, guidePrograms, vm)
                    AndroidKeyEvent.KEYCODE_GUIDE -> mode = Mode.PLAYER
                    AndroidKeyEvent.KEYCODE_MENU -> openGroups()
                    in RECORD_KEYS -> {
                        val p = guide.programAt(channel, guidePrograms)
                        if (channel != null && p != null) vm.toggleRecordProgram(channel, p)
                    }
                    else -> return false
                }
            }
            Mode.GROUPS -> when (code) {
                AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                    val f = filters.size
                    groupsIndex = when {
                        groupsIndex in 1 until f -> groupsIndex - 1
                        groupsIndex == 0 -> f // wrap to the shortcuts
                        groupsIndex in libraryTiles -> (f - 1).coerceAtLeast(0)
                        else -> libraryTiles.firstOrNull() ?: (f - 1).coerceAtLeast(0) // shortcuts
                    }
                }
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                    val f = filters.size
                    groupsIndex = when {
                        groupsIndex < f - 1 -> groupsIndex + 1
                        groupsIndex == f - 1 -> libraryTiles.firstOrNull() ?: f
                        groupsIndex in libraryTiles -> f
                        else -> 0 // shortcuts: wrap to the top
                    }
                }
                AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP ->
                    groupsIndex = (groupsIndex - 8).coerceAtLeast(0)
                AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN ->
                    groupsIndex = (groupsIndex + 8).coerceAtMost((filters.size - 1).coerceAtLeast(0))
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> when {
                    groupsIndex >= filters.size && groupsIndex < filters.size + 2 -> groupsIndex++
                    groupsIndex == tileMovies && tileShows in libraryTiles -> groupsIndex = tileShows
                    else -> mode = Mode.GUIDE
                }
                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> when {
                    groupsIndex > filters.size && groupsIndex <= filters.size + 2 -> groupsIndex--
                    groupsIndex == tileShows && tileMovies in libraryTiles -> groupsIndex = tileMovies
                    else -> Unit
                }
                AndroidKeyEvent.KEYCODE_MENU -> mode = Mode.GUIDE
                AndroidKeyEvent.KEYCODE_GUIDE -> mode = Mode.PLAYER
                else -> return false
            }
            Mode.RECORDINGS -> {
                val size = recordings.size
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> if (size > 0) recIndex = (recIndex - 1).mod(size)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> if (size > 0) recIndex = (recIndex + 1).mod(size)
                    AndroidKeyEvent.KEYCODE_MENU -> recordings.getOrNull(recIndex)?.let {
                        menuRecording = it
                        mode = Mode.RECORDING_MENU
                    }
                    AndroidKeyEvent.KEYCODE_GUIDE -> openGuide()
                    else -> return false
                }
            }
            Mode.PLAYBACK -> when (code) {
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePause()
                AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { vm.player.exo.play(); playbackToken++ }
                AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { vm.player.exo.pause(); playbackToken++ }
                AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-10_000L)
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(30_000L)
                AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_INFO -> playbackToken++
                AndroidKeyEvent.KEYCODE_MENU -> openOptions()
                AndroidKeyEvent.KEYCODE_CAPTIONS -> toggleCaptions()
                else -> return false
            }
            Mode.MULTIVIEW -> if (multiPicking) {
                val size = visible.size
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> if (size > 0) multiPickIndex = (multiPickIndex - 1).mod(size)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> if (size > 0) multiPickIndex = (multiPickIndex + 1).mod(size)
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { vm.cycleFilter(-1); multiPickIndex = 0 }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { vm.cycleFilter(1); multiPickIndex = 0 }
                    else -> return false
                }
            } else {
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> multi.move(0, -1)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> multi.move(0, 1)
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> multi.move(-1, 0)
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> multi.move(1, 0)
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_PROG_GREEN ->
                        multi.setTileCount(if (multi.tiles == 4) 2 else 4)
                    else -> return false
                }
            }
            else -> return false
        }
        return true
    }

    fun handleKey(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val code = native.keyCode
        if (code !in HANDLED_KEYS) return false
        val isDown = native.action == AndroidKeyEvent.ACTION_DOWN

        // OK acts on release so a long press can mean something else.
        if (code in OK_KEYS) {
            if (isDown) {
                if (native.repeatCount == 0) {
                    ok.armed = true
                    ok.longFired = false
                } else if (ok.armed && !ok.longFired && (native.isLongPress || native.repeatCount >= 4)) {
                    ok.longFired = true
                    onLongOk()
                }
            } else {
                if (ok.armed && !ok.longFired) onShortOk()
                ok.armed = false
            }
            return true
        }
        return if (isDown) onKeyDown(code) else true
    }

    // ---- Timers --------------------------------------------------------------------------------

    LaunchedEffect(bannerToken) {
        if (bannerToken > 0) {
            bannerVisible = true
            delay(5_000)
            bannerVisible = false
        }
    }
    // Live stream details for the info banner (resolution, fps, audio, bitrate, captions).
    LaunchedEffect(bannerVisible) {
        while (bannerVisible) {
            streamInfo = vm.player.streamInfo()
            captionLangs = vm.player.captionLanguages()
            delay(1_000)
        }
    }
    LaunchedEffect(touchBarToken) {
        if (touchBarToken > 0) {
            touchBar = true
            delay(6_000)
            touchBar = false
        }
    }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(2_000)
            commitDigits()
        }
    }
    LaunchedEffect(listIndex, mode, multiPickIndex, multiPicking) {
        if (mode == Mode.CHANNEL_LIST) listState.scrollToItem((listIndex - 3).coerceAtLeast(0))
        if (mode == Mode.MULTIVIEW && multiPicking) listState.scrollToItem((multiPickIndex - 3).coerceAtLeast(0))
    }
    LaunchedEffect(mode) {
        ok.armed = false
        if (weOwnKeys) {
            delay(32)
            runCatching { rootFocus.requestFocus() }
        }
    }
    LaunchedEffect(mode) { if (mode != Mode.SETTINGS) settingsFromSetup = false }
    // Guide and menus close back to TV after a while with no button pressed.
    val idleModes = mode == Mode.GUIDE || mode == Mode.GROUPS || mode == Mode.CHANNEL_LIST || mode == Mode.PROGRAM_MENU
    LaunchedEffect(mode, lastInput, settings.idleCloseSeconds) {
        val seconds = settings.idleCloseSeconds
        if (!idleModes || seconds <= 0 || current == null) return@LaunchedEffect
        kotlinx.coroutines.delay(seconds * 1000L)
        mode = Mode.PLAYER
    }
    // "Update available" prompt: shown over whatever screen is open (but not during first setup).
    // Keyed on setup too, so a prompt that arrived during setup shows as soon as setup is done.
    LaunchedEffect(updatePrompt, mode == Mode.SETUP) {
        if (updatePrompt && mode != Mode.UPDATE && mode != Mode.SETUP) {
            updateReturn = mode
            mode = Mode.UPDATE
        } else if (!updatePrompt && mode == Mode.UPDATE) {
            mode = updateReturn
        }
    }
    LaunchedEffect(toast) {
        val t = toast ?: return@LaunchedEffect
        delay(3_000)
        vm.clearToast(t.id)
    }
    // Recording playback: poll the position for the progress bar, hide the controls when idle.
    LaunchedEffect(mode) {
        var ticks = 0
        while (mode == Mode.PLAYBACK) {
            if (playbackReturn == Mode.VOD && ++ticks % 60 == 0) vm.saveVodProgress()
            val exo = vm.player.exo
            pbPosition = exo.currentPosition
            pbDuration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            pbPlaying = exo.isPlaying
            delay(500)
        }
    }
    LaunchedEffect(playbackToken) {
        playbackOverlay = true
        delay(4_000)
        if (vm.player.exo.isPlaying) playbackOverlay = false
    }

    // ---- Touch / mouse -------------------------------------------------------------------------

    fun onScreenTap() {
        when (mode) {
            Mode.PLAYER -> if (touchBar) {
                touchBar = false
            } else {
                touchBarToken++
                if (current != null) vm.showBanner()
            }
            Mode.CHANNEL_LIST -> mode = Mode.PLAYER
            Mode.GROUPS -> mode = Mode.GUIDE
            Mode.PROGRAM_MENU -> mode = Mode.GUIDE
            Mode.RECORDING_MENU -> mode = Mode.RECORDINGS
            Mode.EXIT_CONFIRM -> mode = exitReturn
            Mode.UPDATE -> vm.dismissUpdate(skipThisVersion = false)
            Mode.OPTIONS -> mode = optionsReturn
            Mode.PLAYBACK -> playbackToken++
            else -> Unit
        }
    }

    // Swipe up = next channel, swipe down = previous (phones/tablets).
    val onSwipe by rememberUpdatedState<(Float) -> Unit> { total ->
        if (mode == Mode.PLAYER && abs(total) > swipeThreshold) vm.zap(if (total < 0) 1 else -1)
    }

    // ---- Back: TV → Guide → Groups → Exit? ----------------------------------------------------

    BackHandler(enabled = !(mode == Mode.SETUP && !settings.hasSource)) {
        when (mode) {
            Mode.PLAYER -> when {
                digits.isNotEmpty() -> digits = ""
                touchBar -> touchBar = false
                bannerVisible -> bannerVisible = false
                else -> openGuide()
            }
            Mode.CHANNEL_LIST -> mode = Mode.PLAYER
            Mode.GUIDE -> openGroups()
            Mode.GROUPS -> groupsBack()
            Mode.EXIT_CONFIRM -> mode = exitReturn
            Mode.UPDATE -> vm.dismissUpdate(skipThisVersion = false)
            Mode.OPTIONS -> mode = optionsReturn
            Mode.PROGRAM_MENU -> mode = Mode.GUIDE
            Mode.RECORDINGS -> openGroups(filters.size + 1)
            Mode.RECORDING_MENU -> mode = Mode.RECORDINGS
            Mode.PLAYBACK -> leavePlayback()
            Mode.MULTIVIEW -> if (multiPicking) multiPicking = false else closeMultiView()
            Mode.SETTINGS -> if (!settings.hasSource) mode = Mode.SETUP else openGroups(filters.size + 2)
            Mode.VOD -> when {
                vodResume != null -> {
                    vodResume = null
                    vodBrowser.focusToken++
                }
                vodBrowser.show != null -> vodBrowser.closeShow()
                vodBrowser.query.isNotEmpty() -> vodBrowser.query = ""
                else -> openGroups(if (vodBrowser.kind == VodKind.MOVIE) tileMovies else tileShows)
            }
            Mode.SETUP -> mode = Mode.SETTINGS
        }
    }

    // ---- Layout --------------------------------------------------------------------------------

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent {
                lastInput = android.os.SystemClock.uptimeMillis()
                if (weOwnKeys) handleKey(it) else false
            }
            .pointerInput(Unit) {
                // Watch every touch (without consuming it) so the idle timer restarts.
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        lastInput = android.os.SystemClock.uptimeMillis()
                    }
                }
            }
            .focusRequester(rootFocus)
            // Only focusable while we handle the remote ourselves, so Settings/dialogs get normal focus.
            .focusable(enabled = weOwnKeys)
            .tap { onScreenTap() }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { onSwipe(total) },
                    onDragCancel = { total = 0f },
                ) { change, amount ->
                    total += amount
                    change.consume()
                }
            }
    ) {
        // Always the first child so the view (and its video surface) is never recreated;
        // in the guide it shrinks into the top-right preview window.
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = vm.player.exo
                    keepScreenOn = true
                    isFocusable = false
                    isClickable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { view ->
                view.resizeMode = when (settings.videoScale) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = if (inPip) {
                Modifier.fillMaxSize()
            } else if (mode == Mode.MULTIVIEW) {
                Modifier.size(0.dp)
            } else if (mode in GUIDE_MODES) {
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = PREVIEW_PADDING, end = PREVIEW_PADDING)
                    .size(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            } else {
                Modifier.fillMaxSize()
            },
        )

        // In picture-in-picture only the video is shown.
        if (!inPip) when (mode) {
            Mode.GUIDE, Mode.GROUPS, Mode.PROGRAM_MENU -> {
                GuideScreen(
                    state = guide,
                    channels = visible,
                    programs = guidePrograms,
                    filterLabel = filter.label,
                    guideStart = guideStart,
                    now = now,
                    playingId = current?.id,
                    favorites = favorites,
                    recordingKeys = recordingKeys,
                    canCatchUp = { ch, p -> vm.canCatchUp(ch, p) },
                    timeFormat = timeFormat,
                    onTune = { tuneAndWatch(it) },
                    onProgram = { ch, p -> programPressed(ch, p) },
                    onMenu = { ch, p -> openProgramMenu(ch, p) },
                    onGroups = { openGroups() },
                    onShift = { delta ->
                        vm.setGuideStart(guideStart + delta)
                        guide.focusTime = guideStart + delta
                    },
                    onNow = {
                        val t = System.currentTimeMillis()
                        vm.setGuideStart(t)
                        guide.focusTime = t
                    },
                    onClose = { mode = Mode.PLAYER },
                )
                if (mode == Mode.GROUPS) {
                    GroupsPanel(
                        filters = filters,
                        counts = filterCounts,
                        active = filter,
                        selected = groupsIndex,
                        listState = groupsListState,
                        recordingCount = recordings.count { it.status != RecStatus.CANCELLED },
                        movieCount = movieCount,
                        showCount = showCount,
                        onSelect = { selectGroupItem(it) },
                    )
                }
                val ch = menuChannel
                if (mode == Mode.PROGRAM_MENU && ch != null) {
                    val p = menuProgram
                    val t = System.currentTimeMillis()
                    val actions = mutableListOf(DialogAction("Watch ${ch.name}") { tuneAndWatch(ch) })
                    if (p != null && p.start < t && vm.canCatchUp(ch, p)) {
                        actions += DialogAction(if (p.stop <= t) "↺ Watch (catch-up)" else "↺ Watch from the start") {
                            watchCatchUp(ch, p)
                        }
                    }
                    if (p != null && p.stop > t) {
                        val scheduled = TvViewModel.recordingKey(ch.id, p.start) in recordingKeys
                        actions += DialogAction(if (scheduled) "Cancel recording" else "● Record") {
                            vm.toggleRecordProgram(ch, p)
                            mode = Mode.GUIDE
                        }
                    }
                    actions += DialogAction(if (ch.id in favorites) "★ Remove favourite" else "☆ Add favourite") {
                        vm.toggleFavorite(ch)
                        mode = Mode.GUIDE
                    }
                    actions += DialogAction("Close") { mode = Mode.GUIDE }
                    ActionDialog(
                        title = p?.title ?: ch.name,
                        subtitle = p?.let { "${ch.name}  ·  ${timeFormat(it.start)} – ${timeFormat(it.stop)}" },
                        actions = actions,
                    )
                }
            }
            Mode.RECORDINGS, Mode.RECORDING_MENU -> {
                RecordingsScreen(
                    recordings = recordings,
                    selected = recIndex.coerceAtMost((recordings.size - 1).coerceAtLeast(0)),
                    listState = recListState,
                    onPlay = { openRecording(it) },
                    onMenu = {
                        menuRecording = it
                        mode = Mode.RECORDING_MENU
                    },
                )
                val r = menuRecording?.let { m -> recordings.firstOrNull { it.id == m.id } }
                if (mode == Mode.RECORDING_MENU && r != null) {
                    val actions = mutableListOf<DialogAction>()
                    if (r.filePath != null && r.sizeBytes > 0) actions += DialogAction("▶ Play") { openRecording(r) }
                    if (r.status == RecStatus.RECORDING) actions += DialogAction("■ Stop recording") {
                        vm.stopRecording(r)
                        mode = Mode.RECORDINGS
                    }
                    if (r.status == RecStatus.SCHEDULED) actions += DialogAction("Cancel") {
                        vm.stopRecording(r)
                        mode = Mode.RECORDINGS
                    }
                    actions += DialogAction("Delete") {
                        vm.deleteRecording(r)
                        mode = Mode.RECORDINGS
                    }
                    actions += DialogAction("Close") { mode = Mode.RECORDINGS }
                    ActionDialog(title = r.title, subtitle = r.channelName, actions = actions)
                } else if (mode == Mode.RECORDING_MENU) {
                    mode = Mode.RECORDINGS // the recording was deleted meanwhile
                }
            }
            Mode.PLAYBACK -> {
                (playerStatus as? PlayerStatus.Error)?.let { StatusMessage(it, null, it.message) }
                if (playbackOverlay || !pbPlaying) {
                    PlaybackOverlay(
                        title = playbackTitle,
                        position = pbPosition,
                        duration = pbDuration,
                        playing = pbPlaying,
                        onToggle = { togglePause() },
                        onSeek = { seekBy(it) },
                        onClose = { leavePlayback() },
                    )
                }
            }
            Mode.VOD -> {
                VodScreen(vodBrowser, vm, onPlay = { item, show -> playVod(item, show) })
                vodResume?.let { (item, show, at) ->
                    ActionDialog(
                        title = item.title,
                        subtitle = "You stopped at ${formatClock(at)}",
                        actions = listOf(
                            DialogAction("Resume from ${formatClock(at)}") { startVod(item, show, at) },
                            DialogAction("Start over") { startVod(item, show, 0) },
                        ),
                    )
                }
            }
            Mode.SETTINGS -> SettingsScreen(
                openNetwork = settingsFromSetup,
                settings = settings,
                refreshState = refreshState,
                channelCount = allChannels.size,
                vm = vm,
                onEditSource = { id ->
                    setupPlaylistId = id
                    mode = Mode.SETUP
                },
                onOpenRecordings = {
                    recIndex = 0
                    mode = Mode.RECORDINGS
                },
                onExit = { exitApp() },
            )
            Mode.SETUP -> {
                // Decided once on entry: saving the first playlist mustn't restart this screen.
                val firstRun = remember(setupPlaylistId) { !settings.hasSource }
                key(setupPlaylistId) {
                    SetupScreen(
                        initial = settings.playlists.firstOrNull { it.id == setupPlaylistId },
                        firstRun = firstRun,
                        refreshState = refreshState,
                        vm = vm,
                        onNetwork = {
                            settingsFromSetup = true
                            mode = Mode.SETTINGS
                        },
                        onDone = { mode = if (firstRun) Mode.PLAYER else Mode.SETTINGS },
                    )
                }
            }
            Mode.UPDATE -> {
                when (val u = update) {
                    is TvViewModel.UpdateState.Available -> ActionDialog(
                        title = "Update available: Channelbox ${u.release.version}",
                        subtitle = "You have ${vm.appVersion}." +
                            (u.release.notes.lineSequence().firstOrNull { it.isNotBlank() }
                                ?.let { "\n" + it.trim().removePrefix("#").removePrefix("-").trim() } ?: ""),
                        actions = listOf(
                            DialogAction("Update now") { vm.startUpdate() },
                            DialogAction("Later") { vm.dismissUpdate(skipThisVersion = false) },
                            DialogAction("Skip this version") { vm.dismissUpdate(skipThisVersion = true) },
                        ),
                    )
                    is TvViewModel.UpdateState.Downloading -> ActionDialog(
                        title = "Downloading Channelbox ${u.release.version}… ${u.percent}%",
                        subtitle = "Android will ask you to confirm the update when it's done.",
                        actions = listOf(DialogAction("Hide") { vm.dismissUpdate(skipThisVersion = false) }),
                    )
                    is TvViewModel.UpdateState.Failed -> ActionDialog(
                        title = "Update failed",
                        subtitle = u.message,
                        actions = listOf(
                            DialogAction("Try again") { vm.checkForUpdates(manual = true) },
                            DialogAction("Close") { vm.dismissUpdate(skipThisVersion = false) },
                        ),
                    )
                    else -> Unit
                }
            }
            Mode.OPTIONS -> {
                // Re-read the tracks each time the panel opens or a choice is made.
                val audio = remember(optionsToken, tracksVersion) { vm.player.audioTracks() }
                val subtitles = remember(optionsToken, tracksVersion) { vm.player.subtitleTracks() }
                val quality = remember(optionsToken, tracksVersion) { vm.player.videoTracks() }
                OptionsPanel(
                    quality = quality,
                    onQuality = {
                        vm.player.select(C.TRACK_TYPE_VIDEO, it)
                        optionsToken++
                    },
                    audio = audio,
                    subtitles = subtitles,
                    scale = settings.videoScale,
                    onAudio = {
                        vm.player.select(C.TRACK_TYPE_AUDIO, it)
                        optionsToken++
                    },
                    onSubtitle = {
                        vm.player.select(C.TRACK_TYPE_TEXT, it)
                        optionsToken++
                    },
                    onScale = { vm.setVideoScale(it) },
                )
            }
            Mode.MULTIVIEW -> {
                MultiViewScreen(multi, onTileTap = { i ->
                    if (multi.focused == i) {
                        multiPickIndex = 0
                        multiPicking = true
                    } else {
                        multi.select(i)
                    }
                })
                if (multiPicking) {
                    ChannelListPanel(
                        channels = visible,
                        selected = multiPickIndex,
                        listState = listState,
                        filterLabel = filter.label,
                        favorites = favorites,
                        playingId = multi.channels[multi.focused]?.id,
                        upcoming = upcoming,
                        now = now,
                        onSelect = { i ->
                            visible.getOrNull(i)?.let { multi.assign(vm, multi.focused, it) }
                            multiPicking = false
                        },
                        onFavorite = { i -> visible.getOrNull(i)?.let { vm.toggleFavorite(it) } },
                        onGroup = { d ->
                            vm.cycleFilter(d)
                            multiPickIndex = 0
                        },
                    )
                }
            }
            Mode.EXIT_CONFIRM -> ActionDialog(
                title = "Exit Channelbox?",
                subtitle = null,
                actions = listOf(
                    DialogAction("Exit") { exitApp() },
                    DialogAction("Cancel") { mode = exitReturn },
                ),
            )
            Mode.PLAYER, Mode.CHANNEL_LIST -> {
                val fallback = when (val r = refreshState) {
                    is RefreshState.Running -> r.message
                    is RefreshState.Failed -> "Couldn't load channels: ${r.message}\nPress OK for settings"
                    else -> if (allChannels.isEmpty()) "No channels yet – press OK for settings" else null
                }
                StatusMessage(playerStatus, current, fallback)
                val cur = current
                if (cur != null && cur.id in recordingChannels) RecIndicator()
                if (mode == Mode.PLAYER && bannerVisible && cur != null) {
                    InfoBanner(
                        cur, cur.id in favorites, cur.epgId?.let { upcoming[it] }, now, timeFormat,
                        info = streamInfo,
                        captions = captionLangs,
                        vpnOn = vpnState == io.github.axon07.channelbox.system.VpnManager.State.On,
                    )
                }
                if (mode == Mode.PLAYER && touchBar) {
                    TouchBar(
                        favorite = cur != null && cur.id in favorites,
                        recording = cur != null && cur.id in recordingChannels,
                        onChannels = { touchBar = false; openList() },
                        onGuide = { touchBar = false; openGuide() },
                        onChannelUp = { touchBarToken++; vm.zap(1) },
                        onChannelDown = { touchBarToken++; vm.zap(-1) },
                        onFavorite = { touchBarToken++; cur?.let { vm.toggleFavorite(it) } },
                        onRecord = { touchBarToken++; vm.toggleRecordCurrent() },
                        onCaptions = { touchBarToken++; toggleCaptions() },
                        onOptions = { touchBar = false; openOptions() },
                        onPip = if (io.github.axon07.channelbox.system.SetTopBox.supportsPip(LocalContext.current)) {
                            { touchBar = false; activity?.let { io.github.axon07.channelbox.system.SetTopBox.enterPip(it) } }
                        } else null,
                        onMultiView = { touchBar = false; openMultiView() },
                        onSettings = { touchBar = false; mode = Mode.SETTINGS },
                        onExit = { touchBar = false; askExit(Mode.PLAYER) },
                    )
                }
                if (mode == Mode.CHANNEL_LIST) {
                    ChannelListPanel(
                        channels = visible,
                        selected = listIndex,
                        listState = listState,
                        filterLabel = filter.label,
                        favorites = favorites,
                        playingId = cur?.id,
                        upcoming = upcoming,
                        now = now,
                        onSelect = { i -> visible.getOrNull(i)?.let { tuneAndWatch(it) } },
                        onFavorite = { i -> visible.getOrNull(i)?.let { vm.toggleFavorite(it) } },
                        onGroup = { d ->
                            vm.cycleFilter(d)
                            listIndex = 0
                        },
                    )
                }
            }
        }
        if (digits.isNotEmpty() && !inPip) {
            NumberEntry(digits, digits.toIntOrNull()?.let { n -> allChannels.firstOrNull { it.number == n } })
        }
        if (!inPip) toast?.let { ToastMessage(it.text) }
    }
}

/** Back presses closer together than this go straight through the menus to "Exit?". */
private const val QUICK_BACK_MS = 2_000L
