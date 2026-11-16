package io.github.axon07.channelbox.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ListItem
import androidx.tv.material3.Text
import io.github.axon07.channelbox.BuildConfig
import io.github.axon07.channelbox.data.AppSettings
import io.github.axon07.channelbox.data.BackAction
import io.github.axon07.channelbox.data.refreshLabel
import io.github.axon07.channelbox.data.RefreshState
import io.github.axon07.channelbox.data.SourceType
import io.github.axon07.channelbox.data.redact
import io.github.axon07.channelbox.system.SetTopBox
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    settings: AppSettings,
    refreshState: RefreshState,
    channelCount: Int,
    vm: TvViewModel,
    onEditSource: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    // Re-read system state (permissions, launcher) whenever we come back to the screen.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val isHome = remember(resumeTick) { SetTopBox.isHomeScreen(context) }
    val canAutoStart = remember(resumeTick) { SetTopBox.canStartFromBackground(context) }

    val firstItem = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { firstItem.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 20.dp)
    ) {
        Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        Spacer(Modifier.height(12.dp))
        // A plain Column (not lazy) so the first row exists immediately and can take focus.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.widthIn(max = 620.dp)) {
            run {
                SettingRow(
                    title = "Playlist",
                    value = when (settings.sourceType) {
                        SourceType.M3U -> "M3U · ${redact(settings.m3uUrl)}"
                        SourceType.XTREAM -> "Xtream Codes · ${settings.xtServer} · ${settings.xtUser}"
                        SourceType.NONE -> "Not set up"
                    },
                    onClick = onEditSource,
                    modifier = Modifier.focusRequester(firstItem),
                )
            }
            run {
                val last = settings.lastRefresh.takeIf { it > 0 }
                    ?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }
                SettingRow(
                    title = "Reload channels and TV guide",
                    value = when (refreshState) {
                        is RefreshState.Running -> refreshState.message
                        is RefreshState.Failed -> "Failed: ${refreshState.message}"
                        is RefreshState.Done -> "$channelCount channels, ${refreshState.programmes} guide entries"
                        RefreshState.Idle -> last?.let { "$channelCount channels · last updated $it" } ?: "$channelCount channels"
                    },
                    onClick = { vm.refreshNow() },
                )
            }
            run {
                SettingRow(
                    title = "Reload channels and guide automatically",
                    value = refreshLabel(settings.refreshHours),
                    onClick = { vm.cycleRefreshHours(settings.refreshHours) },
                )
            }
            run {
                SettingRow(
                    title = "Back on the groups menu",
                    value = settings.backAction.label,
                    onClick = {
                        val all = BackAction.entries
                        vm.setBackAction(all[(all.indexOf(settings.backAction) + 1) % all.size])
                    },
                )
            }
            run {
                SettingRow(
                    title = "Start when the box turns on",
                    value = onOff(settings.startOnBoot),
                    onClick = { vm.setStartOnBoot(!settings.startOnBoot) },
                )
            }
            if (SetTopBox.needsOverlayPermission()) {
                run {
                    SettingRow(
                        title = "Permission for start on boot",
                        value = if (canAutoStart) "Granted" else "Needed: allow \"Display over other apps\"",
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
            }
            run {
                SettingRow(
                    title = "Decode all audio in software (FFmpeg)",
                    value = if (settings.preferSoftwareAudio) "On – try this if some channels have no sound"
                    else "Off – the box's own decoders are used, FFmpeg only when needed",
                    onClick = { vm.setPreferSoftwareAudio(!settings.preferSoftwareAudio) },
                )
            }
            run {
                SettingRow(
                    title = "Picture size",
                    value = SCALE_LABELS.getOrElse(settings.videoScale) { SCALE_LABELS[0] },
                    onClick = { vm.setVideoScale((settings.videoScale + 1) % SCALE_LABELS.size) },
                )
            }
            run {
                SettingRow(
                    title = "Use as home screen",
                    value = if (isHome) "On – press Home and choose Channelbox, \"Always\"" else "Off",
                    onClick = {
                        SetTopBox.setHomeScreen(context, !isHome)
                        resumeTick++
                    },
                )
            }
            run {
                SettingRow(
                    title = "Reverse up/down channel buttons",
                    value = onOff(settings.invertChannelKeys),
                    onClick = { vm.setInvertChannelKeys(!settings.invertChannelKeys) },
                )
            }
            run {
                val update by vm.update.collectAsStateWithLifecycle()
                SettingRow(
                    title = "Check for updates",
                    value = when (val u = update) {
                        TvViewModel.UpdateState.Checking -> "Checking…"
                        TvViewModel.UpdateState.UpToDate -> "You have the latest version (${vm.appVersion})"
                        is TvViewModel.UpdateState.Available -> "Version ${u.release.version} is available – select to update"
                        is TvViewModel.UpdateState.Downloading -> "Downloading version ${u.release.version}… ${u.percent}%"
                        is TvViewModel.UpdateState.Failed -> "Couldn't check: ${u.message}"
                        TvViewModel.UpdateState.Idle -> "Installed: version ${vm.appVersion}"
                    },
                    onClick = { vm.checkForUpdates(manual = true) },
                )
            }
            run {
                SettingRow(
                    title = "Check for updates when the app starts",
                    value = onOff(settings.autoUpdateCheck),
                    onClick = { vm.setAutoUpdateCheck(!settings.autoUpdateCheck) },
                )
            }
            run {
                SettingRow(
                    title = "Exit Channelbox",
                    value = "Close the app and stop playback",
                    onClick = onExit,
                )
            }
            run {
                SettingRow(
                    title = "About",
                    value = "Channelbox ${BuildConfig.VERSION_NAME} · free software, GPL-3.0",
                    onClick = {},
                )
            }
        }
    }
}

private fun onOff(on: Boolean) = if (on) "On" else "Off"

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier.tap(onClick = onClick),
        headlineContent = { Text(title, fontSize = 16.sp) },
        supportingContent = { Text(value, fontSize = 13.sp, maxLines = 2) },
    )
}
