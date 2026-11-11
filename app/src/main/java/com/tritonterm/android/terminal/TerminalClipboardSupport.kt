package com.tritonterm.android.terminal

internal enum class TerminalClipboardAction { COPY, PASTE }

/** Small, platform independent rules shared by hardware and touch keyboard shortcuts. */
internal object TerminalClipboardSupport {
    fun shortcut(character: Char, control: Boolean, shift: Boolean): TerminalClipboardAction? {
        if (!control || !shift) return null
        return when (character.lowercaseChar()) {
            'c' -> TerminalClipboardAction.COPY
            'v' -> TerminalClipboardAction.PASTE
            else -> null
        }
    }

    fun clipboardText(value: CharSequence?): String? = value?.toString()?.takeIf(String::isNotEmpty)

    fun allTerminalText(value: CharSequence?): String? = value?.toString()?.trimEnd()?.takeIf(String::isNotEmpty)
}
