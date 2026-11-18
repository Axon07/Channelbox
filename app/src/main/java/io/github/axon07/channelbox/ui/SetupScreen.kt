package io.github.axon07.channelbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.axon07.channelbox.data.Playlist
import io.github.axon07.channelbox.data.RefreshState
import io.github.axon07.channelbox.data.SourceType
import kotlinx.coroutines.delay

/**
 * Add or edit one playlist: M3U URL or Xtream Codes login.
 * [initial] = null adds a new one; [firstRun] shows the welcome wording.
 */
@Composable
fun SetupScreen(
    initial: Playlist?,
    firstRun: Boolean,
    refreshState: RefreshState,
    vm: TvViewModel,
    onDone: () -> Unit,
    /** Opens Settings → Network & VPN (DNS, proxy, VPN, user agent). */
    onNetwork: () -> Unit,
) {
    val id = rememberSaveable { initial?.id ?: Playlist.newId() }
    var type by rememberSaveable {
        mutableStateOf(if (initial?.type == SourceType.XTREAM) SourceType.XTREAM else SourceType.M3U)
    }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var m3uUrl by rememberSaveable { mutableStateOf(initial?.m3uUrl.orEmpty()) }
    var server by rememberSaveable { mutableStateOf(initial?.xtServer.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(initial?.xtUser.orEmpty()) }
    var pass by rememberSaveable { mutableStateOf(initial?.xtPass.orEmpty()) }
    var epgUrl by rememberSaveable { mutableStateOf(initial?.epgUrl.orEmpty()) }
    var submitted by remember { mutableStateOf(false) }
    var sawRunning by remember { mutableStateOf(false) }

    // "Use your phone": a small web page on the home network fills in this form.
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    var fromPhone by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val link = io.github.axon07.channelbox.system.PhoneLink { f ->
            type = if (f["type"] == "xtream") SourceType.XTREAM else SourceType.M3U
            f["name"]?.takeIf { it.isNotBlank() }?.let { name = it.trim() }
            f["url"]?.let { m3uUrl = it.trim() }
            f["server"]?.let { server = it.trim() }
            f["user"]?.let { user = it.trim() }
            f["pass"]?.let { pass = it }
            f["epg"]?.let { epgUrl = it.trim() }
            fromPhone = true
        }
        phoneUrl = runCatching { link.start() }.getOrNull()
        onDispose { link.stop() }
    }

    // Only leave once *this* save has finished loading (not an older refresh result).
    LaunchedEffect(refreshState, submitted) {
        if (!submitted) return@LaunchedEffect
        if (refreshState is RefreshState.Running) sawRunning = true
        // Done, or only the TV guide is still loading: go to TV and let it finish in the background.
        if (sawRunning && (refreshState is RefreshState.Done || (refreshState as? RefreshState.Running)?.background == true)) onDone()
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(Palette.Background)) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 24.dp)
    ) {
        Text(
            when {
                firstRun -> "Set up your TV service"
                initial == null -> "Add a playlist"
                else -> "Edit ${initial.displayName}"
            },
            fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (firstRun) "Enter the details from your IPTV provider. Channelbox doesn't come with any channels. You can add more playlists later in Settings."
            else "Channels from all your playlists are shown together, and each playlist also gets its own entry in the groups menu.",
            fontSize = 14.sp, color = Palette.TextDim,
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TypeButton("Playlist link (M3U)", type == SourceType.M3U, Modifier.focusRequester(first)) { type = SourceType.M3U }
            TypeButton("Xtream Codes login", type == SourceType.XTREAM) { type = SourceType.XTREAM }
        }
        Spacer(Modifier.height(16.dp))

        Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TvTextField("Name – optional, e.g. \"Sports\" or the provider's name", name, { name = it })
            if (type == SourceType.M3U) {
                TvTextField("Playlist URL", m3uUrl, { m3uUrl = it }, keyboardType = KeyboardType.Uri)
            } else {
                TvTextField("Server (e.g. http://example.com:8080)", server, { server = it }, keyboardType = KeyboardType.Uri)
                TvTextField("Username", user, { user = it })
                TvTextField("Password", pass, { pass = it }, password = true)
            }
            TvTextField("TV guide (XMLTV) URL – optional, leave empty to use the provider's", epgUrl, { epgUrl = it }, keyboardType = KeyboardType.Uri)
        }
        Spacer(Modifier.height(18.dp))

        // Only this screen's own save counts as busy; a background reload is cancelled by saving.
        val busy = submitted && refreshState is RefreshState.Running
        val canSave = !busy && (if (type == SourceType.M3U) m3uUrl.isNotBlank() else server.isNotBlank() && user.isNotBlank())
        val save = {
            if (canSave) {
                submitted = true
                sawRunning = false
                vm.savePlaylist(
                    Playlist(
                        id = id, name = name, type = type, m3uUrl = m3uUrl, epgUrl = epgUrl,
                        xtServer = server, xtUser = user, xtPass = pass, enabled = initial?.enabled ?: true,
                        includeVod = initial?.includeVod ?: true,
                    )
                )
            }
        }
        // Sent from the phone: save straight away.
        LaunchedEffect(fromPhone) {
            if (fromPhone) {
                fromPhone = false
                save()
            }
        }
        Button(
            onClick = save,
            enabled = canSave,
            modifier = Modifier.tap(onClick = save),
        ) {
            Text(if (busy) "Loading…" else if (initial == null) "Add and load channels" else "Save and reload")
        }
        Spacer(Modifier.height(12.dp))
        val failed = submitted && refreshState is RefreshState.Failed
        when (refreshState) {
            is RefreshState.Running -> Text(refreshState.message, color = Palette.TextDim, fontSize = 14.sp)
            is RefreshState.Failed -> if (submitted) Text(refreshState.message, color = Palette.Error, fontSize = 14.sp)
            else -> Unit
        }
        Spacer(Modifier.height(20.dp))
        Text(
            if (failed) "Works in other apps or on other networks? Your internet provider may be blocking it – try another DNS, a proxy or a VPN, then save again."
            else "Internet provider blocking IPTV? Set up DNS, a proxy, a VPN or the user agent first.",
            fontSize = 13.sp, color = if (failed) Palette.Text else Palette.TextDim,
            modifier = Modifier.widthIn(max = 620.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onNetwork, modifier = Modifier.tap(onClick = onNetwork)) {
            Text("Network & VPN settings")
        }
    }
    PhonePanel(phoneUrl)
    }
}

