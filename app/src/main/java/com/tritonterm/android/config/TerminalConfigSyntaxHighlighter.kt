package com.tritonterm.android.config

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/** Colors the small TritonTerm config language without changing its source text. */
internal object TerminalConfigSyntaxHighlighter {
    val foreground = Color(0xFFC0C6E2)

    private val comment = Color(0xFF898DAD)
    private val directive = Color(0xFFE4C46A)
    private val condition = Color(0xFFFFB86C)
    private val chord = Color(0xFF80A0FF)
    private val action = Color(0xFF47F0A3)
    private val number = Color(0xFFC28CFF)

    fun highlight(source: String): AnnotatedString = buildAnnotatedString {
        append(source)

        var lineStart = 0
        while (lineStart < source.length) {
            val newline = source.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) source.length else newline
            val tokens = tokens(source, lineStart, lineEnd)

            if (tokens.isNotEmpty()) {
                val first = tokens.first()
                if (first.value.startsWith('#')) {
                    addStyle(SpanStyle(color = comment), first.start, lineEnd)
                } else {
                    when (first.value) {
                        "font_family" -> addStyle(SpanStyle(color = directive), first.start, first.end)
                        "font_size" -> {
                            addStyle(SpanStyle(color = directive), first.start, first.end)
                            tokens.getOrNull(1)?.takeIf { it.value.matches(Regex("\\d+")) }?.let {
                                addStyle(SpanStyle(color = number), it.start, it.end)
                            }
                        }
                        "cursor_shape", "arch_runtime" -> highlightValue(tokens, chord)
                        "cursor_blink_interval", "cursor_beam_thickness", "cursor_underline_thickness" ->
                            highlightValue(tokens, number)
                        "cursor", "cursor_text_color" -> highlightValue(tokens, action)
                        "map" -> highlightMap(tokens)
                    }
                }
            }

            if (newline < 0) break
            lineStart = newline + 1
        }
    }

    private fun androidx.compose.ui.text.AnnotatedString.Builder.highlightValue(tokens: List<Token>, valueColor: Color) {
        val option = tokens.first()
        addStyle(SpanStyle(color = directive), option.start, option.end)
        tokens.getOrNull(1)?.let { addStyle(SpanStyle(color = valueColor), it.start, it.end) }
    }

    private fun androidx.compose.ui.text.AnnotatedString.Builder.highlightMap(tokens: List<Token>) {
        val map = tokens.first()
        addStyle(SpanStyle(color = directive), map.start, map.end)

        var chordIndex = 1
        if (tokens.getOrNull(chordIndex)?.value == "--when-focus-on") {
            val flag = tokens[chordIndex]
            addStyle(SpanStyle(color = condition), flag.start, flag.end)
            tokens.getOrNull(chordIndex + 1)?.takeIf { it.value.startsWith("var:") }?.let {
                addStyle(SpanStyle(color = condition), it.start, it.end)
            }
            chordIndex += 2
        }

        tokens.getOrNull(chordIndex)?.let {
            addStyle(SpanStyle(color = chord), it.start, it.end)
        }
        val actionIndex = chordIndex + 1
        tokens.getOrNull(actionIndex)?.let { actionToken ->
            addStyle(SpanStyle(color = action), actionToken.start, actionToken.end)
            when (actionToken.value) {
                "send_key" -> tokens.getOrNull(actionIndex + 1)?.let {
                    addStyle(SpanStyle(color = chord), it.start, it.end)
                }
                "send_text" -> tokens.drop(actionIndex + 1).forEach {
                    addStyle(SpanStyle(color = chord), it.start, it.end)
                }
            }
        }
    }

    private fun tokens(source: String, start: Int, end: Int): List<Token> {
        val result = mutableListOf<Token>()
        var index = start
        while (index < end) {
            while (index < end && source[index].isWhitespace()) index++
            if (index >= end) break
            val tokenStart = index
            while (index < end && !source[index].isWhitespace()) index++
            result += Token(tokenStart, index, source.substring(tokenStart, index))
        }
        return result
    }

    private data class Token(val start: Int, val end: Int, val value: String)
}
