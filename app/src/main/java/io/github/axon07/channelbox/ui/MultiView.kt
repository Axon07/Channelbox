package io.github.axon07.channelbox.ui

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import io.github.axon07.channelbox.data.ChannelEntity
import io.github.axon07.channelbox.data.StreamSpecs
import io.github.axon07.channelbox.player.TvPlayer

/**
 * Watch 2 or 4 channels at once. Sound comes from the highlighted tile.
 * How many work smoothly depends on the box's video decoders (most handle 2, many handle 4 at HD).
 */
class MultiViewState {
    var tiles by mutableIntStateOf(4)
    var focused by mutableIntStateOf(0)
    var version by mutableIntStateOf(0) // bumped when a tile's player changes
    val channels = mutableStateListOf<ChannelEntity?>(null, null, null, null)
    private val players = arrayOfNulls<TvPlayer>(4)

    fun player(i: Int): TvPlayer? = players[i]

    fun start(vm: TvViewModel, first: ChannelEntity?) {
        vm.player.stop() // frees a decoder for the tiles
        focused = 0
        if (first != null) assign(vm, 0, first)
    }

    fun assign(vm: TvViewModel, tile: Int, channel: ChannelEntity) {
        channels[tile] = channel
        val p = players[tile] ?: vm.newTilePlayer().also { players[tile] = it; version++ }
        p.play(StreamSpecs.from(channel))
        updateVolumes()
    }

    fun setTileCount(count: Int) {
        tiles = count
        for (i in count until 4) {
            players[i]?.release()
            players[i] = null
            channels[i] = null
        }
        focused = focused.coerceAtMost(count - 1)
        version++
        updateVolumes()
    }

    /** Moves the highlight with the D-pad (2 tiles side by side, or a 2×2 grid). */
    fun move(dx: Int, dy: Int) {
        val cols = 2
        val rows = if (tiles == 4) 2 else 1
        val col = (focused % cols + dx).coerceIn(0, cols - 1)
        val row = (focused / cols + dy).coerceIn(0, rows - 1)
        focused = row * cols + col
        updateVolumes()
    }

    fun select(tile: Int) {
        focused = tile
        updateVolumes()
    }

    private fun updateVolumes() {
        players.forEachIndexed { i, p -> p?.exo?.volume = if (i == focused) 1f else 0f }
    }

    fun release() {
        players.forEachIndexed { i, p ->
            p?.release()
            players[i] = null
        }
        for (i in 0 until 4) channels[i] = null
        version++
    }
}

@OptIn(UnstableApi::class)
@Composable
fun MultiViewScreen(state: MultiViewState, onTileTap: (Int) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = state.version // recompose when players change
    val rows = if (state.tiles == 4) 2 else 1
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (r in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until 2) {
                    val i = r * 2 + c
                    val focused = state.focused == i
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Palette.Surface)
                            .border(if (focused) 3.dp else 0.dp, if (focused) Palette.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                            .tap { onTileTap(i) },
                    ) {
                        val tilePlayer = state.player(i)
                        if (tilePlayer != null) {
                            key(i, state.version) {
                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            useController = false
                                            isFocusable = false
                                            isClickable = false
                                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                                            player = tilePlayer.exo
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize().padding(3.dp),
                                )
                            }
                        }
                        val ch = state.channels[i]
                        Text(
                            ch?.let { "${it.number}  ${it.name}" } ?: "Empty – press OK to pick a channel",
                            fontSize = 13.sp,
                            color = Palette.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(if (ch == null) Alignment.Center else Alignment.TopStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xB3000000))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        if (focused && ch != null) {
                            AppIcon(
                                AppIcons.Audio, tint = Palette.Text, size = 16.dp,
                                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
        Text(
            "OK: pick channel · Hold OK: watch full screen · Menu: ${if (state.tiles == 4) "2" else "4"} tiles · Back: exit",
            fontSize = 12.sp, color = Palette.TextDim,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}
