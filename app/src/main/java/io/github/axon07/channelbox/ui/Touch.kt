package io.github.axon07.channelbox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Tap / long-press for touch screens and mice. Pointer only, so it never
 * doubles up with the remote's OK button, which the TV components already handle.
 */
fun Modifier.tap(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    pointerInput(Unit) {
        detectTapGestures(
            onLongPress = { longClick?.invoke() },
            onTap = { click() },
        )
    }
}

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    iconTint: Color = Palette.Text,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (highlighted) Palette.Accent else Color(0xE61F2A37))
            .tap(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AppIcon(icon, tint = iconTint, size = 16.dp)
            if (text.isNotEmpty()) androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 7.dp))
        }
        if (text.isNotEmpty()) Text(text, fontSize = 14.sp, color = Palette.Text, maxLines = 1)
    }
}

/** On-screen buttons shown when the video is tapped (phones, tablets, mouse users). */
@Composable
fun BoxScope.TouchBar(
    favorite: Boolean,
    recording: Boolean,
    onChannels: () -> Unit,
    onGuide: () -> Unit,
    onChannelUp: () -> Unit,
    onChannelDown: () -> Unit,
    onFavorite: () -> Unit,
    onRecord: () -> Unit,
    onCaptions: () -> Unit,
    onOptions: () -> Unit,
    onPip: (() -> Unit)?,
    onMultiView: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
) {
    Row(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color.Transparent)))
            .tap { } // swallow taps between the buttons
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Pill("Channels", icon = AppIcons.List, onClick = onChannels)
        Pill("Guide", icon = AppIcons.Guide, onClick = onGuide)
        Pill("CH+", icon = AppIcons.Up, onClick = onChannelUp)
        Pill("CH−", icon = AppIcons.Down, onClick = onChannelDown)
        Pill(
            "Favourite", icon = if (favorite) AppIcons.Star else AppIcons.StarOutline,
            iconTint = if (favorite) Palette.Favorite else Palette.Text, highlighted = favorite, onClick = onFavorite,
        )
        Pill(
            if (recording) "Stop" else "Record", icon = if (recording) AppIcons.Stop else AppIcons.Record,
            iconTint = Palette.Recording, highlighted = recording, onClick = onRecord,
        )
        Pill("CC", icon = AppIcons.Captions, onClick = onCaptions)
        Pill("Audio & quality", icon = AppIcons.Audio, onClick = onOptions)
        if (onPip != null) Pill("Mini player", icon = AppIcons.Pip, onClick = onPip)
        Pill("Multi-view", icon = AppIcons.Grid, onClick = onMultiView)
        Pill("Settings", icon = AppIcons.Settings, onClick = onSettings)
        Pill("Exit", icon = AppIcons.Exit, onClick = onExit)
    }
}
