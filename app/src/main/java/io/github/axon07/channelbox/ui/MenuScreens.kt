package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import io.github.axon07.channelbox.data.RecStatus
import io.github.axon07.channelbox.data.RecordingEntity
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// ---- Groups menu (Back from the guide) ---------------------------------------------------------

/**
 * Left-hand menu over the guide: channel groups, then Recordings and Settings pinned at the bottom.
 * Index layout: 0 until filters.size = groups, filters.size = Recordings, filters.size + 1 = Settings.
 */
@Composable
fun GroupsPanel(
    filters: List<ChannelFilter>,
    active: ChannelFilter,
    selected: Int,
    listState: LazyListState,
    recordingCount: Int,
    onSelect: (Int) -> Unit,
) {
    LaunchedEffect(selected) {
        if (selected < filters.size) listState.scrollToItem((selected - 4).coerceAtLeast(0))
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(Palette.Panel)
            .tap { }
            .padding(vertical = 16.dp)
    ) {
        Text(
            "Groups", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            itemsIndexed(filters) { i, f ->
                MenuRow(
                    label = f.label,
                    trailing = if (f == active) "✓" else null,
                    selected = i == selected,
                    onClick = { onSelect(i) },
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(1.dp).background(Color(0x33FFFFFF)))
        MenuRow(
            label = "●  Recordings",
            trailing = if (recordingCount > 0) recordingCount.toString() else null,
            selected = selected == filters.size,
            onClick = { onSelect(filters.size) },
        )
        MenuRow(
            label = "⚙  Settings",
            trailing = null,
            selected = selected == filters.size + 1,
            onClick = { onSelect(filters.size + 1) },
        )
    }
}

@Composable
private fun MenuRow(label: String, trailing: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Palette.Accent else Color.Transparent)
            .tap(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 14.sp, color = Palette.TextDim)
    }
}

// ---- Recordings list ---------------------------------------------------------------------------

@Composable
fun RecordingsScreen(
    recordings: List<RecordingEntity>,
    selected: Int,
    listState: LazyListState,
    onPlay: (RecordingEntity) -> Unit,
    onMenu: (RecordingEntity) -> Unit,
) {
    LaunchedEffect(selected) {
        if (recordings.isNotEmpty()) listState.scrollToItem((selected - 3).coerceAtLeast(0))
    }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(horizontal = 40.dp, vertical = 20.dp)
    ) {
        Text("Recordings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        Text(
            "OK / tap to play · hold for options · Back to return",
            fontSize = 13.sp, color = Palette.TextDim,
        )
        Spacer(Modifier.height(12.dp))
        if (recordings.isEmpty()) {
            Text(
                "No recordings yet.\n\nWhile watching, press the remote's ● Record (or red) button, or tap the picture → Record.\n" +
                    "In the guide, open a programme (OK on a future show, or hold OK) and choose Record.",
                fontSize = 15.sp, color = Palette.TextDim,
            )
        }
        LazyColumn(state = listState, modifier = Modifier.widthIn(max = 760.dp)) {
            itemsIndexed(recordings, key = { _, r -> r.id }) { i, r ->
                val isSelected = i == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Palette.Accent else Palette.Surface)
                        .tap(onLongClick = { onMenu(r) }) { onPlay(r) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        statusIcon(r.status), fontSize = 18.sp, textAlign = TextAlign.Center,
                        color = if (r.status == RecStatus.RECORDING) Palette.Recording else Palette.Text,
                        modifier = Modifier.width(30.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(r.title, fontSize = 16.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                r.channelName,
                                dateFormat.format(Date(r.start)),
                                formatDuration(r.stop - r.start).takeIf { r.status == RecStatus.SCHEDULED },
                                formatSize(r.sizeBytes).takeIf { r.sizeBytes > 0 },
                            ).joinToString("  ·  "),
                            fontSize = 12.sp, color = if (isSelected) Palette.Text else Palette.TextDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        statusLabel(r), fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.End,
                        color = if (r.status == RecStatus.FAILED) Palette.Error else Palette.TextDim,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            }
        }
    }
}

