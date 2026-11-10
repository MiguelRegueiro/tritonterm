package com.tritonterm.android.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tritonterm.android.config.TerminalConfig
import com.tritonterm.android.terminal.TerminalSessionController
import com.tritonterm.android.terminal.TerminalCallbacks
import com.termux.view.TerminalView
import java.nio.charset.StandardCharsets

@Composable
internal fun TerminalScreen() {
    val context = LocalContext.current
    val shellSession = remember { TerminalSessionController(context) }
    val sessionClient = shellSession.client
    val configFile = remember(context) { TerminalConfig.file(context.filesDir) }
    val initialConfig = remember(configFile) { TerminalConfig.loadOrCreate(configFile) }
    var terminalFontSize by remember { mutableIntStateOf(initialConfig.fontSize) }
    var terminalFontFamily by remember { mutableStateOf(initialConfig.fontFamily) }
    var appliedFontSize by remember { mutableIntStateOf(terminalFontSize) }
    var appliedFontFamily by remember { mutableStateOf(terminalFontFamily) }
    var showConfig by remember { mutableStateOf(false) }
    var configText by remember { mutableStateOf("") }
    var useSystemKeyboard by remember { mutableStateOf(false) }
    val terminalViewHolder = remember { arrayOfNulls<TerminalView>(1) }
    val zoomTerminal = { increase: Boolean ->
        terminalFontSize = (terminalFontSize + if (increase) 2 else -2).coerceIn(12, 40)
    }
    val toggleKeyboard: () -> Unit = {
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (useSystemKeyboard) {
            terminalViewHolder[0]?.let { inputMethodManager.hideSoftInputFromWindow(it.windowToken, 0) }
            useSystemKeyboard = false
        } else {
            useSystemKeyboard = true
            terminalViewHolder[0]?.let { view ->
                view.requestFocus()
                view.post { inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
            }
        }
    }
    val session = shellSession.session

    DisposableEffect(shellSession) {
        onDispose { shellSession.close() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .background(Color.Black)
            .padding(top = 4.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("tritonterm", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF8992B2))
            Spacer(Modifier.weight(1f))
            Text(
                "Config",
                modifier = Modifier
                    .clickable {
                        configText = configFile.readText()
                        showConfig = true
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = Color(0xFFD5D5D5)
            )
            Text(
                if (useSystemKeyboard) "Keys" else "IME",
                modifier = Modifier
                    .clickable(onClick = toggleKeyboard)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = Color(0xFFD5D5D5)
            )
            Spacer(Modifier.padding(end = 8.dp))
            Text("local", fontSize = 11.sp, color = Color(0xFF626A83))
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { viewContext ->
                TerminalView(viewContext, null).apply {
                    setTextSize(terminalFontSize)
                    setTypeface(TerminalConfig(terminalFontFamily, terminalFontSize).typeface(context))
                    setTerminalViewClient(TerminalCallbacks(session, zoomTerminal))
                    sessionClient.terminalView = this
                    attachSession(session)
                    terminalViewHolder[0] = this
                    setBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
            },
            update = {
                it.attachSession(session)
                if (appliedFontSize != terminalFontSize) {
                    it.setTextSize(terminalFontSize)
                    appliedFontSize = terminalFontSize
                }
                if (appliedFontFamily != terminalFontFamily) {
                    it.setTypeface(TerminalConfig(terminalFontFamily, terminalFontSize).typeface(context))
                    appliedFontFamily = terminalFontFamily
                }
            }
        )

        TerminalKeyboard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            onZoom = zoomTerminal,
            compact = useSystemKeyboard,
            onInput = { input ->
                val bytes = input.toByteArray(StandardCharsets.UTF_8)
                session.write(bytes, 0, bytes.size)
            }
        )
    }
    if (showConfig) {
        TerminalConfigDialog(
            text = configText,
            onTextChanged = { configText = it },
            onDismiss = { showConfig = false },
            onSave = { editedText ->
                val result = TerminalConfig.parse(editedText)
                val parsed = result.config
                if (parsed == null) {
                    result.error ?: "The config could not be parsed."
                } else {
                    configFile.writeText(editedText)
                    terminalFontSize = parsed.fontSize
                    terminalFontFamily = parsed.fontFamily
                    showConfig = false
                    null
                }
            }
        )
    }
}


@Composable
private fun TerminalConfigDialog(
    text: String,
    onTextChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> String?
) {
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("tritonterm.conf") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Supported options: font_family and font_size.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChanged,
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onSave(text) }) { Text("Save & apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
