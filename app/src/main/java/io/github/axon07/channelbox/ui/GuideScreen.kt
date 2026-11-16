package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.ProgramEntity
import io.github.axon07.channelbox.data.TvRepository.Companion.HOUR

// Layout of the guide on a 960x540 dp TV screen. The live preview sits in the top-right corner.
val PREVIEW_WIDTH = 256.dp
val PREVIEW_HEIGHT = 144.dp
val PREVIEW_PADDING = 16.dp
private val TOP_HEIGHT = PREVIEW_HEIGHT + PREVIEW_PADDING * 2
private val CHANNEL_COLUMN = 200.dp
private val ROW_HEIGHT = 44.dp

/**
 * Selection state for the grid. The guide doesn't use Compose focus traversal: a
 * remote press moves a (row, time) cursor, which is predictable on slow boxes and
 * keeps the time axis shared between rows, like a cable EPG.
 */
class GuideState {
    var row by mutableIntStateOf(0)
    var focusTime by mutableLongStateOf(System.currentTimeMillis())
    val listState = LazyListState()

    fun open(channels: List<ChannelEntity>, playingId: String?, vm: TvViewModel) {
        val now = System.currentTimeMillis()
        focusTime = now
        vm.setGuideStart(now)
        row = channels.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
    }

    fun programAt(channel: ChannelEntity?, programs: Map<String, List<ProgramEntity>>, time: Long = focusTime) =
        channel?.epgId?.let { programs[it] }?.firstOrNull { it.start <= time && time < it.stop }

    fun moveVertical(delta: Int, channelCount: Int) {
        if (channelCount == 0) return
        row = (row + delta).coerceIn(0, channelCount - 1)
    }

    fun moveHorizontal(delta: Int, channel: ChannelEntity?, programs: Map<String, List<ProgramEntity>>, vm: TvViewModel) {
        val p = programAt(channel, programs)
        val target = if (delta > 0) {
            p?.stop ?: (TvViewModel.roundDown(focusTime) + GUIDE_STEP)
        } else {
            (p?.start ?: TvViewModel.roundDown(focusTime)) - 1
        }
        val earliest = System.currentTimeMillis() - 6 * HOUR
        if (target < earliest) return
        focusTime = target
        val start = vm.guideStart.value
        if (target >= start + GUIDE_SPAN) vm.setGuideStart(target - GUIDE_SPAN + GUIDE_STEP)
        else if (target < start) vm.setGuideStart(target)
    }
}

