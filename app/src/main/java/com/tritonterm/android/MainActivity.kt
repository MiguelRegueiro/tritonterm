package com.tritonterm.android

import android.os.Bundle
import android.os.SystemClock
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.withTimeoutOrNull

private val TritonPalette = darkColorScheme(
    primary = Color(0xFFDADADA), onPrimary = Color(0xFF1B1B1B),
    primaryContainer = Color(0xFF484848), onPrimaryContainer = Color(0xFFF2F2F2),
    secondary = Color(0xFFCFCFCF), onSecondary = Color(0xFF1B1B1B),
    secondaryContainer = Color(0xFF2A2A2A), onSecondaryContainer = Color(0xFFE8E8E8),
    background = Color(0xFF101010), onBackground = Color(0xFFF3F3F3),
    surface = Color(0xFF1B1B1B), onSurface = Color(0xFFF3F3F3),
    surfaceVariant = Color(0xFF292929), onSurfaceVariant = Color(0xFFD5D5D5),
    outline = Color(0xFF969696)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        setContent {
            MaterialTheme(colorScheme = TritonPalette) {
                TritonTermScreen()
            }
        }
    }
}

@Composable
private fun TritonTermScreen() {
    val context = LocalContext.current
    val sessionClient = remember { TritonSessionClient() }
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
    val session = remember {
        val home = context.filesDir.absolutePath
        val env = arrayOf(
            "HOME=$home",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "XDG_CONFIG_HOME=${File(context.filesDir, ".config").absolutePath}",
            "PATH=/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8"
        )
        TerminalSession("/system/bin/sh", home, emptyArray(), env, 2000, sessionClient)
    }

    DisposableEffect(session) {
        onDispose {
            sessionClient.terminalView = null
            session.finishIfRunning()
        }
    }

    MaterialTheme(colorScheme = TritonPalette) {
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

private class TritonSessionClient : TerminalSessionClient {
    var terminalView: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.onScreenUpdated()
    }
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit
}

private class TerminalCallbacks(
    private val session: TerminalSession,
    private val zoom: (Boolean) -> Unit
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = 1f
    override fun onSingleTapUp(e: MotionEvent) = Unit
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (e.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_MINUS -> { zoom(false); return true }
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> { zoom(true); return true }
            }
        }
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        session.writeCodePoint(ctrlDown, codePoint)
        return true
    }
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit
}

private enum class KeyKind { Text, Escape, Tab, Enter, Backspace, Arrow, Modifier, Caps, Shift, Layer }
private data class TerminalKey(
    val label: String,
    val kind: KeyKind = KeyKind.Text,
    val value: String = label,
    val weight: Float = 1f
)

