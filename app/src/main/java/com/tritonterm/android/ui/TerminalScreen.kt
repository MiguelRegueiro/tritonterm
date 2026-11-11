package com.tritonterm.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import android.view.Menu
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.tritonterm.android.config.TerminalConfig
import com.tritonterm.android.terminal.TerminalSessionController
import com.tritonterm.android.terminal.TerminalSessionHost
import com.tritonterm.android.terminal.TerminalForegroundService
import com.tritonterm.android.terminal.PacmanRecoveryState
import com.tritonterm.android.terminal.ArchLinuxArmInstaller
import com.tritonterm.android.terminal.TerminalDiagnostics
import com.tritonterm.android.terminal.RuntimeDiagnosticsProbes
import com.tritonterm.android.terminal.TerminalSetupProgress
import com.tritonterm.android.terminal.TerminalSetupProgressFile
import com.tritonterm.android.terminal.TerminalSetupStage
import com.tritonterm.android.terminal.TerminalCallbacks
import com.tritonterm.android.terminal.TerminalClipboardAction
import com.tritonterm.android.terminal.TerminalClipboardSupport
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

private const val DIAGNOSTICS_PREVIEW_CHARS = 80_000

@Composable
internal fun TerminalScreen() {
    val context = LocalContext.current
    val terminalController = remember(context) { TerminalSessionHost.controller(context) }
    val installState by ArchLinuxArmInstaller.state.collectAsState()
    val pacmanLockPresent = remember(context) { PacmanRecoveryState.hasDatabaseLock(context) }
    var setupReady by remember(terminalController) {
        mutableStateOf(terminalController.isReady(context))
    }
    var setupError by remember(context) { mutableStateOf<String?>(null) }
    var setupWarning by remember(context) { mutableStateOf<String?>(null) }
    var setupProgress by remember(context) {
        mutableStateOf(TerminalSetupProgress(TerminalSetupStage.IDLE))
    }
    var session by remember(terminalController) { mutableStateOf<TerminalSession?>(null) }
    val setupScope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(terminalController) {
        if (terminalController.isReady(context)) {
            session = terminalController.start()
            setupReady = true
            return@LaunchedEffect
        }
        setupReady = false
    }

    LaunchedEffect(installState) {
        setupProgress = installState.progress
        setupError = installState.error
        if (installState.ready && session == null) {
            try {
                session = terminalController.start()
                setupReady = true
            } catch (failure: Exception) {
                setupReady = false
                setupError = failure.message ?: "Arch is installed, but its interactive shell could not start."
                TerminalDiagnostics.record(context, "shell_startup", "failed", mapOf("error" to setupError))
            }
        }
    }

    LaunchedEffect(installState.running) {
        if (!installState.running) return@LaunchedEffect
        var lastInstallProgress: List<Any>? = null
        while (ArchLinuxArmInstaller.state.value.running) {
            val extracted = withContext(Dispatchers.IO) {
                TerminalSetupProgressFile.read(context)
            }
            if (extracted != null) {
                val signature = listOf(extracted.stage, extracted.extractedBytes, extracted.extractionTotal, extracted.extractedEntries)
                if (signature != lastInstallProgress) {
                    withContext(Dispatchers.IO) {
                        TerminalDiagnostics.record(
                            context,
                            "installer_progress",
                            extracted.stage.name.lowercase(),
                            mapOf(
                                "archive_bytes_read" to extracted.extractedBytes,
                                "archive_bytes_total" to extracted.extractionTotal,
                                "filesystem_entries" to extracted.extractedEntries
                            )
                        )
                    }
                    lastInstallProgress = signature
                }
                setupProgress = setupProgress.copy(
                    stage = extracted.stage,
                    extractedEntries = extracted.extractedEntries,
                    extractedBytes = extracted.extractedBytes,
                    extractionTotal = extracted.extractionTotal
                )
            }
            delay(300)
        }
    }

    LaunchedEffect(installState.running, setupProgress.stage, setupProgress.extractedBytes, setupProgress.extractedEntries) {
        if (!installState.running || setupReady || setupError != null ||
            setupProgress.stage !in setOf(TerminalSetupStage.INSTALLING, TerminalSetupStage.INITIALIZING)
        ) return@LaunchedEffect
        setupWarning = null
        delay(90_000)
        if (installState.running && !setupReady && setupError == null) {
            setupWarning = "No setup progress has been reported for 90 seconds. The process is still running; open Logs to inspect its last phase."
            TerminalDiagnostics.record(
                context,
                "setup_watchdog",
                "no_progress_90s",
                mapOf("stage" to setupProgress.stage.name, "archive_bytes_read" to setupProgress.extractedBytes, "filesystem_entries" to setupProgress.extractedEntries)
            )
        }
    }

    val sessionClient = terminalController.client
    val configFile = remember(context) { TerminalConfig.file(context.filesDir) }
    val initialConfig = remember(configFile) { TerminalConfig.loadOrCreate(configFile) }
    var terminalFontSize by remember { mutableIntStateOf(initialConfig.fontSize) }
    var terminalFontFamily by remember { mutableStateOf(initialConfig.fontFamily) }
    var appliedFontSize by remember { mutableIntStateOf(terminalFontSize) }
    var appliedFontFamily by remember { mutableStateOf(terminalFontFamily) }
    var showConfig by remember { mutableStateOf(false) }
    var configText by remember { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnosticsText by remember { mutableStateOf("") }
    var diagnosticsMessage by remember { mutableStateOf("") }
    var smokeRunning by remember { mutableStateOf(false) }
    var smokeResult by remember { mutableStateOf("Not run") }
    val exportDiagnostics = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri: Uri? ->
        if (uri == null) {
            diagnosticsMessage = "Export cancelled."
        } else {
            val result = runCatching {
                TerminalDiagnostics.flushProcessOutput()
                val logText = TerminalDiagnostics.read(context)
                check(logText.isNotBlank()) { "The diagnostics log is empty." }
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(logText.toByteArray(Charsets.UTF_8))
                    output.flush()
                } ?: error("Could not open the selected destination.")
            }
            diagnosticsMessage = if (result.isSuccess) "Diagnostics exported." else "Export failed: ${result.exceptionOrNull()?.message}"
        }
    }
    var useSystemKeyboard by remember { mutableStateOf(false) }
    val terminalViewHolder = remember { arrayOfNulls<TerminalView>(1) }
    val zoomTerminal = { increase: Boolean ->
        terminalFontSize = (terminalFontSize + if (increase) 2 else -2).coerceIn(12, 40)
    }
    val toggleKeyboard: () -> Unit = {
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (useSystemKeyboard) {
            terminalViewHolder[0]?.let { inputMethodManager.hideSoftInputFromWindow(it.windowToken, 0) }
            useSystemKeyboard = false
        } else {
            useSystemKeyboard = true
            terminalViewHolder[0]?.let { view ->
                view.requestFocus()
                view.post { inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
            }
        }
    }
    DisposableEffect(terminalController) {
        onDispose { terminalController.detachView() }
    }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .background(Color.Black)
            .padding(top = 4.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("tritonterm", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF8992B2))
            Spacer(Modifier.weight(1f))
            Text(
                "Config",
                modifier = Modifier
                    .clickable {
                        configText = configFile.readText()
                        showConfig = true
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = Color(0xFFD5D5D5)
            )
            Text(
                "Logs",
                modifier = Modifier.clickable {
                    diagnosticsText = TerminalDiagnostics.read(context).takeLast(DIAGNOSTICS_PREVIEW_CHARS)
                    diagnosticsMessage = ""
                    showDiagnostics = true
                }.padding(horizontal = 8.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = Color(0xFFD5D5D5)
            )
            Text(
                if (useSystemKeyboard) "Keys" else "IME",
                modifier = Modifier
                    .clickable(onClick = toggleKeyboard)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 12.sp,
                color = Color(0xFFD5D5D5)
            )
            Spacer(Modifier.padding(end = 8.dp))
            Text(
                terminalController.runtimeLabel,
                fontSize = 11.sp,
                color = Color(0xFF626A83)
            )
        }

        if (pacmanLockPresent && setupReady) {
            Text(
                "A pacman database lock exists. A transaction may still be running; do not start another pacman command. If the previous process stopped, verify pacman is no longer active before recovering the lock. See Logs for process recovery details.",
                modifier = Modifier.fillMaxWidth().background(Color(0xFF3B2D1A)).padding(horizontal = 12.dp, vertical = 8.dp),
                color = Color(0xFFFFCC80),
                fontSize = 12.sp
            )
        }

        val currentSession = session
        if (currentSession != null && setupReady) {
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { viewContext ->
                    TerminalView(viewContext, null).apply {
                        setTextSize(terminalFontSize)
                        setTypeface(TerminalConfig(terminalFontFamily, terminalFontSize).typeface(context))
                        setTerminalViewClient(TerminalCallbacks(currentSession, zoomTerminal))
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
                                false
                            } else {
                                val character = when (keyCode) {
                                    KeyEvent.KEYCODE_C -> 'c'
                                    KeyEvent.KEYCODE_V -> 'v'
                                    else -> null
                                }
                                val action = character?.let {
                                    TerminalClipboardSupport.shortcut(it, event.isCtrlPressed, event.isShiftPressed)
                                }
                                when (action) {
                                    TerminalClipboardAction.COPY -> {
                                        sessionClient.copySelection()
                                        true
                                    }
                                    TerminalClipboardAction.PASTE -> {
                                        sessionClient.pasteFromClipboard(currentSession)
                                        true
                                    }
                                    null -> false
                                }
                            }
                        }
                        setOnCreateContextMenuListener { menu, _, _ ->
                            menu.add(Menu.NONE, Menu.FIRST, Menu.NONE, "Select All")
                                .setOnMenuItemClickListener {
                                    stopTextSelectionMode()
                                    val copied = sessionClient.copyAllText(currentSession)
                                    if (!copied) Toast.makeText(context, "No terminal text to copy", Toast.LENGTH_SHORT).show()
                                    else Toast.makeText(context, "All terminal text copied", Toast.LENGTH_SHORT).show()
                                    true
                                }
                        }
                        sessionClient.terminalView = this
                        attachSession(currentSession)
                        terminalViewHolder[0] = this
                        TerminalForegroundService.startSession(context)
                        setBackgroundColor(android.graphics.Color.BLACK)
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                },
                update = {
                    it.attachSession(currentSession)
                    if (appliedFontSize != terminalFontSize) {
                        it.setTextSize(terminalFontSize)
                        appliedFontSize = terminalFontSize
                    }
                    if (appliedFontFamily != terminalFontFamily) {
                        it.setTypeface(TerminalConfig(terminalFontFamily, terminalFontSize).typeface(context))
                        appliedFontFamily = terminalFontFamily
                    }
                }
            )
        } else {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        setupError ?: when (setupProgress.stage) {
                            TerminalSetupStage.IDLE -> if (installState.preflightPassed) {
                                "The native installer launcher passed its harmless check. Arch Linux ARM is not installed yet."
                            } else "Arch Linux ARM is not installed. Test the installer launcher before starting setup."
                            TerminalSetupStage.DOWNLOADING -> "Downloading Arch Linux ARM…"
                            TerminalSetupStage.VERIFYING -> "Checking the downloaded Arch Linux ARM archive…"
                            TerminalSetupStage.CLEANING -> "Checking interrupted-install state without removing files…"
                            TerminalSetupStage.INSTALLING -> "Unpacking Arch Linux ARM…"
                            TerminalSetupStage.INITIALIZING -> "Initializing Arch package keys…"
                            TerminalSetupStage.READY -> "Starting shell…"
                            TerminalSetupStage.FAILED -> "Setup failed."
                        },
                        color = if (setupError == null) Color(0xFFD5D5D5) else Color(0xFFFF8A80),
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                    setupWarning?.let {
                        Text(it, color = Color(0xFFFFCC80), textAlign = TextAlign.Center, fontSize = 12.sp)
                    }
                    if (installState.running) {
                        val total = setupProgress.total
                        if (setupProgress.stage == TerminalSetupStage.DOWNLOADING && total != null && total > 0) {
                            Text(
                                "${setupProgress.downloaded / (1024 * 1024)} / ${total / (1024 * 1024)} MiB",
                                color = Color(0xFF626A83),
                                fontSize = 12.sp
                            )
                        }
                        if ((setupProgress.stage == TerminalSetupStage.VERIFYING ||
                                setupProgress.stage == TerminalSetupStage.INSTALLING) &&
                            setupProgress.extractionTotal > 0L
                        ) {
                            val progressText = if (setupProgress.stage == TerminalSetupStage.VERIFYING) {
                                "${setupProgress.extractedBytes / (1024 * 1024)} / " +
                                    "${setupProgress.extractionTotal / (1024 * 1024)} MiB checked"
                            } else {
                                "${setupProgress.extractedBytes / (1024 * 1024)} / " +
                                    "${setupProgress.extractionTotal / (1024 * 1024)} MiB · " +
                                    "${setupProgress.extractedEntries} entries"
                            }
                            Text(progressText, color = Color(0xFF626A83), fontSize = 12.sp)
                        }
                        if (setupProgress.stage == TerminalSetupStage.CLEANING) {
                            Text(
                                "${setupProgress.extractedEntries} installer entries checked",
                                color = Color(0xFF626A83),
                                fontSize = 12.sp
                            )
                        }
                        SetupStageProgress(
                            downloadFraction = when {
                                setupProgress.stage.ordinal >= TerminalSetupStage.VERIFYING.ordinal -> 1f
                                total != null && total > 0 -> setupProgress.downloaded.toFloat() / total
                                else -> null
                            },
                            installStage = setupProgress.stage,
                            installFraction = setupProgress.extractionTotal.takeIf { it > 0L }?.let {
                                setupProgress.extractedBytes.toFloat() / it
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (!setupReady) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = !installState.running && !installState.preflightPassed,
                                onClick = { ArchLinuxArmInstaller.runPreflight(context) }
                            ) { Text(if (installState.preflightPassed) "Launcher verified" else "Test launcher") }
                            Button(
                                enabled = installState.preflightPassed && !installState.running && installState.error == null,
                                onClick = { ArchLinuxArmInstaller.start(context) }
                            ) { Text("Install Arch") }
                        }
                        if (setupError != null) {
                            Text(
                                "Open Logs and export diagnostics. This build will not automatically retry or remove the preserved rootfs.",
                                color = Color(0xFFFFCC80),
                                textAlign = TextAlign.Center,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        TerminalKeyboard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            onZoom = zoomTerminal,
            onCopy = {
                if (!sessionClient.copySelection()) {
                    Toast.makeText(context, "Select terminal text first", Toast.LENGTH_SHORT).show()
                }
            },
            onPaste = { sessionClient.pasteFromClipboard(session) },
            compact = useSystemKeyboard,
            onInput = { input ->
                val bytes = input.toByteArray(StandardCharsets.UTF_8)
                session?.write(bytes, 0, bytes.size)
            }
        )
    }
    if (showConfig) {
        TerminalConfigDialog(
            text = configText,
            onTextChanged = { configText = it },
            onDismiss = { showConfig = false },
            onSave = { editedText ->
                val result = TerminalConfig.parse(editedText)
                val parsed = result.config
                if (parsed == null) {
                    result.error ?: "The config could not be parsed."
                } else {
                    configFile.writeText(editedText)
                    terminalFontSize = parsed.fontSize
                    terminalFontFamily = parsed.fontFamily
                    showConfig = false
                    null
                }
            }
        )
    }
    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("Diagnostics") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        smokeResult,
                        color = if (smokeResult.startsWith("PASS")) Color(0xFF81C784) else Color(0xFFFFCC80),
                        fontSize = 12.sp
                    )
                    Text(
                        diagnosticsMessage,
                        color = Color(0xFFD5D5D5),
                        fontSize = 12.sp
                    )
                    Text(
                        diagnosticsText,
                        modifier = Modifier.height(300.dp).verticalScroll(rememberScrollState()),
                        color = Color(0xFFD5D5D5),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    TerminalDiagnostics.flushProcessOutput()
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("TritonTerm diagnostics", TerminalDiagnostics.read(context))
                    )
                    diagnosticsMessage = "Diagnostics copied to clipboard."
                }) { Text("Copy") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = !smokeRunning,
                        onClick = {
                            smokeRunning = true
                            smokeResult = "Running three runtime probes…"
                            setupScope.launch {
                                val results = withContext(Dispatchers.IO) {
                                    RuntimeDiagnosticsProbes.run(context)
                                }
                                smokeRunning = false
                                smokeResult = results.joinToString("\n") { it.summary() }
                                diagnosticsText = TerminalDiagnostics.read(context).takeLast(DIAGNOSTICS_PREVIEW_CHARS)
                            }
                        }
                    ) { Text("Run runtime probes") }
                    TextButton(onClick = {
                        exportDiagnostics.launch("tritonterm-diagnostics.jsonl")
                    }) { Text("Export") }
                }
            }
        )
    }
}

