package com.tritonterm.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

@Composable
internal fun TritonTermApp() {
    MaterialTheme(colorScheme = TritonPalette) {
        TerminalScreen()
    }
}