@Composable
private fun TerminalKeyboard(
    modifier: Modifier = Modifier,
    onInput: (String) -> Unit,
    onZoom: (Boolean) -> Unit,
    compact: Boolean
) {
    var layer by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var caps by remember { mutableStateOf(false) }

    fun setHeld(key: TerminalKey, down: Boolean) {
        when (key.value) {
            "ctrl" -> ctrl = down
            "alt" -> alt = down
        }
        if (key.kind == KeyKind.Shift) shift = down
    }

    val fnKeys = (1..12).map { index ->
        val sequence = when (index) {
            1 -> "\u001bOP"; 2 -> "\u001bOQ"; 3 -> "\u001bOR"; 4 -> "\u001bOS"
            in 5..10 -> "\u001b[${index + 10}~"
            11 -> "\u001b[23~"
            else -> "\u001b[24~"
        }
        TerminalKey("F$index", value = sequence, weight = 1f)
    }
    val utility = listOf(
        TerminalKey("Esc", KeyKind.Escape), TerminalKey("Tab", KeyKind.Tab),
        TerminalKey("Caps", KeyKind.Caps),
        TerminalKey(if (layer) "ABC" else "?123", KeyKind.Layer, weight = 1.15f),
        TerminalKey("←", KeyKind.Arrow, "\u001b[D"), TerminalKey("↑", KeyKind.Arrow, "\u001b[A"),
        TerminalKey("↓", KeyKind.Arrow, "\u001b[B"), TerminalKey("→", KeyKind.Arrow, "\u001b[C")
    )
    val digits = "1234567890".map { TerminalKey(it.toString()) }
    val qRow = "QWERTYUIOP".map { TerminalKey(it.toString()) }
    val aRow = "ASDFGHJKLÑ".map { TerminalKey(it.toString()) }
    val zRow = listOf(
        TerminalKey("Shift", KeyKind.Shift, weight = 1.35f),
        *"ZXCVBNM".map { TerminalKey(it.toString()) }.toTypedArray(),
        TerminalKey(".", value = "."), TerminalKey("⌫", KeyKind.Backspace, weight = 1.35f)
    )
    val symbolKeys = listOf(
        "?", "!", "@", "#", "€", "%", "&", "*", "(", ")",
        "/", "+", "=", "_", ":", ";", ",", ".", "'", "\"",
        "\\", "|", "[", "]", "{", "}", "<", ">", "-", "¿"
    ).map { TerminalKey(it) }
    val modifiers = listOf(
        TerminalKey("Ctrl", KeyKind.Modifier, "ctrl"),
        TerminalKey("Alt", KeyKind.Modifier, "alt"),
        TerminalKey("", value = " ", weight = 2.9f),
        TerminalKey("Enter", KeyKind.Enter, weight = 1.3f)
    )

    fun emit(key: TerminalKey) {
        when (key.kind) {
            KeyKind.Layer -> layer = !layer
            KeyKind.Caps -> caps = !caps
            KeyKind.Shift, KeyKind.Modifier -> Unit
            else -> {
                if (ctrl && key.kind == KeyKind.Backspace) {
                    onInput("\u0017") // Ctrl+W: erase the previous word in common shells.
                    return
                }
                val text = when (key.kind) {
                    KeyKind.Escape -> "\u001b"
                    KeyKind.Tab -> "\t"
                    KeyKind.Enter -> "\r"
                    KeyKind.Backspace -> "\u007f"
                    KeyKind.Arrow -> key.value
                    else -> key.value
                }
                if (ctrl && key.kind == KeyKind.Text && text in setOf("-", "+", "=")) {
                    onZoom(text != "-")
                    return
                }
                val adjusted = if (key.kind == KeyKind.Text && text.length == 1 && text[0].isLetter()) {
                    if (caps xor shift) text.uppercase() else text.lowercase()
                } else if (key.kind == KeyKind.Text && shift) {
                    shiftedSymbol(text)
                } else text
                val sequence = if (ctrl && adjusted.length == 1 && adjusted[0].code < 128) {
                    (adjusted[0].uppercaseChar().code and 0x1f).toChar().toString()
                } else adjusted
                onInput((if (alt) "\u001b" else "") + sequence)
            }
        }
    }

    Column(modifier.fillMaxWidth().padding(bottom = if (compact) 4.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (compact) {
            KeyRow(utility.filterNot { it.kind == KeyKind.Caps || it.kind == KeyKind.Layer }, 38.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = emptySet())
            KeyRow(modifiers.filter { it.kind == KeyKind.Modifier }, 38.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = buildSet { if (ctrl) add("Ctrl"); if (alt) add("Alt") })
        } else {
            KeyRow(fnKeys, 38.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = emptySet())
            KeyRow(utility, 38.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = setOfNotNull(if (caps) "Caps" else null))
            KeyRow(digits, 50.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = emptySet(), caps = caps, shift = shift)
            if (layer) {
                symbolKeys.chunked(10).forEach { row -> KeyRow(row, 50.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = emptySet(), caps = caps, shift = shift) }
            } else {
                KeyRow(qRow, 50.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = emptySet(), caps = caps, shift = shift)
                KeyRow(aRow, 50.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = emptySet(), caps = caps, shift = shift)
                KeyRow(zRow, 50.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = if (shift) setOf("Shift") else emptySet(), caps = caps, shift = shift)
            }
            KeyRow(
                modifiers,
                50.dp,
                onClick = ::emit,
                onHoldChanged = ::setHeld,
                active = buildSet { if (ctrl) add("Ctrl"); if (alt) add("Alt") },
                caps = caps,
                shift = shift
            )
        }
    }
}

private fun shiftedSymbol(value: String): String = when (value) {
    "1" -> "!"; "2" -> "@"; "3" -> "#"; "4" -> "$"; "5" -> "%"
    "6" -> "^"; "7" -> "&"; "8" -> "*"; "9" -> "("; "0" -> ")"
    "-" -> "_"; "=" -> "+"; "[" -> "{"; "]" -> "}"
    ";" -> ":"; "'" -> "\""; "," -> "<"; "." -> ">"; "/" -> "?"
    else -> value
}

@Composable
private fun KeyRow(
    keys: List<TerminalKey>,
    height: Dp,
    onClick: (TerminalKey) -> Unit,
    onHoldChanged: (TerminalKey, Boolean) -> Unit,
    active: Set<String>,
    caps: Boolean = false,
    shift: Boolean = false
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        keys.forEach { key ->
            val highlighted = key.label in active || (key.kind == KeyKind.Shift && key.label in active)
            var pressed by remember(key.kind, key.label) { mutableStateOf(false) }
            val currentOnClick by rememberUpdatedState(onClick)
            val currentOnHoldChanged by rememberUpdatedState(onHoldChanged)
            val holdModifier = key.kind == KeyKind.Modifier || key.kind == KeyKind.Shift
            val isLetter = key.kind == KeyKind.Text && key.value.length == 1 && key.value[0].isLetter()
            val label = if (isLetter) {
                if (caps xor shift) key.value.uppercase() else key.value.lowercase()
            } else key.label
            val utilityKey = key.kind in setOf(KeyKind.Escape, KeyKind.Tab, KeyKind.Backspace, KeyKind.Enter, KeyKind.Arrow, KeyKind.Modifier, KeyKind.Caps, KeyKind.Shift, KeyKind.Layer)
            val functionKey = key.label.startsWith("F") && key.label.drop(1).toIntOrNull() != null
            val labelSize = when {
                functionKey -> if (height >= 32.dp) 16.sp else 12.sp
                utilityKey -> if (height >= 48.dp) 17.sp else 14.sp
                label.firstOrNull()?.isLowerCase() == true && height >= 48.dp -> 22.sp
                height >= 48.dp -> 20.sp
                height >= 32.dp -> 16.sp
                else -> 12.sp
            }
            Surface(
                modifier = Modifier
                    .weight(key.weight)
                    .height(height)
                    .pointerInput(key.kind, key.label) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            pressed = true
                            if (holdModifier) currentOnHoldChanged(key, true) else currentOnClick(key)
                            try {
                                var isPressed = true
                                var nextBackspaceRepeat = SystemClock.uptimeMillis() + 450L
                                while (isPressed) {
                                    val event = if (key.kind == KeyKind.Backspace) {
                                        val remaining = (nextBackspaceRepeat - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                                        withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                    } else {
                                        awaitPointerEvent()
                                    }
                                    if (event == null && key.kind == KeyKind.Backspace) {
                                        currentOnClick(key)
                                        nextBackspaceRepeat = SystemClock.uptimeMillis() + 55L
                                    } else if (event != null) {
                                        isPressed = event.changes.firstOrNull { it.id == down.id }?.pressed == true
                                    }
                                }
                            } finally {
                                if (holdModifier) currentOnHoldChanged(key, false)
                                pressed = false
                            }
                        }
                    },
                shape = MaterialTheme.shapes.small,
                color = when {
                    pressed -> Color(0xFF6A6A6A)
                    highlighted -> Color(0xFF525252)
                    utilityKey -> MaterialTheme.colorScheme.secondaryContainer
                    functionKey -> MaterialTheme.colorScheme.surface
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        fontSize = labelSize,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            pressed -> Color.White
                            highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
                            utilityKey -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1
                    )
                }
            }
        }
    }
}