@Composable
private fun SetupStageProgress(
    downloadFraction: Float?,
    installStage: TerminalSetupStage,
    installFraction: Float?,
    modifier: Modifier = Modifier
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        StageProgressBar(
            title = "Download",
            fraction = downloadFraction,
            active = downloadFraction == null,
            modifier = Modifier.weight(1f)
        )
        StageProgressBar(
            title = when (installStage) {
                TerminalSetupStage.VERIFYING -> "Verify"
                TerminalSetupStage.CLEANING -> "Cleanup"
                TerminalSetupStage.INSTALLING -> "Unpack"
                else -> "Install"
            },
            fraction = when (installStage) {
                TerminalSetupStage.VERIFYING -> installFraction
                TerminalSetupStage.CLEANING -> null
                TerminalSetupStage.INSTALLING -> installFraction
                else -> 0f
            },
            active = installStage in setOf(TerminalSetupStage.VERIFYING, TerminalSetupStage.INSTALLING) &&
                installFraction == null,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StageProgressBar(
    title: String,
    fraction: Float?,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val offset = if (fraction == null && active) {
        val transition = rememberInfiniteTransition(label = "${title}Progress")
        val animatedOffset by transition.animateFloat(
            initialValue = -0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1_000)),
            label = "${title}ProgressOffset"
        )
        animatedOffset
    } else {
        0f
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = Color(0xFF626A83), fontSize = 11.sp)
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(Color(0xFF292929), cornerRadius = radius)
            if (fraction != null) {
                val width = size.width * fraction.coerceIn(0f, 1f)
                if (width > 0f) {
                    drawRoundRect(
                        Color(0xFF8992B2),
                        size = Size(width, size.height),
                        cornerRadius = radius
                    )
                }
            } else if (active) {
                val segmentWidth = size.width * 0.34f
                val left = ((size.width + segmentWidth) * offset - segmentWidth).coerceIn(0f, size.width)
                val right = (left + segmentWidth).coerceAtMost(size.width)
                if (right > left) {
                    drawRoundRect(
                        Color(0xFF8992B2),
                        topLeft = Offset(left, 0f),
                        size = Size(right - left, size.height),
                        cornerRadius = radius
                    )
                }
            }
        }
    }
}


@Composable
private fun TerminalConfigDialog(
    text: String,
    onTextChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> String?
) {
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("tritonterm.conf") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Supported options: font_family and font_size.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChanged,
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onSave(text) }) { Text("Save & apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
