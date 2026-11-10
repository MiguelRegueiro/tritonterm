package com.tritonterm.android.ui

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.withTimeoutOrNull

private enum class KeyKind { Text, Escape, Tab, Enter, Backspace, Arrow, Modifier, Caps, Shift, Layer }
private data class TerminalKey(
    val label: String,
    val kind: KeyKind = KeyKind.Text,
    val value: String = label,
    val weight: Float = 1f
)

@Composable
internal fun TerminalKeyboard(
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