@Composable
fun GuideScreen(
    state: GuideState,
    channels: List<ChannelEntity>,
    programs: Map<String, List<ProgramEntity>>,
    filterLabel: String,
    guideStart: Long,
    now: Long,
    playingId: String?,
    favorites: Set<String>,
    recordingKeys: Set<String>,
    canCatchUp: (ChannelEntity, ProgramEntity) -> Boolean,
    timeFormat: (Long) -> String,
    onTune: (ChannelEntity) -> Unit,
    onProgram: (ChannelEntity, ProgramEntity) -> Unit,
    onMenu: (ChannelEntity, ProgramEntity?) -> Unit,
    onGroups: () -> Unit,
    onShift: (Long) -> Unit,
    onNow: () -> Unit,
    onClose: () -> Unit,
) {
    LaunchedEffect(state.row, channels.size) {
        if (channels.isNotEmpty()) state.listState.scrollToItem((state.row - 2).coerceAtLeast(0))
    }

    val selectedChannel = channels.getOrNull(state.row)
    val selectedProgram = state.programAt(selectedChannel, programs)

    Column(Modifier.fillMaxSize()) {
        // Top: programme details, with a transparent hole on the right for the live preview.
        Row(Modifier.fillMaxWidth().height(TOP_HEIGHT)) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Palette.Background)
                    .padding(start = 28.dp, top = 20.dp, end = 16.dp)
            ) {
                when {
                    selectedProgram != null -> {
                        val recorded = selectedChannel != null &&
                            TvViewModel.recordingKey(selectedChannel.id, selectedProgram.start) in recordingKeys
                        Text(
                            (if (recorded) "● " else "") + selectedProgram.title,
                            fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${timeFormat(selectedProgram.start)} – ${timeFormat(selectedProgram.stop)}  ·  ${selectedChannel?.name.orEmpty()}",
                            fontSize = 14.sp, color = Palette.Accent,
                        )
                        if (selectedChannel != null && selectedProgram.stop <= now && canCatchUp(selectedChannel, selectedProgram)) {
                            Text("↺ Catch-up available – press OK to watch", fontSize = 13.sp, color = Palette.Text)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            selectedProgram.description.orEmpty(), fontSize = 14.sp, color = Palette.TextDim,
                            maxLines = 5, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    selectedChannel != null -> {
                        Text(selectedChannel.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                        Spacer(Modifier.height(4.dp))
                        Text("No programme information", fontSize = 14.sp, color = Palette.TextDim)
                    }
                    else -> Text("No channels in this group", fontSize = 18.sp, color = Palette.TextDim)
                }
            }
            // Transparent: the live video shows through here. Tapping it goes back to full screen.
            Box(Modifier.width(PREVIEW_WIDTH + PREVIEW_PADDING * 2).fillMaxHeight().tap(onClick = onClose))
        }

        // Header: current group (tap or Back for the group menu), clock, back to TV.
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(Palette.Background).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(timeFormat(now), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.width(CHANNEL_COLUMN - 16.dp))
            HeaderButton("☰  $filterLabel", onClick = onGroups)
            Spacer(Modifier.width(10.dp))
            Text("Back: groups, recordings & settings", fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, modifier = Modifier.weight(1f))
            HeaderButton("✕ Back to TV", onClick = onClose)
        }

        // Time ruler, with earlier / now / later buttons for touch and mouse.
        Row(Modifier.fillMaxWidth().height(26.dp).background(Palette.Background)) {
            Row(
                Modifier.width(CHANNEL_COLUMN).fillMaxHeight().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RulerButton("◀", Modifier.weight(1f)) { onShift(-GUIDE_STEP) }
                RulerButton("Now", Modifier.weight(1f), onClick = onNow)
                RulerButton("▶", Modifier.weight(1f)) { onShift(GUIDE_STEP) }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                val total = maxWidth
                for (i in 0 until (GUIDE_SPAN / GUIDE_STEP).toInt()) {
                    Text(
                        timeFormat(guideStart + i * GUIDE_STEP),
                        fontSize = 12.sp, color = Palette.TextDim,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = total * (i.toFloat() * GUIDE_STEP / GUIDE_SPAN) + 6.dp),
                    )
                }
            }
        }

        LazyColumn(
            state = state.listState,
            modifier = Modifier.fillMaxSize().background(Palette.Background),
        ) {
            itemsIndexed(channels, key = { _, ch -> ch.id }) { index, ch ->
                GuideRow(
                    channel = ch,
                    programs = ch.epgId?.let { programs[it] }.orEmpty(),
                    rowSelected = index == state.row,
                    focusTime = state.focusTime,
                    guideStart = guideStart,
                    now = now,
                    playing = ch.id == playingId,
                    favorite = ch.id in favorites,
                    recordingKeys = recordingKeys,
                    canCatchUp = { p -> canCatchUp(ch, p) },
                    onTune = { onTune(ch) },
                    onProgram = { p -> onProgram(ch, p) },
                    onMenu = { p -> onMenu(ch, p) },
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: ChannelEntity,
    programs: List<ProgramEntity>,
    rowSelected: Boolean,
    focusTime: Long,
    guideStart: Long,
    now: Long,
    playing: Boolean,
    favorite: Boolean,
    recordingKeys: Set<String>,
    canCatchUp: (ProgramEntity) -> Boolean,
    onTune: () -> Unit,
    onProgram: (ProgramEntity) -> Unit,
    onMenu: (ProgramEntity?) -> Unit,
) {
    val end = guideStart + GUIDE_SPAN
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT).padding(vertical = 1.dp)) {
        Row(
            Modifier
                .width(CHANNEL_COLUMN)
                .fillMaxHeight()
                .padding(end = 2.dp)
                .background(if (rowSelected) Palette.SurfaceLive else Palette.Surface)
                .tap(onLongClick = { onMenu(null) }, onClick = onTune)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(channel.number.toString(), fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.width(34.dp))
            ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 44.dp, height = 26.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                (if (playing) "▶ " else "") + channel.name + (if (favorite) " ★" else ""),
                fontSize = 13.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
            val total = maxWidth
            fun x(t: Long): Dp = total * ((t - guideStart).toFloat() / GUIDE_SPAN)

            val visible = programs.filter { it.stop > guideStart && it.start < end }
            if (visible.isEmpty()) {
                ProgramCell(
                    "No information",
                    Modifier.fillMaxSize().tap(onLongClick = { onMenu(null) }, onClick = onTune),
                    selected = rowSelected,
                    live = false,
                    recording = false,
                    catchup = false,
                )
            } else {
                for (p in visible) {
                    val s = maxOf(p.start, guideStart)
                    val e = minOf(p.stop, end)
                    ProgramCell(
                        title = (if (p.start < guideStart) "‹ " else "") + p.title,
                        modifier = Modifier
                            .offset(x = x(s))
                            .width(x(e) - x(s))
                            .fillMaxHeight()
                            .tap(onLongClick = { onMenu(p) }, onClick = { onProgram(p) }),
                        selected = rowSelected && p.start <= focusTime && focusTime < p.stop,
                        live = p.start <= now && now < p.stop,
                        recording = TvViewModel.recordingKey(channel.id, p.start) in recordingKeys,
                        catchup = p.stop <= now && canCatchUp(p),
                    )
                }
            }
            if (now in guideStart until end) {
                Box(Modifier.offset(x = x(now)).width(2.dp).fillMaxHeight().background(Palette.Accent))
            }
        }
    }
}

@Composable
private fun ProgramCell(title: String, modifier: Modifier, selected: Boolean, live: Boolean, recording: Boolean, catchup: Boolean) {
    Box(
        modifier
            .padding(end = 2.dp)
            .background(
                when {
                    selected -> Palette.Accent
                    live -> Palette.SurfaceLive
                    else -> Palette.Surface
                }
            )
            .then(if (selected) Modifier.border(2.dp, Color.White) else Modifier)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (recording) Text("● ", fontSize = 13.sp, color = Palette.Recording)
            if (catchup) Text("↺ ", fontSize = 13.sp, color = Palette.TextDim)
            Text(title, fontSize = 13.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HeaderButton(label: String, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, color = Palette.Text, maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.SurfaceLive)
            .tap(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun RulerButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .padding(horizontal = 2.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Palette.Surface)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, color = Palette.Text, maxLines = 1)
    }
}
