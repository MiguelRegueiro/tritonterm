package com.tritonterm.android.ui

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.Menu
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.FileProvider
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
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
import com.tritonterm.android.terminal.ArchLinuxArmBootstrap
import com.tritonterm.android.terminal.ArchRootfsRelease
import com.tritonterm.android.terminal.TerminalDiagnostics
import com.tritonterm.android.terminal.DiagnosticEvent
import com.tritonterm.android.terminal.GuestCrashRecord
import com.tritonterm.android.terminal.RuntimeDiagnosticsProbes
import com.tritonterm.android.terminal.TerminalSetupProgress
import com.tritonterm.android.terminal.TerminalSetupProgressFile
import com.tritonterm.android.terminal.TerminalSetupStage
import com.tritonterm.android.terminal.ArchGuestUser
import com.tritonterm.android.terminal.TerminalCallbacks
import com.tritonterm.android.terminal.TerminalClipboardSupport
import com.tritonterm.android.config.TerminalKeyAction
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun TerminalScreen() {
    val context = LocalContext.current
    val terminalController = remember(context) { TerminalSessionHost.controller(context) }
    val installState by ArchLinuxArmInstaller.state.collectAsState()
    val reusableArchRootfs = remember(context, installState.ready) {
        ArchLinuxArmBootstrap.isRootfsExtracted(context)
    }
    var pacmanLockWarning by remember(context) { mutableStateOf(false) }
    var setupReady by remember(terminalController) {
        mutableStateOf(terminalController.isReady(context))
    }
    var setupError by remember(context) { mutableStateOf<String?>(null) }
    var showFeatureWarnings by remember(context) { mutableStateOf(false) }
    var setupWarning by remember(context) { mutableStateOf<String?>(null) }
    var setupProgress by remember(context) {
        mutableStateOf(TerminalSetupProgress(TerminalSetupStage.IDLE))
    }
    var stageElapsedSeconds by remember(context) { mutableLongStateOf(0L) }
    var session by remember(terminalController) { mutableStateOf<TerminalSession?>(null) }
    val currentTerminalSession = rememberUpdatedState(session)
    var terminalProcessFinished by remember(terminalController) { mutableStateOf(false) }
    val setupScope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(terminalController) {
        if (terminalController.isReady(context)) {
            // Post the ongoing notification before PRoot/session startup can take time.
            TerminalForegroundService.startSession(context)
            try {
                session = terminalController.start()
                setupReady = true
            } catch (failure: Exception) {
                TerminalForegroundService.stop(context)
                TerminalSessionHost.markSessionInactive(context)
                setupReady = false
                setupError = failure.message ?: "The terminal shell could not start."
                TerminalDiagnostics.record(context, "shell_startup", "failed", mapOf("error" to setupError))
            }
            return@LaunchedEffect
        }
        setupReady = false
    }

    LaunchedEffect(installState) {
        setupProgress = installState.progress
        setupError = installState.error
        if (installState.ready && !installState.running && session == null) {
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

    LaunchedEffect(setupReady) {
        var noProcessSince: Long? = null
        while (setupReady) {
            val state = withContext(Dispatchers.IO) { PacmanRecoveryState.state(context) }
            if (state == PacmanRecoveryState.LockState.POSSIBLY_STALE) {
                val now = android.os.SystemClock.elapsedRealtime()
                val firstSeen = noProcessSince ?: now.also { noProcessSince = it }
                pacmanLockWarning = PacmanRecoveryState.shouldWarn(state, now - firstSeen)
            } else {
                noProcessSince = null
                // A live pacman process owns its lock; ordinary user-initiated commands are quiet.
                pacmanLockWarning = false
            }
            delay(1_000)
        }
        pacmanLockWarning = false
    }

    LaunchedEffect(installState.running) {
        if (!installState.running) return@LaunchedEffect
        var lastInstallProgress: List<Any>? = null
        while (TerminalSetupProgressFile.shouldPollInstaller(ArchLinuxArmInstaller.state.value)) {
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

    LaunchedEffect(installState.running, installState.stage) {
        if (!installState.running) {
            stageElapsedSeconds = 0L
            return@LaunchedEffect
        }
        val stage = installState.stage
        val started = android.os.SystemClock.elapsedRealtime()
        while (ArchLinuxArmInstaller.state.value.running && ArchLinuxArmInstaller.state.value.stage == stage) {
            stageElapsedSeconds = (android.os.SystemClock.elapsedRealtime() - started) / 1_000L
            delay(1_000L)
        }
    }

    LaunchedEffect(
        installState.running,
        setupProgress.stage,
        setupProgress.extractedBytes,
        setupProgress.extractedEntries,
        setupProgress.stepCompleted,
        setupProgress.activitySequence
    ) {
        if (!installState.running || setupReady || setupError != null ||
            setupProgress.stage !in setOf(
                TerminalSetupStage.INSTALLING,
                TerminalSetupStage.PREPARING,
                TerminalSetupStage.DEVELOPER_TOOLS,
                TerminalSetupStage.INITIALIZING
            )
        ) return@LaunchedEffect
        setupWarning = null
        delay(90_000)
        if (installState.running && !setupReady && setupError == null) {
            setupWarning = "No new command output for 90 seconds. The package or build command is still running and has not been stopped; open Logs to inspect its last output."
            TerminalDiagnostics.record(
                context,
                "setup_watchdog",
                "no_progress_90s",
                mapOf(
                    "stage" to setupProgress.stage.name,
                    "activity_kind" to if (setupProgress.stage == TerminalSetupStage.INSTALLING) "archive_extraction" else "command_output",
                    "last_command_output" to setupProgress.lastActivity,
                    "activity_sequence" to setupProgress.activitySequence,
                    "archive_bytes_read" to setupProgress.extractedBytes,
                    "filesystem_entries" to setupProgress.extractedEntries,
                    "process_terminated" to false
                )
            )
        }
    }

    val sessionClient = terminalController.client
    LaunchedEffect(session) {
        terminalProcessFinished = session?.isRunning == false
    }
    DisposableEffect(sessionClient, setupScope) {
        sessionClient.finishObserver = { setupScope.launch { terminalProcessFinished = true } }
        onDispose {
            sessionClient.finishObserver = null
            terminalController.detachView()
        }
    }
    val configFile = remember(context) { TerminalConfig.file(context.filesDir) }
    val initialConfig = remember(configFile) { TerminalConfig.loadOrCreate(configFile) }
    var terminalFontSize by remember { mutableIntStateOf(initialConfig.fontSize) }
    var terminalFontFamily by remember { mutableStateOf(initialConfig.fontFamily) }
    var terminalKeyBindings by remember { mutableStateOf(initialConfig.keyBindings) }
    val currentTerminalKeyBindings = rememberUpdatedState(terminalKeyBindings)
    var appliedFontSize by remember { mutableIntStateOf(terminalFontSize) }
    var appliedFontFamily by remember { mutableStateOf(terminalFontFamily) }
    var showConfig by remember { mutableStateOf(false) }
    var configText by remember { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnosticEvents by remember { mutableStateOf(emptyList<DiagnosticEvent>()) }
    var crashRecords by remember { mutableStateOf(emptyList<GuestCrashRecord>()) }
    var recorderStatusText by remember { mutableStateOf("Recorder status unknown.") }
    var diagnosticsMessage by remember { mutableStateOf("") }
    var smokeRunning by remember { mutableStateOf(false) }
    var exportRunning by remember { mutableStateOf(false) }
    var smokeResult by remember { mutableStateOf("Not run") }
    val exportDiagnostics = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-ndjson")
    ) { uri: Uri? ->
        TerminalDiagnostics.recordExportDestination(context, uri)
        if (uri == null) {
            diagnosticsMessage = "Export cancelled."
        } else {
            diagnosticsMessage = "Exporting and verifying diagnostics…"
            exportRunning = true
            setupScope.launch {
                val result = runCatching { withContext(Dispatchers.IO) { TerminalDiagnostics.export(context, uri) } }
                exportRunning = false
                diagnosticsMessage = result.fold(
                    onSuccess = {
                        "Destination URI ${it.destinationUri}: verified ${it.uriReadbackBytes} bytes " +
                            "(expected ${it.expectedBytes}, wrote ${it.bytesWritten}); " +
                            "provider reports ${it.providerReportedBytes?.toString() ?: "size unavailable"} " +
                            "after ${it.providerMetadataAttempts} metadata checks over ${it.providerMetadataWaitMs} ms." +
                            (it.providerMetadataError?.let { error -> " Metadata note: $error" } ?: "")
                    },
                    onFailure = { "Export failed: ${it.message ?: it.javaClass.simpleName}" }
                )
            }
        }
    }
    var useSystemKeyboard by remember { mutableStateOf(false) }
    var keyboardVisible by remember { mutableStateOf(true) }
    var pendingBuiltInKeyboard by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    val imeTargetInsets = WindowInsets.imeAnimationTarget
    val animatedImeInsets = WindowInsets.ime
    LaunchedEffect(imeVisible, pendingBuiltInKeyboard) {
        if (pendingBuiltInKeyboard && !imeVisible) {
            useSystemKeyboard = false
            keyboardVisible = true
            pendingBuiltInKeyboard = false
        }
    }
    val terminalViewHolder = remember { arrayOfNulls<TerminalView>(1) }
    val inputMethodManager = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
    val zoomTerminal = { increase: Boolean ->
        terminalFontSize = (terminalFontSize + if (increase) 2 else -2).coerceIn(12, 112)
    }
    val hideSystemKeyboard = {
        terminalViewHolder[0]?.let { inputMethodManager.hideSoftInputFromWindow(it.windowToken, 0) }
    }
    val showSystemKeyboard = {
        terminalViewHolder[0]?.let { view ->
            view.requestFocus()
            view.post { inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT) }
        }
    }
    val toggleKeyboardMode: () -> Unit = {
        if (useSystemKeyboard) {
            // Let the Android IME finish dismissing before showing our own
            // keyboard. Otherwise both keyboards briefly occupy the window.
            if (imeVisible) {
                pendingBuiltInKeyboard = true
                keyboardVisible = false
                hideSystemKeyboard()
            } else {
                useSystemKeyboard = false
                keyboardVisible = true
            }
        } else {
            pendingBuiltInKeyboard = false
            useSystemKeyboard = true
            keyboardVisible = true
            showSystemKeyboard()
        }
    }
    val toggleKeyboardVisibility: () -> Unit = {
        if (pendingBuiltInKeyboard) pendingBuiltInKeyboard = false
        keyboardVisible = !keyboardVisible
        if (useSystemKeyboard) {
            if (keyboardVisible) showSystemKeyboard() else hideSystemKeyboard()
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            // Use the final IME inset while Android animates. The animated
            // inset changes every frame; Termux TerminalView turns each size
            // change into a PTY resize, which flickers the cursor and output.
            .windowInsetsPadding(WindowInsets.imeAnimationTarget)
            .background(Color.Black)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                configText = configFile.readText()
                showConfig = true
            }) { Text("Config", fontSize = 12.sp) }
            TextButton(onClick = {
                    diagnosticEvents = runCatching { TerminalDiagnostics.recentEvents(context) }.getOrDefault(emptyList())
                    recorderStatusText = TerminalDiagnostics.recorderStatusText(context)
                    crashRecords = TerminalDiagnostics.guestCrashRecords(context)
                    diagnosticsMessage = ""
                    showDiagnostics = true
            }) { Text("Logs", fontSize = 12.sp) }
            if (setupReady) {
                TextButton(
                    onClick = toggleKeyboardMode,
                    modifier = Modifier.semantics {
                        contentDescription = if (useSystemKeyboard) "Switch to the built-in keyboard" else "Switch to the Android keyboard"
                    }
                ) {
                    Text(if (useSystemKeyboard) "Built-in" else "Android", fontSize = 11.sp)
                }
                TextButton(
                    onClick = toggleKeyboardVisibility,
                    modifier = Modifier.semantics {
                        contentDescription = if (keyboardVisible) "Hide the keyboard" else "Show the keyboard"
                    }
                ) {
                    Text(if (keyboardVisible) "Hide" else "Show", fontSize = 11.sp)
                }
            }
        }

        if (pacmanLockWarning && setupReady) {
            Text(
                "A pacman database lock has remained without an active pacman process. It may be stale or left by an interrupted transaction. TritonTerm will not remove it; verify pacman is stopped before recovering the lock.",
                modifier = Modifier.fillMaxWidth().background(Color(0xFF3B2D1A)).padding(horizontal = 12.dp, vertical = 8.dp),
                color = Color(0xFFFFCC80),
                fontSize = 12.sp
            )
        }

        val currentSession = session
        if (currentSession != null && setupReady) {
            if (installState.featureWarnings.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Some image checks failed (${installState.featureWarnings.size}); terminal is available.",
                        color = Color(0xFFFFCC80), fontSize = 11.sp, maxLines = 1
                    )
                    TextButton(onClick = { showFeatureWarnings = true }) { Text("Details") }
                }
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        TerminalView(viewContext, null).apply {
                        val config = TerminalConfig(terminalFontFamily, terminalFontSize)
                        setTextSize(terminalFontSize)
                        setTypeface(config.typeface(context))
                        setSpecialTypefaces(config.boxDrawingTypeface(context), config.nerdTypeface(context))
                        setTerminalViewClient(TerminalCallbacks(currentSession, zoomTerminal))
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) {
                                false
                            } else {
                                val keyName = TerminalClipboardSupport.keyName(keyCode)
                                val activeUserVariables = currentTerminalSession.value?.emulator?.userVariables.orEmpty()
                                val action = keyName?.let { name ->
                                    currentTerminalKeyBindings.value.lastOrNull {
                                        it.matches(name, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, activeUserVariables)
                                    }?.action
                                }
                                when (action) {
                                    TerminalKeyAction.COPY_TO_CLIPBOARD -> {
                                        // With no selection, preserve Ctrl+C as the terminal's interrupt key.
                                        sessionClient.copySelection()
                                    }
                                    TerminalKeyAction.PASTE_FROM_CLIPBOARD -> {
                                        sessionClient.pasteFromClipboard(currentTerminalSession.value)
                                        true
                                    }
                                    TerminalKeyAction.SEND_ESCAPE, TerminalKeyAction.SEND_CTRL_C,
                                    TerminalKeyAction.SEND_KEY_CTRL_C, TerminalKeyAction.SEND_KEY_F13,
                                    TerminalKeyAction.SEND_KEY_F14 -> {
                                        val bytes = action.terminalInput.orEmpty().toByteArray(StandardCharsets.UTF_8)
                                        currentTerminalSession.value?.write(bytes, 0, bytes.size)
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
                            val config = TerminalConfig(terminalFontFamily, terminalFontSize)
                            it.setTypeface(config.typeface(context))
                            it.setSpecialTypefaces(config.boxDrawingTypeface(context), config.nerdTypeface(context))
                            appliedFontFamily = terminalFontFamily
                        }
                    }
                )
                if (terminalProcessFinished) {
                    val exitStatus = currentSession.exitStatus
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color(0xE6212128))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            if (exitStatus < 0) {
                                "Terminal process ended after signal ${-exitStatus}. Start a new shell to continue."
                            } else "Terminal process exited with status $exitStatus. Start a new shell to continue.",
                            color = Color(0xFFE3E3E8),
                            textAlign = TextAlign.Center,
                            fontSize = 13.sp
                        )
                        Button(onClick = {
                            runCatching {
                                TerminalForegroundService.startSession(context)
                                session = terminalController.restartFinishedSession()
                                terminalProcessFinished = false
                            }.onFailure { failure ->
                                TerminalForegroundService.stop(context)
                                TerminalSessionHost.markSessionInactive(context)
                                diagnosticsMessage = "Could not start a new shell: ${failure.message ?: failure.javaClass.simpleName}"
                                TerminalDiagnostics.record(context, "shell_restart", "failed", mapOf("error" to failure.toString()))
                            }
                        }) { Text("Start new shell") }
                    }
                }
            }
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
                            TerminalSetupStage.IDLE -> if (reusableArchRootfs) {
                                "An Arch installation is present. Start the preconfigured Triton shell."
                            } else {
                                "Arch Linux ARM is ready to install with the Triton development environment."
                            }
                            TerminalSetupStage.DOWNLOADING -> "Downloading Arch Linux ARM…"
                            TerminalSetupStage.VERIFYING -> "Checking the downloaded Arch Linux ARM archive…"
                            TerminalSetupStage.CLEANING -> "Checking interrupted-install state without removing files…"
                            TerminalSetupStage.INSTALLING -> "Unpacking Arch Linux ARM…"
                            TerminalSetupStage.PREPARING -> "Preparing the Arch rootfs…"
                            TerminalSetupStage.DEVELOPER_TOOLS -> "Checking the prebuilt development environment…"
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
                    if (!setupReady && !ArchRootfsRelease.isConfigured()) {
                        Text(
                            "This APK is not pinned to a signed rootfs release yet.",
                            color = Color(0xFFFFCC80),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp
                        )
                    }
                    if (!setupReady && setupProgress.stage != TerminalSetupStage.READY) {
                        Text("Linux account · ${ArchGuestUser.NAME}", color = Color(0xFFBDBDBD), fontSize = 13.sp)
                    }
                    if (installState.running) {
                        Text(
                            "${setupProgress.stage.name.lowercase().replace('_', ' ')} · ${stageElapsedSeconds / 60}:${(stageElapsedSeconds % 60).toString().padStart(2, '0')}",
                            color = Color(0xFF8A8A8A),
                            fontSize = 11.sp
                        )
                        val total = setupProgress.total
                        if (setupProgress.stage == TerminalSetupStage.DOWNLOADING && total != null && total > 0) {
                            Text(
                                "${setupProgress.downloaded / (1024 * 1024)} / ${total / (1024 * 1024)} MiB · " +
                                    "${(setupProgress.downloaded * 100 / total).coerceIn(0, 100)}% · " +
                                    "${setupProgress.downloadBytesPerSecond / (1024 * 1024)} MiB/s",
                                color = Color(0xFF8A8A8A),
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
                            Text(progressText, color = Color(0xFF8A8A8A), fontSize = 12.sp)
                        }
                        if (setupProgress.stage == TerminalSetupStage.CLEANING) {
                            Text(
                                "${setupProgress.extractedEntries} installer entries checked",
                                color = Color(0xFF8A8A8A),
                                fontSize = 12.sp
                            )
                        }
                        if (setupProgress.stage == TerminalSetupStage.PREPARING && setupProgress.stepTotal > 0) {
                            Text(
                                "Preparation step ${setupProgress.stepCompleted} of ${setupProgress.stepTotal}",
                                color = Color(0xFF8A8A8A),
                                fontSize = 12.sp
                            )
                        }
                        if (setupProgress.stage == TerminalSetupStage.DEVELOPER_TOOLS) {
                            setupProgress.lastActivity?.let { activity ->
                                Text(activity.take(120), color = Color(0xFF8A8A8A), fontSize = 11.sp, maxLines = 2)
                            }
                            setupProgress.commandStatus?.let { status ->
                                Text(status.take(160), color = Color(0xFFBDBDBD), fontSize = 11.sp, maxLines = 2)
                            }
                        }
                        SetupStageProgress(
                            downloadFraction = when {
                                setupProgress.stage.ordinal >= TerminalSetupStage.VERIFYING.ordinal -> 1f
                                total != null && total > 0 -> setupProgress.downloaded.toFloat() / total
                                else -> null
                            },
                            installStage = setupProgress.stage,
                            installFraction = when (setupProgress.stage) {
                                TerminalSetupStage.PREPARING -> setupProgress.stepTotal.takeIf { it > 0 }?.let {
                                    setupProgress.stepCompleted.toFloat() / it
                                }
                                TerminalSetupStage.DEVELOPER_TOOLS -> null
                                else -> setupProgress.extractionTotal.takeIf { it > 0L }?.let {
                                    setupProgress.extractedBytes.toFloat() / it
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (!setupReady) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = !installState.running && ArchRootfsRelease.isConfigured(),
                                onClick = { ArchLinuxArmInstaller.start(context) }
                            ) { Text(if (installState.error != null) "Retry setup" else if (reusableArchRootfs) "Continue setup" else "Install Arch") }
                        }
                        if (setupError != null) {
                            Text(
                                "The rootfs and user files are preserved. Review Logs, then retry setup after resolving the reported issue.",
                                color = Color(0xFFFFCC80),
                                textAlign = TextAlign.Center,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        if (setupReady && keyboardVisible) {
            TerminalKeyboard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .offset {
                        // Keep the extra keys aligned with the live IME edge,
                        // while the terminal itself uses the stable target inset.
                        IntOffset(
                            x = 0,
                            y = imeTargetInsets.getBottom(this) - animatedImeInsets.getBottom(this)
                        )
                    },
                onZoom = zoomTerminal,
                onCopy = { sessionClient.copySelection() },
                onPaste = { sessionClient.pasteFromClipboard(session) },
                keyBindings = terminalKeyBindings,
                activeUserVariables = { currentTerminalSession.value?.emulator?.userVariables.orEmpty() },
                // Keep the Termux-style extra-key strip above Android's IME;
                // only the full custom keyboard is exclusive to built-in mode.
                compact = useSystemKeyboard,
                onInput = { input ->
                    val bytes = input.toByteArray(StandardCharsets.UTF_8)
                    session?.write(bytes, 0, bytes.size)
                }
            )
        }
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
                    terminalKeyBindings = parsed.keyBindings
                    showConfig = false
                    null
                }
            }
        )
    }
    if (showDiagnostics) {
        DiagnosticsScreen(
            recorderStatus = recorderStatusText,
            crashes = crashRecords,
            events = diagnosticEvents,
            message = diagnosticsMessage,
            testResult = smokeResult,
            busy = smokeRunning,
            exporting = exportRunning,
            onDismiss = { showDiagnostics = false },
            onTestSegv = {
                smokeRunning = true
                smokeResult = "Running controlled guest SIGSEGV test…"
                setupScope.launch {
                    val result = withContext(Dispatchers.IO) { RuntimeDiagnosticsProbes.runControlledGuestSegv(context) }
                    smokeRunning = false
                    smokeResult = result.summary()
                    diagnosticEvents = runCatching { TerminalDiagnostics.recentEvents(context) }.getOrDefault(emptyList())
                    recorderStatusText = TerminalDiagnostics.recorderStatusText(context)
                    crashRecords = TerminalDiagnostics.guestCrashRecords(context)
                }
            },
            onRunProbes = {
                smokeRunning = true
                smokeResult = "Running runtime probes…"
                setupScope.launch {
                    val results = withContext(Dispatchers.IO) { RuntimeDiagnosticsProbes.run(context) }
                    smokeRunning = false
                    smokeResult = results.joinToString("\n") { it.summary() }
                    diagnosticEvents = runCatching { TerminalDiagnostics.recentEvents(context) }.getOrDefault(emptyList())
                    recorderStatusText = TerminalDiagnostics.recorderStatusText(context)
                    crashRecords = TerminalDiagnostics.guestCrashRecords(context)
                }
            },
            onRunPermissionProbe = {
                smokeRunning = true
                smokeResult = "Checking Android file modes, PRoot guest access, and execution…"
                setupScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { ArchLinuxArmInstaller.runPermissionModelProbe(context) }
                    }
                    smokeRunning = false
                    diagnosticEvents = runCatching { TerminalDiagnostics.recentEvents(context) }.getOrDefault(emptyList())
                    smokeResult = result.fold(
                        onSuccess = { "Permission probe completed. Review the newest proot_permission_probe events below." },
                        onFailure = { "Permission probe failed: ${it.message ?: it.javaClass.simpleName}" }
                    )
                }
            },
            onRunPackageManagerProbe = {
                smokeRunning = true
                smokeResult = "Checking the selected fakeroot, library access, keyring files, and DNS…"
                setupScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { ArchLinuxArmInstaller.runPackageManagerProbe(context) }
                    }
                    smokeRunning = false
                    diagnosticEvents = runCatching { TerminalDiagnostics.recentEvents(context) }.getOrDefault(emptyList())
                    smokeResult = result.fold(
                        onSuccess = { "Package manager probe completed. Review the newest package_manager_probe event." },
                        onFailure = { "Package manager probe failed: ${it.message ?: it.javaClass.simpleName}" }
                    )
                }
            },
            onRepairPacmanKeyring = {
                smokeRunning = true
                smokeResult = "Rebuilding pacman's device-local key trust from the bundled official Arch Linux ARM keyring…"
                setupScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { ArchLinuxArmInstaller.repairPacmanKeyring(context) }
                    }
                    smokeRunning = false
                    diagnosticEvents = runCatching { TerminalDiagnostics.recentEvents(context) }.getOrDefault(emptyList())
                    smokeResult = result.fold(
                        onSuccess = { "Official Arch Linux ARM signing key trust was populated and verified. Signature checking remains enabled." },
                        onFailure = { "Pacman keyring repair failed: ${it.message ?: it.javaClass.simpleName}" }
                    )
                }
            },
            onCopyLatest = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                runCatching { TerminalDiagnostics.recentEventsText(context) }.onSuccess {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Recent TritonTerm diagnostics", it))
                    diagnosticsMessage = "Recent events copied (${it.toByteArray(Charsets.UTF_8).size} bytes)."
                }.onFailure { diagnosticsMessage = "Copy failed: ${it.message}" }
            },
            onCopyCrashes = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val crashes = TerminalDiagnostics.guestCrashRecordsText(context)
                if (crashes == "No guest SIGSEGV records captured.") diagnosticsMessage = crashes
                else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Guest SIGSEGV records", crashes))
                    diagnosticsMessage = "Guest crash records copied (${crashes.toByteArray(Charsets.UTF_8).size} bytes)."
                }
            },
            onShare = {
                runCatching { TerminalDiagnostics.recentExportText(context) }.onSuccess { text ->
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/x-ndjson"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share recent diagnostics"))
                    diagnosticsMessage = "Opened Android's share sheet for recent diagnostics."
                }.onFailure { diagnosticsMessage = "Share failed: ${it.message}" }
            },
            onShareFile = {
                runCatching {
                    val file = TerminalDiagnostics.createShareableDiagnosticsFile(context)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/x-ndjson"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(context.contentResolver, "TritonTerm diagnostics", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share diagnostics file"))
                    diagnosticsMessage = "Prepared ${file.length()} bytes and opened Android's share sheet."
                }.onFailure { diagnosticsMessage = "Could not prepare diagnostics file: ${it.message}" }
            },
            onCopyMessage = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostics export details", diagnosticsMessage))
            },
            onExport = { if (!exportRunning) exportDiagnostics.launch("tritonterm-diagnostics.jsonl") }
        )
    }
    if (showFeatureWarnings) {
        AlertDialog(
            onDismissRequest = { showFeatureWarnings = false },
            title = { Text("Image checks") },
            text = {
                SelectionContainer {
                    Text(
                        installState.featureWarnings.joinToString("\n"),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showFeatureWarnings = false }) { Text("Close") } }
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
                TerminalSetupStage.PREPARING -> "Prepare"
                TerminalSetupStage.DEVELOPER_TOOLS -> "Developer tools"
                else -> "Install"
            },
            fraction = when (installStage) {
                TerminalSetupStage.VERIFYING -> installFraction
                TerminalSetupStage.CLEANING -> null
                TerminalSetupStage.INSTALLING -> installFraction
                TerminalSetupStage.PREPARING, TerminalSetupStage.DEVELOPER_TOOLS -> installFraction
                else -> 0f
            },
            active = installStage in setOf(
                TerminalSetupStage.VERIFYING,
                TerminalSetupStage.INSTALLING,
                TerminalSetupStage.DEVELOPER_TOOLS
            ) &&
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
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val offset = if (fraction == null && active && animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "${title}Progress")
        val animatedOffset by transition.animateFloat(
            initialValue = -0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 5_000)),
            label = "${title}ProgressOffset"
        )
        animatedOffset
    } else {
        0f
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = Color(0xFF929292), fontSize = 11.sp)
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(Color(0xFF292929), cornerRadius = radius)
            if (fraction != null) {
                val width = size.width * fraction.coerceIn(0f, 1f)
                if (width > 0f) {
                    drawRoundRect(
                        Color(0xFFBDBDBD),
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
                        Color(0xFFBDBDBD),
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
                    "Options: font_family, font_size, and Kitty-style key maps for clipboard and terminal keys.",
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
