package com.tritonterm.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tritonterm.android.terminal.DiagnosticEvent
import com.tritonterm.android.terminal.GuestCrashRecord

private enum class EventFilter(val title: String) {
    ALL("All"), PROBLEMS("Problems"), SETUP("Setup"), RECORDER("Recorder")
}

@Composable
internal fun DiagnosticsScreen(
    recorderStatus: String,
    crashes: List<GuestCrashRecord>,
    events: List<DiagnosticEvent>,
    message: String,
    testResult: String,
    busy: Boolean,
    exporting: Boolean,
    onDismiss: () -> Unit,
    onTestSegv: () -> Unit,
    onRunProbes: () -> Unit,
    onCopyLatest: () -> Unit,
    onCopyCrashes: () -> Unit,
    onShare: () -> Unit,
    onShareFile: () -> Unit,
    onCopyMessage: () -> Unit,
    onExport: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(EventFilter.ALL) }
    var expandedEventIds by remember { mutableStateOf(emptySet<String>()) }
    var expandedCrashIds by remember { mutableStateOf(emptySet<Int>()) }

    val normalizedQuery = query.trim().lowercase()
    val visibleCrashes = crashes.filter { crash ->
        normalizedQuery.isEmpty() || crash.raw.lowercase().contains(normalizedQuery)
    }
    val visibleEvents = events.filter { event ->
        val matchesFilter = when (filter) {
            EventFilter.ALL -> true
            EventFilter.PROBLEMS -> event.isProblem
            EventFilter.SETUP -> event.phase.contains("setup", ignoreCase = true) ||
                event.phase.contains("rootfs", ignoreCase = true) ||
                event.phase.contains("userland", ignoreCase = true)
            EventFilter.RECORDER -> event.phase.contains("recorder", ignoreCase = true) ||
                event.phase.contains("sigsegv", ignoreCase = true)
        }
        val matchesQuery = normalizedQuery.isEmpty() ||
            listOf(event.phase, event.status, event.summary, event.source).any { it.lowercase().contains(normalizedQuery) }
        matchesFilter && matchesQuery
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .imePadding()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
                        Text("Recent terminal and PRoot activity", style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("Actions ▾") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Copy latest events") }, onClick = { menuOpen = false; onCopyLatest() })
                            DropdownMenuItem(text = { Text("Copy guest crash records") }, onClick = { menuOpen = false; onCopyCrashes() })
                            DropdownMenuItem(text = { Text("Share recent diagnostics") }, onClick = { menuOpen = false; onShare() })
                            DropdownMenuItem(text = { Text("Share diagnostics file") }, onClick = { menuOpen = false; onShareFile() })
                            DropdownMenuItem(
                                text = { Text(if (exporting) "Exporting…" else "Export verified JSONL") },
                                enabled = !exporting,
                                onClick = { menuOpen = false; onExport() }
                            )
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)
                ) {
                    if (message.isNotBlank()) {
                        item(key = "message") {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(message, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (message.startsWith("Export failed")) {
                                            TextButton(onClick = onShareFile) { Text("Share file") }
                                        }
                                        TextButton(onClick = onCopyMessage) { Text("Copy details") }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "recorder") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("PRoot recorder", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(recorderStatus, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Guest crashes are recorded separately from recorder startup. The test runs a disposable guest program.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = !busy, onClick = onTestSegv) {
                                        Text(if (busy) "Testing…" else "Test SIGSEGV")
                                    }
                                    OutlinedButton(enabled = !busy, onClick = onRunProbes) { Text("Run probes") }
                                }
                                if (testResult != "Not run") {
                                    Text(testResult, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    item(key = "crashes_header") {
                        SectionHeading("Recent guest SIGSEGV records", "${crashes.size} captured")
                    }
                    if (visibleCrashes.isEmpty()) {
                        item(key = "crashes_empty") {
                            Text(
                                if (crashes.isEmpty()) "No guest SIGSEGV records captured yet." else "No crash records match this filter.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        items(visibleCrashes, key = { "crash-${it.id}" }) { crash ->
                            ExpandableDiagnostic(
                                title = crash.summary,
                                subtitle = "Guest process · SIGSEGV",
                                raw = crash.raw,
                                expanded = crash.id in expandedCrashIds,
                                onClick = {
                                    expandedCrashIds = if (crash.id in expandedCrashIds) expandedCrashIds - crash.id
                                    else expandedCrashIds + crash.id
                                }
                            )
                        }
                    }
                    item(key = "events_header") {
                        SectionHeading("Recent events", "${visibleEvents.size} shown · newest first")
                    }
                    item(key = "filter_search") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Filter events and crashes") }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                EventFilter.entries.forEach { candidate ->
                                    FilterChip(
                                        selected = filter == candidate,
                                        onClick = { filter = candidate },
                                        label = { Text(candidate.title) }
                                    )
                                }
                            }
                        }
                    }
                    if (visibleEvents.isEmpty()) {
                        item(key = "events_empty") {
                            Text("No recent events match this filter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        items(visibleEvents, key = { it.id }) { event ->
                            ExpandableDiagnostic(
                                title = event.summary,
                                subtitle = "${event.time} · ${event.source}",
                                raw = event.rawJson,
                                expanded = event.id in expandedEventIds,
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

@Composable
private fun SectionHeading(title: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExpandableDiagnostic(
    title: String,
    subtitle: String,
    raw: String,
    expanded: Boolean,
    onClick: () -> Unit,
    problem: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                Text(
                    raw,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("Tap to inspect raw details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
