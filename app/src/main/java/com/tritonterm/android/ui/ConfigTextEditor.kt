package com.tritonterm.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/** A single scroll viewport lets keyboard resizing reveal the actual caret. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConfigTextEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    textStyle: TextStyle,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    val caretRequester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val caretMargin = with(density) { 8.dp.toPx() }

    LaunchedEffect(focused, value.selection, value.text, viewport, textLayout, caretMargin) {
        if (!focused || viewport.height == 0) return@LaunchedEffect
        // Coalesce focus/selection/layout changes in the same frame. Read the
        // latest layout after the resized viewport has been measured.
        withFrameNanos { }
        val layout = textLayout ?: return@LaunchedEffect
        val offset = value.selection.end.coerceIn(0, layout.layoutInput.text.length)
        caretRequester.bringIntoView(layout.getCursorRect(offset).inflate(caretMargin))
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        border = BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp)) {
            Box(
                Modifier.fillMaxSize()
                    .onSizeChanged { viewport = it }
                    .verticalScroll(scroll)
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(min = with(density) { viewport.height.toDp() })
                        .bringIntoViewRequester(caretRequester)
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = "Configuration editor" },
                    onTextLayout = { textLayout = it }
                )
            }
        }
    }
}
