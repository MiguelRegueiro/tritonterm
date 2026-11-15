package com.tritonterm.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text

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

@Composable
internal fun TritonActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (compact) 40.dp else 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF484848)),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF343434),
            contentColor = Color(0xFFF1F1F1),
            disabledContainerColor = Color(0xFF292929),
            disabledContentColor = Color(0xFF999999)
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = if (compact) 6.dp else 10.dp)
    ) {
        Text(label, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
