package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
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
 * Left-hand menu over the guide: channel groups, then three tiles at the bottom.
 * Index layout: 0 until filters.size = groups, then +0 Multi-view, +1 Recordings, +2 Settings.
 */
@Composable
fun GroupsPanel(
    filters: List<ChannelFilter>,
    counts: Map<ChannelFilter, Int>,
    active: ChannelFilter,
    selected: Int,
    listState: LazyListState,
    recordingCount: Int,
    movieCount: Int,
    showCount: Int,
    onSelect: (Int) -> Unit,
) {
    LaunchedEffect(selected) {
        if (selected < filters.size) listState.scrollToItem((selected - 3).coerceAtLeast(0))
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(340.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xFA0F151D), Color(0xF20F151D), Color(0xE00F151D))))
            .tap { }
            .padding(top = 24.dp, bottom = 16.dp, start = 14.dp, end = 14.dp)
    ) {
        Text(
            "Channels", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            "${counts[ChannelFilter.All] ?: 0} channels · ${filters.count { it is ChannelFilter.Group }} groups",
            fontSize = 12.sp, color = Palette.TextDim,
            modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 12.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            itemsIndexed(filters) { i, f ->
                GroupRow(
                    filter = f,
                    count = counts[f],
                    active = f == active,
                    selected = i == selected,
                    onClick = { onSelect(i) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        // Movies & TV Shows (when the playlists have any), then the shortcuts.
        if (movieCount > 0 || showCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (movieCount > 0) LibraryTile("Movies", movieCount, AppIcons.Film, selected == filters.size + 3, Modifier.weight(1f)) {
                    onSelect(filters.size + 3)
                }
                if (showCount > 0) LibraryTile("TV Shows", showCount, AppIcons.Tv, selected == filters.size + 4, Modifier.weight(1f)) {
                    onSelect(filters.size + 4)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickTile("Multi-view", AppIcons.Grid, selected == filters.size, Modifier.weight(1f)) {
                onSelect(filters.size)
            }
            QuickTile(
                if (recordingCount > 0) "Recordings · $recordingCount" else "Recordings",
                AppIcons.Record, selected == filters.size + 1, Modifier.weight(1f),
            ) { onSelect(filters.size + 1) }
            QuickTile("Settings", AppIcons.Settings, selected == filters.size + 2, Modifier.weight(1f)) {
                onSelect(filters.size + 2)
            }
        }
    }
}

@Composable
private fun GroupRow(
    filter: ChannelFilter,
    count: Int?,
    active: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x29FFFFFF) else Color.Transparent)
            .border(if (selected) 2.dp else 0.dp, if (selected) Palette.Accent else Color.Transparent, RoundedCornerShape(14.dp))
            .tap(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (filter) {
            ChannelFilter.All -> IconBadge(AppIcons.List, highlight = selected)
            ChannelFilter.Favorites -> IconBadge(AppIcons.Star, highlight = selected)
            is ChannelFilter.Source -> IconBadge(AppIcons.Globe, highlight = selected)
            is ChannelFilter.Group ->
                if (filter.name == ChannelFilter.UNGROUPED) IconBadge(AppIcons.Folder, highlight = selected)
                else LetterBadge(filter.label, selected)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            filter.label,
            fontSize = 15.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) Color(0xFF8FBBFF) else Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                count.toString(), fontSize = 12.sp, color = Palette.TextDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x1AFFFFFF))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** First letter of a group on a coloured tile. */
@Composable
private fun LetterBadge(label: String, highlight: Boolean) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (highlight) Color(0x26FFFFFF) else Color(0x14FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (highlight) Color.White else Color(0xFFAEB8C4),
        )
    }
}

@Composable
private fun LibraryTile(
    label: String,
    count: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x29FFFFFF) else Color(0x12FFFFFF))
            .border(if (selected) 2.dp else 0.dp, if (selected) Palette.Accent else Color.Transparent, RoundedCornerShape(14.dp))
            .tap(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, size = 32.dp, highlight = selected)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1)
            Text(count.toString(), fontSize = 11.sp, color = Palette.TextDim, maxLines = 1)
        }
    }
}

@Composable
private fun QuickTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x29FFFFFF) else Color(0x12FFFFFF))
            .border(if (selected) 2.dp else 0.dp, if (selected) Palette.Accent else Color.Transparent, RoundedCornerShape(14.dp))
            .tap(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, size = 32.dp, highlight = selected)
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Pill("10 s", icon = AppIcons.Rewind) { onSeek(-10_000L) }
                Pill(if (playing) "Pause" else "Play", icon = if (playing) AppIcons.Pause else AppIcons.Play, onClick = onToggle)
                Pill("30 s", icon = AppIcons.Forward) { onSeek(30_000L) }
                Pill("Close", icon = AppIcons.Close, onClick = onClose)
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
    quality: List<io.github.axon07.channelbox.player.TrackOption>,
    onQuality: (io.github.axon07.channelbox.player.TrackOption) -> Unit,
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

            if (quality.size > 1) {
                Section("Quality")
                quality.forEach { t -> OptionRow(t.label, t.selected, Modifier.firstFocus()) { onQuality(t) } }
            }

            Section("Audio")
            if (audio.isEmpty()) Text("No audio tracks yet", fontSize = 13.sp, color = Palette.TextDim)
            audio.forEach { t -> OptionRow(t.label, t.selected, Modifier.firstFocus()) { onAudio(t) } }

            Section("Subtitles & closed captions")
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
