package com.tritonterm.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tritonterm.android.terminal.DiagnosticEvent
import com.tritonterm.android.terminal.GuestCrashRecord
import kotlinx.coroutines.delay
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween

@Composable
internal fun DiagnosticsScreen(
    crashes: List<GuestCrashRecord>,
    events: List<DiagnosticEvent>,
    message: String,
    testResult: String,
    busy: Boolean,
    sharing: Boolean,
    onDismiss: () -> Unit,
    onTestSegv: () -> Unit,
    onRunProbes: () -> Unit,
    onRunPermissionProbe: () -> Unit,
    onRunPackageManagerProbe: () -> Unit,
    onRepairPacmanKeyring: () -> Unit,
    onShareFile: () -> Unit,
    onCopyLog: (String) -> Unit,
    onClear: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var toolsExpanded by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var expandedEventIds by remember { mutableStateOf(emptySet<String>()) }
    var expandedCrashIds by remember { mutableStateOf(emptySet<Int>()) }

    val normalizedQuery = query.trim().lowercase()
    val visibleCrashes = crashes.filter { crash ->
        normalizedQuery.isEmpty() || crash.raw.lowercase().contains(normalizedQuery)
    }
    val visibleEvents = events.filter { event ->
        normalizedQuery.isEmpty() ||
            listOf(event.phase, event.status, event.summary, event.source).any { it.lowercase().contains(normalizedQuery) }
    }
    val visibleEventGroups = visibleEvents.groupBy { it.time.substringBefore(' ') }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .padding(horizontal = 16.dp)
            ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) { Text("← Terminal") }
                Text(
                    "Logs",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Button(onClick = onShareFile, enabled = !sharing) {
                    Text(if (sharing) "Preparing…" else "Share log file", maxLines = 1)
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 20.dp)
            ) {
                item(key = "troubleshooting") {
                    TroubleshootingTools(
                        expanded = toolsExpanded,
                        onToggle = { toolsExpanded = !toolsExpanded },
                        testResult = testResult,
                        busy = busy,
                        onTestSegv = onTestSegv,
                        onRunProbes = onRunProbes,
                        onRunPermissionProbe = onRunPermissionProbe,
                        onRunPackageManagerProbe = onRunPackageManagerProbe,
                        onRepairPacmanKeyring = onRepairPacmanKeyring
                    )
                }

                if (message.isNotBlank()) {
                    item(key = "message") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF171717),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text(
                                message,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                color = MaterialTheme.colorScheme.secondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (crashes.isNotEmpty()) {
                    item(key = "crashes_header") {
                        SectionHeading("Guest crashes", "${crashes.size} crashes")
                    }
                    if (visibleCrashes.isEmpty()) {
                        item(key = "crashes_empty") {
                            EmptyLogsMessage("No crash records match this search.")
                        }
                    } else {
                        items(visibleCrashes, key = { "crash-${it.id}" }) { crash ->
                            ExpandableDiagnostic(
                                title = crash.summary,
                                subtitle = "Guest process · SIGSEGV",
                                raw = crash.raw,
                                expanded = crash.id in expandedCrashIds,
                                onCopy = { onCopyLog(crash.raw) },
                                onClick = {
                                    expandedCrashIds = if (crash.id in expandedCrashIds) expandedCrashIds - crash.id
                                    else expandedCrashIds + crash.id
                                }
                            )
                        }
                    }
                }

                item(key = "events_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${events.size} logs",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        OutlinedButton(
                            onClick = { showClearConfirmation = true },
                            enabled = events.isNotEmpty() || crashes.isNotEmpty(),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF353535))
                        ) { Text("Clear logs") }
                    }
                }
                item(key = "search_filter") {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Search logs") },
                        leadingIcon = { SearchGlyph() },
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF353535),
                            unfocusedBorderColor = Color(0xFF353535),
                            disabledBorderColor = Color(0xFF353535),
                            focusedContainerColor = Color.Black,
                            unfocusedContainerColor = Color.Black,
                            disabledContainerColor = Color.Black
                        )
                    )
                }

                if (visibleEvents.isEmpty()) {
                    item(key = "events_empty") {
                        EmptyLogsMessage(if (events.isEmpty()) "No logs recorded yet." else "No logs match this search.")
                    }
                } else {
                    visibleEventGroups.forEach { (day, dayEvents) ->
                        item(key = "log-day-$day") {
                            Text(
                                day,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(dayEvents, key = { it.id }) { event ->
                            ExpandableDiagnostic(
                                title = event.summary,
                                timestamp = event.time,
                                subtitle = event.source.takeUnless { it == "android" }.orEmpty(),
                                raw = event.rawJson,
                                expanded = event.id in expandedEventIds,
                                onCopy = { onCopyLog(event.rawJson) },
                                onClick = {
                                    expandedEventIds = if (event.id in expandedEventIds) expandedEventIds - event.id
                                    else expandedEventIds + event.id
                                },
                                problem = event.isProblem
                            )
                        }
                    }
                }

            }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear logs?") },
            text = { Text("This clears TritonTerm’s saved event and guest crash logs from this device. New activity will be recorded as usual.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirmation = false
                    onClear()
                }) { Text("Clear logs", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeading(title: String, detail: String, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable
private fun EmptyLogsMessage(text: String) {
    Text(
        text,
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun TroubleshootingTools(
    expanded: Boolean,
    onToggle: () -> Unit,
    testResult: String,
    busy: Boolean,
    onTestSegv: () -> Unit,
    onRunProbes: () -> Unit,
    onRunPermissionProbe: () -> Unit,
    onRunPackageManagerProbe: () -> Unit,
    onRepairPacmanKeyring: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onToggle
        ),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF222222),
            contentColor = Color.White
        ),
        border = BorderStroke(1.dp, Color(0xFF353535))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Troubleshooting tools", style = MaterialTheme.typography.titleSmall, color = Color.White)
                }
                Text(if (expanded) "−" else "+", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
            }
            if (expanded) {
                Text("Checks", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TroubleshootingActionButton(
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        onClick = onRunProbes,
                        label = if (busy) "Working…" else "Run runtime checks"
                    )
                    TroubleshootingActionButton(
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        onClick = onTestSegv,
                        label = "Test crash capture"
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TroubleshootingActionButton(
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        onClick = onRunPermissionProbe,
                        label = "Executable access"
                    )
                    TroubleshootingActionButton(
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                        onClick = onRunPackageManagerProbe,
                        label = "Package manager"
                    )
                }
                Text("Repair tools", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TroubleshootingActionButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    onClick = onRepairPacmanKeyring,
                    label = "Repair pacman signing keys"
                )
                if (testResult != "Not run") {
                    Text(testResult, style = MaterialTheme.typography.bodySmall, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun TroubleshootingActionButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    label: String
) {
    Button(
        modifier = modifier,
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(0xFF505050)),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF383838),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF292929),
            disabledContentColor = Color(0xFFAAAAAA)
        )
    ) {
        Text(label, maxLines = 1, fontSize = 13.sp)
    }
}

@Composable
private fun SearchGlyph() {
    Canvas(Modifier.size(20.dp)) {
        val line = 1.8.dp.toPx()
        val lensCenter = Offset(size.width * 0.40f, size.height * 0.40f)
        val radius = size.minDimension * 0.26f
        drawCircle(
            color = Color(0xFFBDBDBD),
            radius = radius,
            center = lensCenter,
            style = Stroke(width = line)
        )
        drawLine(
            color = Color(0xFFBDBDBD),
            start = Offset(lensCenter.x + radius * 0.72f, lensCenter.y + radius * 0.72f),
            end = Offset(size.width * 0.84f, size.height * 0.84f),
            strokeWidth = line
        )
    }
}

@Composable
private fun ExpandableDiagnostic(
    title: String,
    subtitle: String,
    timestamp: String? = null,
    raw: String,
    expanded: Boolean,
    onCopy: () -> Unit,
    onClick: () -> Unit,
    problem: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
        border = BorderStroke(1.dp, Color(0xFF353535))
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    timestamp?.substringAfter(' ', timestamp) ?: subtitle,
                    modifier = Modifier.fillMaxWidth().padding(end = if (expanded) 40.dp else 0.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (timestamp != null && subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded) {
                    Text(
                        raw,
                        modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (expanded) {
                CopyLogButton(
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    onClick = onCopy
                )
            }
        }
    }
}

@Composable
private fun CopyLogButton(
    modifier: Modifier,
    onClick: () -> Unit
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(450)
            copied = false
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val highlighted = pressed || copied
    val buttonColor by animateColorAsState(
        targetValue = if (highlighted) Color(0xFF4A4A4A) else Color(0xCC171719),
        animationSpec = tween(durationMillis = 100),
        label = "copy-button-color"
    )
    val iconColor by animateColorAsState(
        targetValue = if (highlighted) Color.White else Color(0xFFBDBDBD),
        animationSpec = tween(durationMillis = 100),
        label = "copy-icon-color"
    )
    val borderColor by animateColorAsState(
        targetValue = Color.White.copy(alpha = if (highlighted) 0.42f else 0.12f),
        animationSpec = tween(durationMillis = 100),
        label = "copy-button-border"
    )

    Surface(
        modifier = modifier.size(34.dp)
            .clickable(interactionSource = interactionSource, indication = null) {
                copied = true
                onClick()
            }
            .semantics { contentDescription = "Copy full log entry" },
        shape = CircleShape,
        color = buttonColor,
        contentColor = Color.White,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = 2.dp
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CopyGlyph(iconColor) }
    }
}

@Composable
private fun CopyGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val unit = size.width / 24f
        val stroke = 1.7.dp.toPx()
        val backPage = Path().apply {
            moveTo(15f * unit, 7f * unit)
            lineTo(15f * unit, 5f * unit)
            cubicTo(15f * unit, 3.34f * unit, 13.66f * unit, 2f * unit, 12f * unit, 2f * unit)
            lineTo(5f * unit, 2f * unit)
            cubicTo(3.34f * unit, 2f * unit, 2f * unit, 3.34f * unit, 2f * unit, 5f * unit)
            lineTo(2f * unit, 15f * unit)
            cubicTo(2f * unit, 16.66f * unit, 3.34f * unit, 18f * unit, 5f * unit, 18f * unit)
            lineTo(7f * unit, 18f * unit)
        }
        drawPath(backPage, color = color, style = Stroke(width = stroke))
        drawRoundRect(
            color = color,
            topLeft = Offset(8f * unit, 8f * unit),
            size = Size(14f * unit, 14f * unit),
            cornerRadius = CornerRadius(2f * unit),
            style = Stroke(width = stroke)
        )
    }
}