private fun statusIcon(status: String) = when (status) {
    RecStatus.RECORDING -> "●"
    RecStatus.SCHEDULED -> "⏰"
    RecStatus.DONE -> "▶"
    RecStatus.FAILED -> "✕"
    else -> "–"
}

private fun statusLabel(r: RecordingEntity) = when (r.status) {
    RecStatus.RECORDING -> "Recording now"
    RecStatus.SCHEDULED -> "Scheduled"
    RecStatus.DONE -> if (r.error != null) "Partly recorded" else ""
    RecStatus.FAILED -> r.error ?: "Failed"
    RecStatus.CANCELLED -> "Cancelled"
    else -> r.status
}

fun formatDuration(ms: Long): String {
    val totalMinutes = (ms / 60_000L).coerceAtLeast(0)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h} h ${m.toString().padStart(2, '0')} min" else "$m min"
}

fun formatClock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1e6)
    else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
}

// ---- Playing a recording -----------------------------------------------------------------------

@Composable
fun BoxScope.PlaybackOverlay(
    title: String,
    position: Long,
    duration: Long,
    playing: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 20.dp)
    ) {
        Text(title, fontSize = 18.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        ProgressBar(if (duration > 0) position.toFloat() / duration else 0f, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${formatClock(position)} / ${if (duration > 0) formatClock(duration) else "--:--"}",
                fontSize = 14.sp, color = Palette.TextDim, modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("⏪ 10s") { onSeek(-10_000L) }
                Pill(if (playing) "⏸ Pause" else "▶ Play", onClick = onToggle)
                Pill("30s ⏩") { onSeek(30_000L) }
                Pill("✕ Close", onClick = onClose)
            }
        }
    }
}

// ---- Generic button dialog ---------------------------------------------------------------------

data class DialogAction(val label: String, val onClick: () -> Unit)

/** Centered dialog with a row of buttons; works with the remote (focus) and touch/mouse. */
@Composable
fun ActionDialog(title: String, subtitle: String?, actions: List<DialogAction>) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Panel)
                .tap { }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Text, textAlign = TextAlign.Center, maxLines = 2)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, fontSize = 14.sp, color = Palette.TextDim, textAlign = TextAlign.Center, maxLines = 2)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEachIndexed { i, a ->
                    Button(
                        onClick = a.onClick,
                        modifier = (if (i == 0) Modifier.focusRequester(first) else Modifier).tap(onClick = a.onClick),
                    ) { Text(a.label) }
                }
            }
        }
    }
}

// ---- Playback options (audio, subtitles, picture size) -----------------------------------------

val SCALE_LABELS = listOf("Fit", "Zoom (fill screen)", "Stretch")

@Composable
fun OptionsPanel(
    audio: List<io.github.axon07.channelbox.player.TrackOption>,
    subtitles: List<io.github.axon07.channelbox.player.TrackOption>,
    scale: Int,
    onAudio: (io.github.axon07.channelbox.player.TrackOption) -> Unit,
    onSubtitle: (io.github.axon07.channelbox.player.TrackOption) -> Unit,
    onScale: (Int) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(360.dp)
                .background(Palette.Panel)
                .tap { }
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            var firstUsed = false
            fun Modifier.firstFocus(): Modifier = if (!firstUsed) { firstUsed = true; this.focusRequester(first) } else this

            Section("Audio")
            if (audio.isEmpty()) Text("No audio tracks yet", fontSize = 13.sp, color = Palette.TextDim)
            audio.forEach { t -> OptionRow(t.label, t.selected, Modifier.firstFocus()) { onAudio(t) } }

            Section("Subtitles")
            subtitles.forEach { t -> OptionRow(t.label, t.selected, Modifier.firstFocus()) { onSubtitle(t) } }

            Section("Picture size")
            SCALE_LABELS.forEachIndexed { i, label -> OptionRow(label, i == scale, Modifier.firstFocus()) { onScale(i) } }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.TextDim,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    androidx.tv.material3.ListItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier.tap(onClick = onClick),
        headlineContent = { Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = { if (selected) Text("✓", fontSize = 14.sp) },
    )
}
