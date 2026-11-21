package com.tritonterm.android.terminal

import android.view.KeyEvent

internal enum class TerminalClipboardAction { COPY, PASTE }

/** Small, platform independent rules shared by hardware and touch keyboard shortcuts. */
internal object TerminalClipboardSupport {
    fun keyName(keyCode: Int): String? = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('a' + (keyCode - KeyEvent.KEYCODE_A)).toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
        KeyEvent.KEYCODE_INSERT -> "insert"
        KeyEvent.KEYCODE_ESCAPE -> "esc"
        KeyEvent.KEYCODE_TAB -> "tab"
        KeyEvent.KEYCODE_COMMA -> "comma"
        KeyEvent.KEYCODE_PERIOD -> "period"
        KeyEvent.KEYCODE_MINUS -> "minus"
        KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> "plus"
        KeyEvent.KEYCODE_SPACE -> "space"
        else -> null
    }

    fun keyName(character: Char): String? = when (character.lowercaseChar()) {
        in 'a'..'z', in '0'..'9' -> character.lowercaseChar().toString()
        ',' -> "comma"
        '.' -> "period"
        '-' -> "minus"
        '+' -> "plus"
        ' ' -> "space"
        else -> null
    }

    fun shortcut(character: Char, control: Boolean, shift: Boolean): TerminalClipboardAction? {
        if (!control || !shift) return null
        return when (character.lowercaseChar()) {
            'c' -> TerminalClipboardAction.COPY
            'v' -> TerminalClipboardAction.PASTE
            else -> null
        }
    }

    fun clipboardText(value: CharSequence?): String? = value?.toString()?.takeIf(String::isNotEmpty)
}
