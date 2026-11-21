package com.tritonterm.android.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalClipboardSupportTest {
    @Test
    fun copyAndPasteRequireBothControlAndShift() {
        assertEquals(TerminalClipboardAction.COPY, TerminalClipboardSupport.shortcut('C', true, true))
        assertEquals(TerminalClipboardAction.PASTE, TerminalClipboardSupport.shortcut('v', true, true))
        assertNull(TerminalClipboardSupport.shortcut('c', true, false))
        assertNull(TerminalClipboardSupport.shortcut('v', false, true))
        assertNull(TerminalClipboardSupport.shortcut('x', true, true))
    }

    @Test
    fun clipboardTextPreservesUnicodeAndMultilineContentExactly() {
        val value = "こんにちは 🌊\nline two\n"
        assertEquals(value, TerminalClipboardSupport.clipboardText(value))
    }

    @Test
    fun emptyClipboardTextIsNotPasted() {
        assertNull(TerminalClipboardSupport.clipboardText(null))
        assertNull(TerminalClipboardSupport.clipboardText(""))
    }
}
