package io.github.axon07.channelbox.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import io.github.axon07.channelbox.data.AppSettings
import io.github.axon07.channelbox.data.BackAction
import io.github.axon07.channelbox.data.RefreshState
import io.github.axon07.channelbox.data.SourceType
import io.github.axon07.channelbox.data.redact
import io.github.axon07.channelbox.data.refreshLabel
import io.github.axon07.channelbox.system.SetTopBox
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private enum class SettingsCategory(val icon: String, val title: String, val blurb: String) {
    SOURCE("☰", "Playlist & guide", "Your TV service and programme guide"),
    PLAYBACK("▶", "Playback", "Picture, sound and the remote"),
    RECORDINGS("●", "Recordings", "Where recordings are kept"),
    STARTUP("⏻", "Start-up & exit", "Make it behave like a cable box"),
    UPDATES("↻", "Updates", "Keep Channelbox up to date"),
    ABOUT("ⓘ", "About", "Version and licence"),
}

/**
 * Two panes: categories on the left, that category's settings on the right.
 * Moving through the categories with the remote shows each one; ▶ goes into its settings.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    refreshState: RefreshState,
    channelCount: Int,
    vm: TvViewModel,
    onEditSource: () -> Unit,
    onOpenRecordings: () -> Unit,
    onExit: () -> Unit,
) {
    var category by rememberSaveable { mutableStateOf(SettingsCategory.SOURCE) }
    val firstCategory = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstCategory.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(Palette.Background)) {
        // ---- Categories ----
        Column(
            Modifier
                .width(290.dp)
                .fillMaxHeight()
                .background(Color(0xFF0E131A))
                .padding(top = 30.dp, start = 18.dp, end = 18.dp, bottom = 18.dp)
        ) {
            Text(
                "Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
                modifier = Modifier.padding(start = 10.dp),
            )
            Text(
                "Channelbox ${vm.appVersion}", fontSize = 12.sp, color = Palette.TextDim,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 18.dp),
            )
            SettingsCategory.entries.forEachIndexed { i, c ->
                CategoryRow(
                    category = c,
                    selected = c == category,
                    modifier = if (i == 0) Modifier.focusRequester(firstCategory) else Modifier,
                    onFocus = { category = c },
                )
            }
        }

        // ---- Settings in the chosen category ----
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(top = 30.dp, start = 36.dp, end = 40.dp, bottom = 24.dp)
        ) {
            Text(category.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            Text(category.blurb, fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (category) {
                    SettingsCategory.SOURCE -> SourceSettings(settings, refreshState, channelCount, vm, onEditSource)
                    SettingsCategory.PLAYBACK -> PlaybackSettings(settings, vm)
                    SettingsCategory.RECORDINGS -> RecordingSettings(vm, onOpenRecordings)
                    SettingsCategory.STARTUP -> StartupSettings(settings, vm, onExit)
                    SettingsCategory.UPDATES -> UpdateSettings(settings, vm)
                    SettingsCategory.ABOUT -> AboutSettings(vm)
                }
            }
        }
    }
}

// ---- Categories ------------------------------------------------------------------------------------

@Composable
private fun SourceSettings(
    settings: AppSettings,
    refreshState: RefreshState,
    channelCount: Int,
    vm: TvViewModel,
    onEditSource: () -> Unit,
) {
    SettingCard(
        title = "TV service",
        description = when (settings.sourceType) {
            SourceType.M3U -> "M3U playlist · ${redact(settings.m3uUrl)}"
            SourceType.XTREAM -> "Xtream Codes · ${settings.xtServer} · ${settings.xtUser}"
            SourceType.NONE -> "Not set up yet"
        },
        value = "Change",
        onClick = onEditSource,
    )
    val last = settings.lastRefresh.takeIf { it > 0 }
        ?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
    SettingCard(
        title = "Reload channels and guide now",
        description = when (refreshState) {
            is RefreshState.Running -> refreshState.message
            is RefreshState.Failed -> "Last attempt failed: ${refreshState.message}"
            is RefreshState.Done -> "$channelCount channels · ${refreshState.programmes} guide entries"
            RefreshState.Idle -> "$channelCount channels" + (last?.let { " · updated $it" } ?: "")
        },
        value = if (refreshState is RefreshState.Running) "Loading…" else "Reload",
        onClick = { vm.refreshNow() },
    )
    SettingCard(
        title = "Reload automatically",
        description = "Fetch the latest channels and guide in the background",
        value = refreshLabel(settings.refreshHours).substringBefore(" ("),
        onClick = { vm.cycleRefreshHours(settings.refreshHours) },
    )
}

@Composable
private fun PlaybackSettings(settings: AppSettings, vm: TvViewModel) {
    SettingCard(
        title = "Picture size",
        description = "How the picture fills the screen. Also available while watching (hold OK).",
        value = SCALE_LABELS.getOrElse(settings.videoScale) { SCALE_LABELS[0] },
        onClick = { vm.setVideoScale((settings.videoScale + 1) % SCALE_LABELS.size) },
    )
    SettingCard(
        title = "Decode all audio in software",
        description = "Try this if some channels play without sound. Takes effect after restarting the app.",
        toggle = settings.preferSoftwareAudio,
        onClick = { vm.setPreferSoftwareAudio(!settings.preferSoftwareAudio) },
    )
    SettingCard(
        title = "Reverse channel up / down",
        description = "Swap which way ▲ and ▼ change the channel",
        toggle = settings.invertChannelKeys,
        onClick = { vm.setInvertChannelKeys(!settings.invertChannelKeys) },
    )
}

@Composable
private fun RecordingSettings(vm: TvViewModel, onOpenRecordings: () -> Unit) {
    val folder = remember { vm.recordingsFolder }
    SettingCard(
        title = "Recordings",
        description = "Watch, stop or delete your recordings",
        value = "Open",
        onClick = onOpenRecordings,
    )
    SettingCard(
        title = "Storage",
        description = "Saved on this box in Android/data/…/files/recordings",
        value = "${formatSize(folder.usableSpace)} free",
        onClick = {},
    )
}

@Composable
private fun StartupSettings(settings: AppSettings, vm: TvViewModel, onExit: () -> Unit) {
    val context = LocalContext.current
    // Re-read system state (permissions, launcher) whenever we come back to the screen.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val isHome = remember(resumeTick) { SetTopBox.isHomeScreen(context) }
    val canAutoStart = remember(resumeTick) { SetTopBox.canStartFromBackground(context) }

    SettingCard(
        title = "Start when the box turns on",
        description = "Go straight to live TV after a power cut or restart",
        toggle = settings.startOnBoot,
        onClick = { vm.setStartOnBoot(!settings.startOnBoot) },
    )
    if (SetTopBox.needsOverlayPermission()) {
        SettingCard(
            title = "Permission to start on boot",
            description = if (canAutoStart) "Granted" else "Android needs \"Display over other apps\" allowed for this",
            value = if (canAutoStart) "✓" else "Allow",
            onClick = {
                if (!SetTopBox.openOverlaySettings(context)) {
                    Toast.makeText(
                        context,
                        "This box hides that setting. Grant it from a computer:\n" + SetTopBox.adbGrantCommand(context),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }
    SettingCard(
        title = "Use as home screen",
        description = if (isHome) "Press Home and choose Channelbox → Always" else "Home button opens live TV instead of the Android home screen",
        toggle = isHome,
        onClick = {
            SetTopBox.setHomeScreen(context, !isHome)
            resumeTick++
        },
    )
    SettingCard(
        title = "Back button on the groups menu",
        description = "What happens on the last Back press (TV → guide → groups → …)",
        value = settings.backAction.label,
        onClick = {
            val all = BackAction.entries
            vm.setBackAction(all[(all.indexOf(settings.backAction) + 1) % all.size])
        },
    )
    SettingCard(
        title = "Exit Channelbox",
        description = "Close the app and stop playback",
        value = "Exit",
        onClick = onExit,
    )
}

@Composable
private fun UpdateSettings(settings: AppSettings, vm: TvViewModel) {
    val update by vm.update.collectAsStateWithLifecycle()
    SettingCard(
        title = "Check for updates",
        description = when (val u = update) {
            TvViewModel.UpdateState.Checking -> "Checking…"
            TvViewModel.UpdateState.UpToDate -> "You have the latest version (${vm.appVersion})"
            is TvViewModel.UpdateState.Available -> "Version ${u.release.version} is available"
            is TvViewModel.UpdateState.Downloading -> "Downloading version ${u.release.version}… ${u.percent}%"
            is TvViewModel.UpdateState.Failed -> "Couldn't check: ${u.message}"
            TvViewModel.UpdateState.Idle -> "Installed: version ${vm.appVersion}"
        },
        value = if (update is TvViewModel.UpdateState.Available) "Update" else "Check",
        onClick = { vm.checkForUpdates(manual = true) },
    )
    SettingCard(
        title = "Check when the app starts",
        description = "Offer new versions automatically",
        toggle = settings.autoUpdateCheck,
        onClick = { vm.setAutoUpdateCheck(!settings.autoUpdateCheck) },
    )
}

@Composable
private fun AboutSettings(vm: TvViewModel) {
    SettingCard(title = "Channelbox", description = "Version ${vm.appVersion}", onClick = {})
    SettingCard(
        title = "Free & open source",
        description = "GPL-3.0 licence · github.com/Axon07/Channelbox",
        onClick = {},
    )
    SettingCard(
        title = "Channels",
        description = "Channelbox is only a player – channels come from your own TV service",
        onClick = {},
    )
}

// ---- Building blocks -------------------------------------------------------------------------------

@Composable
private fun CategoryRow(category: SettingsCategory, selected: Boolean, modifier: Modifier, onFocus: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onFocus,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .tap(onClick = onFocus),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0x1FFFFFFF) else Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Accent,
            focusedContentColor = Color.White,
            pressedContainerColor = Palette.Accent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                category.icon, fontSize = 15.sp,
                color = if (focused) Color.White else if (category == SettingsCategory.RECORDINGS) Palette.Recording else Palette.TextDim,
                modifier = Modifier.width(26.dp),
            )
            Text(
                category.title, fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One setting: title and description on the left, and either a value ("Every 12 hours ›")
 * or an on/off switch on the right.
 */
@Composable
private fun SettingCard(
    title: String,
    description: String? = null,
    value: String? = null,
    toggle: Boolean? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .tap(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.SurfaceLive,
            focusedContentColor = Palette.Text,
            pressedContainerColor = Palette.SurfaceLive,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, Palette.Accent),
                shape = RoundedCornerShape(12.dp),
            ),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.Text)
                if (description != null) {
                    Text(
                        description, fontSize = 12.sp, color = Palette.TextDim, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            when {
                toggle != null -> ToggleSwitch(toggle)
                value != null -> Text(
                    "$value  ›", fontSize = 13.sp, maxLines = 1,
                    color = if (focused) Palette.Text else Palette.TextDim,
                )
            }
        }
    }
}

@Composable
private fun ToggleSwitch(on: Boolean) {
    Box(
        Modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) Palette.Accent else Color(0xFF3A4554))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(Color.White))
    }
}
