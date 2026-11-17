package io.github.axon07.channelbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** Everything the player bar shows. */
data class PlaybackUi(
    val title: String,
    val subtitle: String? = null,
    val position: Long,
    val duration: Long,
    val buffered: Long,
    val playing: Boolean,
    val buffering: Boolean,
    val ended: Boolean,
    /** Seconds just skipped (shown briefly as "−10 s" / "+30 s"), 0 = none. */
    val skipped: Long = 0,
)

/**
 * Player bar for movies, episodes, recordings and catch-up. Remote: OK play/pause, ◀ ▶ skip,
 * hold OK for audio & subtitles, Back to close. Touch/mouse: tap the buttons.
 */
@Composable
fun BoxScope.PlaybackControls(
    ui: PlaybackUi,
    visible: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onOptions: () -> Unit,
    onClose: () -> Unit,
) {
    // Big centre button while paused, finished or loading.
    if (visible || !ui.playing || ui.buffering) {
        Box(Modifier.align(Alignment.Center)) {
            when {
                ui.buffering && ui.playing -> LoadingRing()
                !ui.playing || ui.ended -> RoundButton(
                    icon = if (ui.ended) AppIcons.Refresh else AppIcons.Play,
                    size = 88.dp, accent = true, onClick = onToggle,
                )
            }
        }
    }
    // Skip feedback in the middle-left / middle-right.
    if (ui.skipped != 0L) {
        Box(Modifier.align(if (ui.skipped < 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 80.dp)) {
            Text(
                (if (ui.skipped < 0) "−" else "+") + "${kotlin.math.abs(ui.skipped) / 1000} s",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x99000000)).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }

    AnimatedVisibility(
        visible = visible || !ui.playing,
        enter = fadeIn(), exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000), Color(0xF0000000))))
                .padding(start = 40.dp, end = 40.dp, top = 56.dp, bottom = 24.dp)
        ) {
            Text(ui.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ui.subtitle?.let {
                Text(it, fontSize = 14.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(18.dp))
            Scrubber(ui, onSeekTo)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(ui.position), fontSize = 14.sp, color = Color.White)
                Spacer(Modifier.weight(1f))
                Text(
                    if (ui.duration > 0) "−" + formatClock((ui.duration - ui.position).coerceAtLeast(0)) else "--:--",
                    fontSize = 14.sp, color = Palette.TextDim,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "OK  play / pause    ◀ ▶  skip    Hold OK  audio & subtitles",
                    fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundButton(AppIcons.Rewind, label = "10", size = 46.dp) { onSeek(-10_000L) }
                    RoundButton(
                        if (ui.ended) AppIcons.Refresh else if (ui.playing) AppIcons.Pause else AppIcons.Play,
                        size = 58.dp, accent = true, onClick = onToggle,
                    )
                    RoundButton(AppIcons.Forward, label = "30", size = 46.dp) { onSeek(30_000L) }
                    Spacer(Modifier.width(10.dp))
                    RoundButton(AppIcons.Captions, size = 46.dp, onClick = onOptions)
                    RoundButton(AppIcons.Close, size = 46.dp, onClick = onClose)
                }
            }
        }
    }
}

@Composable
private fun Scrubber(ui: PlaybackUi, onSeekTo: (Long) -> Unit) {
    val dur = ui.duration.coerceAtLeast(1)
    val played = if (ui.duration > 0) (ui.position.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val buffered = if (ui.duration > 0) (ui.buffered.toFloat() / dur).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .tapAt { fraction -> if (ui.duration > 0) onSeekTo((fraction * ui.duration).toLong()) },
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF)))
        Box(Modifier.fillMaxWidth(buffered).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x66FFFFFF)))
        Box(Modifier.fillMaxWidth(played).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Accent))
        if (ui.duration > 0) {
            val thumb = 16.dp
            Box(
                Modifier
                    .offset(x = (width - thumb) * played)
                    .size(thumb)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    size: Dp,
    accent: Boolean = false,
    label: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (accent) Palette.Accent else Color(0x33FFFFFF))
            .border(1.dp, if (accent) Color.Transparent else Color(0x33FFFFFF), CircleShape)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(icon, tint = Color.White, size = size * 0.36f)
                Text(label, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        } else {
            AppIcon(icon, tint = Color.White, size = size * 0.46f)
        }
    }
}

@Composable
private fun LoadingRing() {
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
        val angle by spin.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)
            ),
            label = "angle",
        )
        androidx.compose.foundation.Canvas(Modifier.size(40.dp)) {
            drawArc(
                color = Color.White, startAngle = angle, sweepAngle = 270f, useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
    }
}
