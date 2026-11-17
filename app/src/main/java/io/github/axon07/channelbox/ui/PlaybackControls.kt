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
                    size = 76.dp, onClick = onToggle,
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
        val touch = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode ==
            androidx.compose.ui.input.InputMode.Touch
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000), Color(0xE6000000))))
                .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 14.dp)
        ) {
            Text(ui.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ui.subtitle?.let {
                Text(it, fontSize = 13.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
            }
            Spacer(Modifier.height(10.dp))
            // Seek bar with the times at either end.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(ui.position), fontSize = 12.sp, color = Color.White, modifier = Modifier.width(64.dp))
                Box(Modifier.weight(1f)) { Scrubber(ui, onSeekTo) }
                Text(
                    if (ui.duration > 0) "−" + formatClock((ui.duration - ui.position).coerceAtLeast(0)) else "",
                    fontSize = 12.sp, color = Palette.TextDim, textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.width(64.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            // Transport in the middle; captions and close on the right; remote hints on the left.
            Box(Modifier.fillMaxWidth()) {
                if (!touch) {
                    Text(
                        "OK pause · ◀ ▶ skip · Hold OK audio & subtitles",
                        fontSize = 11.sp, color = Palette.TextDim, maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTap(AppIcons.Rewind, size = 26.dp, badge = "10") { onSeek(-10_000L) }
                    IconTap(
                        if (ui.ended) AppIcons.Refresh else if (ui.playing) AppIcons.Pause else AppIcons.Play,
                        size = 38.dp, onClick = onToggle,
                    )
                    IconTap(AppIcons.Forward, size = 26.dp, badge = "30") { onSeek(30_000L) }
                }
                Row(
                    Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTap(AppIcons.Captions, size = 22.dp, onClick = onOptions)
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
            .height(18.dp)
            .tapAt { fraction -> if (ui.duration > 0) onSeekTo((fraction * ui.duration).toLong()) },
        contentAlignment = Alignment.CenterStart,
    ) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF)))
        Box(Modifier.fillMaxWidth(buffered).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF)))
        Box(Modifier.fillMaxWidth(played).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Accent))
        if (ui.duration > 0) {
            val thumb = 11.dp
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

/** A plain white icon you can tap; [badge] = small number under it (skip seconds). */
@Composable
internal fun IconTap(icon: ImageVector, size: Dp, badge: String? = null, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .tap(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(icon, tint = Color.White, size = size)
            if (badge != null) Text(badge, fontSize = 9.sp, color = Color(0xCCFFFFFF), fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Big centre button while paused or finished: a soft dark circle with a white icon. */
@Composable
internal fun RoundButton(
    icon: ImageVector,
    size: Dp,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0x80000000))
            .border(1.dp, Color(0x40FFFFFF), CircleShape)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(icon, tint = Color.White, size = size * 0.42f)
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

/**
 * Live TV controls (tap the picture): same look as the movie player – channel and programme,
 * the programme's progress, pause / record / live in the middle, small icons on the right.
 */
@Composable
fun BoxScope.LiveControls(
    channel: io.github.axon07.channelbox.data.ChannelEntity,
    program: io.github.axon07.channelbox.data.ProgramEntity?,
    now: Long,
    timeFormat: (Long) -> String,
    favorite: Boolean,
    recording: Boolean,
    paused: Boolean,
    behindLive: Boolean,
    onPause: () -> Unit,
    onLive: () -> Unit,
    onRecord: () -> Unit,
    onFavorite: () -> Unit,
    onOptions: () -> Unit,
    onPip: (() -> Unit)?,
    onMultiView: () -> Unit,
) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000), Color(0xE6000000))))
            .tap { } // swallow taps between the buttons
            .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.logoUrl, channel.name, Modifier.size(width = 52.dp, height = 30.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    program?.title ?: channel.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${channel.number}  ${channel.name}", fontSize = 13.sp, color = Palette.TextDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // How far into the programme we are.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(program?.let { timeFormat(it.start) } ?: "", fontSize = 12.sp, color = Color.White, modifier = Modifier.width(72.dp))
            val fraction = program?.let { ((now - it.start).toFloat() / (it.stop - it.start).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
            Box(Modifier.weight(1f).height(18.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF)))
                Box(Modifier.fillMaxWidth(fraction).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Accent))
            }
            Text(
                program?.let { timeFormat(it.stop) } ?: "", fontSize = 12.sp, color = Palette.TextDim,
                textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(72.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier.align(Alignment.CenterStart),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTapTint(if (favorite) AppIcons.Star else AppIcons.StarOutline, if (favorite) Palette.Favorite else Color.White, onFavorite)
            }
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTapTint(if (recording) AppIcons.Stop else AppIcons.Record, Palette.Recording, onRecord, size = 24.dp)
                IconTap(if (paused) AppIcons.Play else AppIcons.Pause, size = 38.dp, onClick = onPause)
                if (behindLive) IconTap(AppIcons.Forward, size = 26.dp, badge = "LIVE", onClick = onLive)
                else Spacer(Modifier.width(38.dp))
            }
            Row(
                Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTap(AppIcons.Captions, size = 22.dp, onClick = onOptions)
                IconTap(AppIcons.Grid, size = 22.dp, onClick = onMultiView)
                if (onPip != null) IconTap(AppIcons.Pip, size = 22.dp, onClick = onPip)
            }
        }
    }
}

@Composable
private fun IconTapTint(icon: ImageVector, tint: Color, onClick: () -> Unit, size: Dp = 22.dp) {
    Box(Modifier.clip(CircleShape).tap(onClick = onClick).padding(6.dp), contentAlignment = Alignment.Center) {
        AppIcon(icon, tint = tint, size = size)
    }
}
