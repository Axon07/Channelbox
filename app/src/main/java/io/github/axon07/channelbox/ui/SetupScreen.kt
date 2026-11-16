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
import androidx.tv.material3.Button
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.axon07.channelbox.data.AppSettings
import io.github.axon07.channelbox.data.RefreshState
import io.github.axon07.channelbox.data.SourceType
import kotlinx.coroutines.delay

/** First-run screen (and Settings → Playlist): M3U URL or Xtream Codes login. */
@Composable
fun SetupScreen(
    initial: AppSettings,
    refreshState: RefreshState,
    vm: TvViewModel,
    onDone: () -> Unit,
) {
    var type by rememberSaveable {
        mutableStateOf(if (initial.sourceType == SourceType.XTREAM) SourceType.XTREAM else SourceType.M3U)
    }
    var m3uUrl by rememberSaveable { mutableStateOf(initial.m3uUrl) }
    var server by rememberSaveable { mutableStateOf(initial.xtServer) }
    var user by rememberSaveable { mutableStateOf(initial.xtUser) }
    var pass by rememberSaveable { mutableStateOf(initial.xtPass) }
    var epgUrl by rememberSaveable { mutableStateOf(initial.epgUrl) }
    var submitted by remember { mutableStateOf(false) }
    var sawRunning by remember { mutableStateOf(false) }

    // Only leave once *this* save has finished loading (not an older refresh result).
    LaunchedEffect(refreshState, submitted) {
        if (!submitted) return@LaunchedEffect
        if (refreshState is RefreshState.Running) sawRunning = true
        if (sawRunning && refreshState is RefreshState.Done) onDone()
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { first.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 24.dp)
    ) {
        Text("Set up your TV service", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        Spacer(Modifier.height(4.dp))
        Text(
            "Enter the details from your IPTV provider. Channelbox doesn't come with any channels.",
            fontSize = 14.sp, color = Palette.TextDim,
        )
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TypeButton("Playlist link (M3U)", type == SourceType.M3U, Modifier.focusRequester(first)) { type = SourceType.M3U }
            TypeButton("Xtream Codes login", type == SourceType.XTREAM) { type = SourceType.XTREAM }
        }
        Spacer(Modifier.height(16.dp))

        Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

        val busy = refreshState is RefreshState.Running
        val canSave = !busy && (if (type == SourceType.M3U) m3uUrl.isNotBlank() else server.isNotBlank() && user.isNotBlank())
        val save = {
            if (canSave) {
                submitted = true
                sawRunning = false
                if (type == SourceType.M3U) vm.saveM3u(m3uUrl, epgUrl) else vm.saveXtream(server, user, pass, epgUrl)
            }
        }
        Button(
            onClick = save,
            enabled = canSave,
            modifier = Modifier.tap(onClick = save),
        ) {
            Text(if (busy) "Loading…" else "Save and load channels")
        }
        Spacer(Modifier.height(12.dp))
        when (refreshState) {
            is RefreshState.Running -> Text(refreshState.message, color = Palette.TextDim, fontSize = 14.sp)
            is RefreshState.Failed -> if (submitted) Text(refreshState.message, color = Palette.Error, fontSize = 14.sp)
            else -> Unit
        }
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
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .border(2.dp, if (focused) Palette.Accent else Color(0xFF2E3946), RoundedCornerShape(8.dp))
                .background(Palette.Surface, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
