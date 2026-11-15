package com.tritonterm.android.ui

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.tritonterm.android.config.TerminalKeyBinding
import com.tritonterm.android.config.TerminalKeyAction
import com.tritonterm.android.terminal.TerminalClipboardSupport
import kotlinx.coroutines.withTimeoutOrNull

private enum class KeyKind { Text, Escape, Tab, Enter, Backspace, Delete, Arrow, Modifier, Caps, Shift, Layer }
private data class TerminalKey(
    val label: String,
    val kind: KeyKind = KeyKind.Text,
    val value: String = label,
    val weight: Float = 1f,
    val modeExclusive: Boolean = false
)

private val KeyboardKeySpacing = 4.dp
private const val ReferenceBackspaceWeight = 1.5f
private const val ReferenceBackspaceRowWeight = 10f
private const val ReferenceBackspaceRowKeyCount = 9

@Composable
internal fun TerminalKeyboard(
    modifier: Modifier = Modifier,
    onInput: (String) -> Unit,
    onZoom: (Boolean) -> Unit,
    onCopy: () -> Boolean,
    onPaste: () -> Unit,
    keyBindings: List<TerminalKeyBinding>,
    activeUserVariables: () -> Set<String>,
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

    val utility = listOf(
        TerminalKey("Esc", KeyKind.Escape), TerminalKey("↹", KeyKind.Tab),
        TerminalKey("Caps", KeyKind.Caps),
        TerminalKey(if (layer) "ABC" else "?123", KeyKind.Layer, weight = 1.15f, modeExclusive = layer),
        TerminalKey("←", KeyKind.Arrow, "\u001b[D"), TerminalKey("↑", KeyKind.Arrow, "\u001b[A"),
        TerminalKey("↓", KeyKind.Arrow, "\u001b[B"), TerminalKey("→", KeyKind.Arrow, "\u001b[C")
    )
    val commonKeys = listOf(
        TerminalKey("/"), TerminalKey("?"), TerminalKey(":"), TerminalKey(";"),
        TerminalKey("@"), TerminalKey("#"), TerminalKey("~"), TerminalKey("+"), TerminalKey("-", weight = 1.1f),
        TerminalKey("Del", KeyKind.Delete)
    )
    val digits = "1234567890".map { TerminalKey(it.toString()) }
    val qRow = "QWERTYUIOP".map { TerminalKey(it.toString()) }
    val aRow = "ASDFGHJKLÑ".map { TerminalKey(it.toString()) }
    val zRow = listOf(
        TerminalKey("⇧", KeyKind.Shift, weight = 1.5f),
        *"ZXCVBNM".map { TerminalKey(it.toString()) }.toTypedArray(),
        TerminalKey("⌫", KeyKind.Backspace, weight = ReferenceBackspaceWeight)
    )
    val symbolRows = listOf(
        listOf("!", "%", "^", "&", "*", "(", ")", "=", "[", "]").map { TerminalKey(it, modeExclusive = true) },
        listOf("{", "}", "\\", "|", "<", ">", "\$", "`", "'", "\"").map { TerminalKey(it, modeExclusive = true) },
        listOf(
            TerminalKey("Home", KeyKind.Arrow, "\u001b[H", 1.05f, modeExclusive = true),
            TerminalKey("End", KeyKind.Arrow, "\u001b[F", 1.05f, modeExclusive = true),
            TerminalKey("PgUp", KeyKind.Arrow, "\u001b[5~", 1.05f, modeExclusive = true),
            TerminalKey("PgDn", KeyKind.Arrow, "\u001b[6~", 1.05f, modeExclusive = true),
            TerminalKey("Ins", KeyKind.Arrow, "\u001b[2~", 0.95f, modeExclusive = true),
            TerminalKey("¡", weight = 0.95f, modeExclusive = true),
            TerminalKey("¿", weight = 0.95f, modeExclusive = true)
        ) +
            TerminalKey("⌫", KeyKind.Backspace, weight = ReferenceBackspaceWeight)
    )
    val modifiers = listOf(
        TerminalKey("Ctrl", KeyKind.Modifier, "ctrl", weight = 0.95f),
        TerminalKey("Alt", KeyKind.Modifier, "alt", weight = 0.75f),
        TerminalKey(",", value = ",", weight = 0.6f),
        TerminalKey("", value = " ", weight = 2.5f),
        TerminalKey(".", value = ".", weight = 0.6f),
        TerminalKey("↵", KeyKind.Enter, weight = 1.2f)
    )
    val compactNavigationKeys = listOf(
        utility[0], // Esc
        utility[1], // Tab
        TerminalKey("Home", KeyKind.Arrow, "\u001b[H"),
        TerminalKey("End", KeyKind.Arrow, "\u001b[F"),
        utility[4], // Left
        utility[5], // Up
        utility[6], // Down
        utility[7]  // Right
    )
    val compactShellKeys = listOf(
        modifiers[0].copy(weight = 1f), // Ctrl, same width as Esc and Tab.
        modifiers[1].copy(weight = 1f), // Alt, same width as Esc and Tab.
        TerminalKey("PgUp", KeyKind.Arrow, "\u001b[5~"),
        TerminalKey("PgDn", KeyKind.Arrow, "\u001b[6~"),
        TerminalKey("~"),
        TerminalKey("/"),
        TerminalKey("-"),
        TerminalKey("Del", KeyKind.Delete)
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
                    KeyKind.Delete -> "\u001b[3~"
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
                val mappedKeyName = when {
                    key.kind == KeyKind.Escape -> "esc"
                    key.kind == KeyKind.Tab -> "tab"
                    key.kind == KeyKind.Text && adjusted.length == 1 -> TerminalClipboardSupport.keyName(adjusted[0])
                    else -> null
                }
                if (mappedKeyName != null) {
                    val action = keyBindings.lastOrNull {
                        it.matches(mappedKeyName, ctrl, shift, alt, activeUserVariables())
                    }?.action
                    when (action) {
                        TerminalKeyAction.COPY_TO_CLIPBOARD -> if (onCopy()) return
                        TerminalKeyAction.PASTE_FROM_CLIPBOARD -> { onPaste(); return }
                        TerminalKeyAction.SEND_ESCAPE, TerminalKeyAction.SEND_CTRL_C,
                        TerminalKeyAction.SEND_KEY_CTRL_C, TerminalKeyAction.SEND_KEY_F13,
                        TerminalKeyAction.SEND_KEY_F14 -> {
                            action.terminalInput?.let(onInput)
                            return
                        }
                        null -> Unit
                    }
                }
                val sequence = if (ctrl && adjusted.length == 1 && adjusted[0].code < 128) {
                    (adjusted[0].uppercaseChar().code and 0x1f).toChar().toString()
                } else adjusted
                onInput((if (alt) "\u001b" else "") + sequence)
            }
        }
    }

    Column(modifier.fillMaxWidth().padding(bottom = if (compact) 4.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (compact) {
            KeyRow(compactNavigationKeys, 38.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = emptySet())
            KeyRow(
                compactShellKeys,
                38.dp,
                onClick = ::emit,
                onHoldChanged = ::setHeld,
                active = buildSet { if (ctrl) add("Ctrl"); if (alt) add("Alt") }
            )
        } else {
            KeyRow(utility, 38.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = setOfNotNull(if (caps) "Caps" else null))
            KeyRow(commonKeys, 38.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = emptySet(), caps = caps, shift = shift)
            KeyRow(digits, 50.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = emptySet(), caps = caps, shift = shift)
            if (layer) {
                symbolRows.forEach { row -> KeyRow(row, 50.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = emptySet(), caps = caps, shift = shift) }
            } else {
                KeyRow(qRow, 50.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = emptySet(), caps = caps, shift = shift)
                KeyRow(aRow, 50.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = emptySet(), caps = caps, shift = shift)
                KeyRow(zRow, 50.dp, onClick = ::emit, onHoldChanged = ::setHeld, active = if (shift) setOf("⇧") else emptySet(), caps = caps, shift = shift)
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
    "'" -> "\""; "," -> "<"; "." -> ">"
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
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = maxWidth - KeyboardKeySpacing * (keys.size - 1)
        val referenceWidth = maxWidth - KeyboardKeySpacing * (ReferenceBackspaceRowKeyCount - 1)
        val referenceBackspaceWidth = referenceWidth * (ReferenceBackspaceWeight / ReferenceBackspaceRowWeight)
        val otherKeyWeights = keys.asSequence()
            .filterNot { it.kind == KeyKind.Backspace }
            .sumOf { it.weight.toDouble() }
            .toFloat()
        val matchedBackspaceWeight = if (availableWidth > referenceBackspaceWidth) {
            otherKeyWeights * referenceBackspaceWidth.value / (availableWidth - referenceBackspaceWidth).value
        } else {
            ReferenceBackspaceWeight
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KeyboardKeySpacing)) {
        keys.forEach { key ->
            val highlighted = key.label in active || (key.kind == KeyKind.Shift && key.label in active)
            var pressed by remember(key.kind, key.label) { mutableStateOf(false) }
            val currentOnClick by rememberUpdatedState(onClick)
            val currentOnHoldChanged by rememberUpdatedState(onHoldChanged)
            val holdModifier = key.kind == KeyKind.Modifier || key.kind == KeyKind.Shift
            val isLetter = key.kind == KeyKind.Text && key.value.length == 1 && key.value[0].isLetter()
            val label = when {
                isLetter -> if (caps xor shift) key.value.uppercase() else key.value.lowercase()
                key.kind == KeyKind.Text && shift -> shiftedSymbol(key.value)
                else -> key.label
            }
            val modifierKey = key.kind == KeyKind.Modifier || key.kind == KeyKind.Shift || key.kind == KeyKind.Caps || key.kind == KeyKind.Layer
            val editKey = key.kind == KeyKind.Backspace || key.kind == KeyKind.Delete || key.kind == KeyKind.Enter
            val utilityKey = modifierKey || editKey || key.kind == KeyKind.Escape || key.kind == KeyKind.Tab || key.kind == KeyKind.Arrow
            val directionalArrow = key.kind == KeyKind.Arrow && key.label in setOf("←", "↑", "↓", "→")
            val smallSymbol = key.kind == KeyKind.Text && label.length == 1 && !label[0].isLetterOrDigit()
            val longNavigationLabel = key.label == "Home" || key.label == "End" || key.label == "PgUp" || key.label == "PgDn"
            val labelSize = when {
                key.kind == KeyKind.Tab -> 18.sp
                key.kind == KeyKind.Shift -> 22.sp
                key.kind == KeyKind.Backspace -> 21.sp
                directionalArrow -> 17.sp
                longNavigationLabel -> 14.sp
                smallSymbol -> if (height >= 48.dp) 22.sp else 18.sp
                utilityKey -> if (height >= 48.dp) 17.sp else 14.sp
                label.firstOrNull()?.isLowerCase() == true && height >= 48.dp -> 22.sp
                height >= 48.dp -> 20.sp
                height >= 32.dp -> 16.sp
                else -> 12.sp
            }
            Surface(
                modifier = Modifier
                    .weight(if (key.kind == KeyKind.Backspace) matchedBackspaceWeight else key.weight)
                    .height(height)
                    .pointerInput(key.kind, key.label) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            pressed = true
                            if (holdModifier) currentOnHoldChanged(key, true) else currentOnClick(key)
                            try {
                                var isPressed = true
                                val repeatable = key.kind in setOf(KeyKind.Text, KeyKind.Tab, KeyKind.Enter, KeyKind.Backspace, KeyKind.Delete, KeyKind.Arrow)
                                var nextRepeat = SystemClock.uptimeMillis() + 450L
                                while (isPressed) {
                                    val event = if (repeatable) {
                                        val remaining = (nextRepeat - SystemClock.uptimeMillis()).coerceAtLeast(1L)
                                        withTimeoutOrNull(remaining) { awaitPointerEvent() }
                                    } else {
                                        awaitPointerEvent()
                                    }
                                    if (event == null && repeatable) {
                                        currentOnClick(key)
                                        nextRepeat = SystemClock.uptimeMillis() + 55L
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
                    key.modeExclusive -> Color(0xFF202020)
                    modifierKey -> Color(0xFF353535)
                    editKey -> Color(0xFF3A3A3A)
                    utilityKey -> Color(0xFF2F2F2F)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val labelColor = when {
                        pressed -> Color.White
                        highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
                        utilityKey -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    if (key.kind == KeyKind.Enter) {
                        Canvas(Modifier.size(width = 28.dp, height = 26.dp)) {
                            val scale = size.minDimension / 24f
                            val stroke = 1.5f * scale
                            drawLine(
                                labelColor,
                                androidx.compose.ui.geometry.Offset(20f * scale, 5f * scale),
                                androidx.compose.ui.geometry.Offset(20f * scale, 14f * scale),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                labelColor,
                                androidx.compose.ui.geometry.Offset(20f * scale, 14f * scale),
                                androidx.compose.ui.geometry.Offset(4f * scale, 14f * scale),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                labelColor,
                                androidx.compose.ui.geometry.Offset(9f * scale, 9f * scale),
                                androidx.compose.ui.geometry.Offset(4f * scale, 14f * scale),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                labelColor,
                                androidx.compose.ui.geometry.Offset(4f * scale, 14f * scale),
                                androidx.compose.ui.geometry.Offset(9f * scale, 19f * scale),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                        }
                    } else {
                        Text(
                            label,
                            modifier = if (key.kind == KeyKind.Shift) Modifier.graphicsLayer(scaleX = 1.35f) else Modifier,
                            fontSize = labelSize,
                            fontWeight = if (directionalArrow) FontWeight.SemiBold else FontWeight.Medium,
                            color = labelColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
        }
    }
}
