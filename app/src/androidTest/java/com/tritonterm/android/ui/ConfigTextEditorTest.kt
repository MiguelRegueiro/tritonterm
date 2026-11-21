package com.tritonterm.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tritonterm.android.config.TerminalConfigSyntaxHighlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConfigTextEditorTest {
    @get:Rule val rule = createComposeRule()

    private val source = (0 until 160).joinToString("\n") {
        "# line ${it.toString().padStart(3, '0')} 雪 🌊 configuration text long enough to wrap on a phone"
    }

    @Test
    fun shrinkingTheKeyboardViewportRevealsTheSameCaretWithoutChangingTextOrSelection() {
        var height by mutableStateOf(520.dp)
        var value by mutableStateOf(TextFieldValue(TerminalConfigSyntaxHighlighter.highlight(source)))
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    ConfigTextEditor(
                        value = value,
                        onValueChange = { value = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.height(height).width(300.dp).testTag("viewport")
                    )
                }
            }
        }
        val offset = source.indexOf("line 080") + 5
        focusAndSelect(offset)
        assertCaretVisible(offset)
        rule.runOnIdle { height = 160.dp }
        rule.waitForIdle()
        assertCaretVisible(offset)
        rule.runOnIdle {
            assertEquals(source, value.text)
            assertEquals(TextRange(offset), value.selection)
        }
        rule.runOnIdle { height = 420.dp }
        rule.waitForIdle()
        assertCaretVisible(offset)
    }

    @Test
    fun manualScrollingIsNotPulledBackToAnUnchangedCaret() {
        var value by mutableStateOf(TextFieldValue(TerminalConfigSyntaxHighlighter.highlight(source)))
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ConfigTextEditor(
                    value = value,
                    onValueChange = { value = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.height(200.dp).width(300.dp).testTag("viewport")
                )
            }
        }
        val offset = source.indexOf("line 080") + 5
        focusAndSelect(offset)
        val before = field().fetchSemanticsNode().positionInRoot.y
        rule.onNodeWithTag("viewport").performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        val after = field().fetchSemanticsNode().positionInRoot.y
        assertTrue("Manual scrolling should remain at the user's position", after > before + 8f)
        rule.runOnIdle { assertEquals(TextRange(offset), value.selection) }
    }

    private fun field() = rule.onNodeWithContentDescription("Configuration editor")

    private fun focusAndSelect(offset: Int) {
        field().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        field().performSemanticsAction(SemanticsActions.SetSelection) { it(offset, offset, false) }
        rule.waitForIdle()
    }

    private fun assertCaretVisible(offset: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        field().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bounds = rule.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        val position = field().fetchSemanticsNode().positionInRoot
        val cursor = layouts.single().getCursorRect(offset).translate(position)
        val padding = with(rule.density) { 16.dp.toPx() }
        assertTrue("Caret above viewport: $cursor / $bounds", cursor.top >= bounds.top + padding - 1f)
        assertTrue("Caret below viewport: $cursor / $bounds", cursor.bottom <= bounds.bottom - padding + 1f)
    }
}