/** QR code + address of the "add a playlist from your phone" page. */
@Composable
private fun PhonePanel(url: String?) {
    Column(
        Modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(Color(0xFF111821))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(AppIcons.Phone, tint = Palette.Text, size = 28.dp)
        Spacer(Modifier.height(8.dp))
        Text("Use your phone", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
        Spacer(Modifier.height(6.dp))
        if (url == null) {
            Text(
                "Connect this box to your home Wi-Fi or network to type the playlist on your phone instead.",
                fontSize = 13.sp, color = Palette.TextDim, textAlign = TextAlign.Center,
            )
            return@Column
        }
        Text(
            "Scan with your phone's camera and type the link there – much easier than with the remote.",
            fontSize = 13.sp, color = Palette.TextDim, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        val qr = remember(url) { io.github.axon07.channelbox.system.PhoneLink.qrCode(url).asImageBitmap() }
        androidx.compose.foundation.Image(
            bitmap = qr, contentDescription = "QR code",
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
            modifier = Modifier.size(220.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.height(12.dp))
        Text("or open", fontSize = 12.sp, color = Palette.TextDim)
        Text(url, fontSize = 13.sp, color = Palette.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Your phone must be on the same Wi-Fi. The page only works while this screen is open.",
            fontSize = 12.sp, color = Palette.TextDim, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TypeButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier.tap(onClick = onClick)) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.tap(onClick = onClick)) { Text(label) }
    }
}

@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    lines: Int = 1,
    /** Extra modifier for the input itself (e.g. a FocusRequester). */
    fieldModifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = Palette.TextDim)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = lines == 1,
            minLines = lines,
            maxLines = if (lines == 1) 1 else lines,
            textStyle = TextStyle(color = Color.White, fontSize = if (lines == 1) 16.sp else 12.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = if (lines == 1) ImeAction.Next else ImeAction.Default,
            ),
            modifier = fieldModifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) Palette.Accent else Color(0xFF2E3946), RoundedCornerShape(6.dp))
                .background(Palette.Surface, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
