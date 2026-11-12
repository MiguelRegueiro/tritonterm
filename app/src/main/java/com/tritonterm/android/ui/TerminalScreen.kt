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
import androidx.compose.runtime.mutableLongStateOf
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
import com.tritonterm.android.terminal.ArchDeveloperSetupLog
import com.tritonterm.android.terminal.ArchLinuxArmBootstrap
import com.tritonterm.android.terminal.ArchRootfsRelease
import com.tritonterm.android.terminal.TerminalDiagnostics
import com.tritonterm.android.terminal.DiagnosticEvent
import com.tritonterm.android.terminal.GuestCrashRecord
import com.tritonterm.android.terminal.RuntimeDiagnosticsProbes
import com.tritonterm.android.terminal.TerminalSetupProgress
import com.tritonterm.android.terminal.TerminalSetupProgressFile
import com.tritonterm.android.terminal.TerminalSetupStage
import com.tritonterm.android.terminal.ArchLoginName
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

@Composable
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
    var setupFailureDetails by remember(context) { mutableStateOf(ArchLinuxArmInstaller.state.value.failureDetails) }
    var showSetupLog by remember(context) { mutableStateOf(false) }
    var setupLogText by remember(context) { mutableStateOf("") }
    var setupWarning by remember(context) { mutableStateOf<String?>(null) }
    var setupProgress by remember(context) {
        mutableStateOf(TerminalSetupProgress(TerminalSetupStage.IDLE))
    }
    var stageElapsedSeconds by remember(context) { mutableLongStateOf(0L) }
    var archUsername by remember(context) {
        mutableStateOf(
            ArchLoginName.fromRootfs(ArchLinuxArmBootstrap.rootfs(context))
                ?: ArchLoginName.pendingUsername(ArchLinuxArmBootstrap.rootfs(context))
                ?: context.getSharedPreferences("arch_setup", Context.MODE_PRIVATE).getString("username", "dev")
                ?: "dev"
        )
    }
    var session by remember(terminalController) { mutableStateOf<TerminalSession?>(null) }
    var terminalProcessFinished by remember(terminalController) { mutableStateOf(false) }
    val setupScope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(terminalController) {
        if (terminalController.isReady(context)) {
            if (!ArchLinuxArmInstaller.repositoryRefreshComplete(context)) {
                setupReady = false
                ArchLinuxArmInstaller.ensureRepositoryDatabases(context)
                return@LaunchedEffect
            }
            session = terminalController.start()
            setupReady = true
            return@LaunchedEffect
        }
        setupReady = false
    }

    LaunchedEffect(installState) {
        setupProgress = installState.progress
        setupError = installState.error
        setupFailureDetails = installState.failureDetails
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

    LaunchedEffect(context, setupReady, reusableArchRootfs, installState.preflightPassed,
        installState.preflightFailed, installState.running, installState.error) {
        if (!setupReady && !reusableArchRootfs && !installState.preflightPassed &&
            !installState.preflightFailed && !installState.running && installState.error == null
        ) {
            // Keep the harmless native check automatic and quiet. The install action stays explicit.
            ArchLinuxArmInstaller.runPreflight(context)
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
                    diagnosticEvents = runCatching { TerminalDiagnostics.recentEvents(context) }.getOrDefault(emptyList())
                    recorderStatusText = TerminalDiagnostics.recorderStatusText(context)
                    crashRecords = TerminalDiagnostics.guestCrashRecords(context)
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
                color = Color(0xFF8A8A8A)
            )
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
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
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
                                session = terminalController.restartFinishedSession()
                                terminalProcessFinished = false
                                TerminalForegroundService.startSession(context)
                            }.onFailure { failure ->
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
                        setupFailureDetails?.let { details ->
                            buildString {
                                append("Developer setup failed")
                                details.stage?.let { append(" at $it") }
                                append(" (exit ${details.exitCode}).")
                            }
                        } ?: setupError ?: when (setupProgress.stage) {
                            TerminalSetupStage.IDLE -> if (reusableArchRootfs) {
                                "An Arch installation is present. Continue setup to install TritonTerm's default developer tools."
                            } else if (installState.preflightPassed) {
                                "Arch Linux ARM is ready to install with Fish, Starship, Rustup, Paru, and TCP fakeroot."
                            } else "Arch Linux ARM is not installed."
                            TerminalSetupStage.DOWNLOADING -> "Downloading Arch Linux ARM…"
                            TerminalSetupStage.VERIFYING -> "Checking the downloaded Arch Linux ARM archive…"
                            TerminalSetupStage.CLEANING -> "Checking interrupted-install state without removing files…"
                            TerminalSetupStage.INSTALLING -> "Unpacking Arch Linux ARM…"
                            TerminalSetupStage.PREPARING -> if (setupProgress.stepCompleted >= 9) {
                                "Installing Rustup, Fish, Starship, Paru, and TCP fakeroot…"
                            } else "Preparing the Arch userland for TritonTerm…"
                            TerminalSetupStage.DEVELOPER_TOOLS -> "Installing Rustup, Fish, Starship, Paru, and TCP fakeroot…"
                            TerminalSetupStage.INITIALIZING -> "Initializing Arch package keys…"
                            TerminalSetupStage.READY -> "Starting shell…"
                            TerminalSetupStage.FAILED -> "Setup failed."
                        },
                        color = if (setupError == null) Color(0xFFD5D5D5) else Color(0xFFFF8A80),
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp
                    )
                    setupFailureDetails?.let { details ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            details.command?.let { command ->
                                Text("Command: $command", color = Color(0xFFBDBDBD), fontSize = 11.sp, maxLines = 2)
                            }
                            details.message?.takeIf { it.isNotBlank() }?.let { message ->
                                Text(message, color = Color(0xFFFFCC80), fontSize = 11.sp, maxLines = 3)
                            }
                            details.stderr.takeLast(3).takeIf { it.isNotEmpty() }?.let { lines ->
                                Text(lines.joinToString("\n"), color = Color(0xFFFFAB91), fontSize = 10.sp, maxLines = 4)
                            }
                        }
                    }
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
                        val passwd = java.io.File(ArchLinuxArmBootstrap.rootfs(context), "etc/passwd")
                        val group = java.io.File(ArchLinuxArmBootstrap.rootfs(context), "etc/group")
                        val root = ArchLinuxArmBootstrap.rootfs(context)
                        val usernameError = ArchLoginName.validate(
                            archUsername,
                            passwd.takeIf { it.isFile }?.readText().orEmpty(),
                            ArchLoginName.isPersonalizationPending(root, archUsername) || ArchLoginName.fromRootfs(root) == archUsername,
                            group.takeIf { it.isFile }?.readText().orEmpty()
                        )
                        OutlinedTextField(
                            value = archUsername,
                            onValueChange = { archUsername = it.take(32) },
                            enabled = !installState.running && !reusableArchRootfs,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Linux username") },
                            supportingText = {
                                Text(usernameError ?: "Your login name and home directory; default: dev")
                            },
                            isError = usernameError != null
                        )
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
                            if (installState.preflightFailed) {
                                TextButton(
                                    enabled = !installState.running,
                                    onClick = { ArchLinuxArmInstaller.runPreflight(context) }
                                ) { Text("Retry check") }
                            }
                            val passwd = java.io.File(ArchLinuxArmBootstrap.rootfs(context), "etc/passwd")
                            val group = java.io.File(ArchLinuxArmBootstrap.rootfs(context), "etc/group")
                            val usernameError = ArchLoginName.validate(
                                archUsername,
                                passwd.takeIf { it.isFile }?.readText().orEmpty(),
                                ArchLoginName.isPersonalizationPending(ArchLinuxArmBootstrap.rootfs(context), archUsername) ||
                                    ArchLoginName.fromRootfs(ArchLinuxArmBootstrap.rootfs(context)) == archUsername,
                                group.takeIf { it.isFile }?.readText().orEmpty()
                            )
                            Button(
                                enabled = !installState.running && usernameError == null && ArchRootfsRelease.isConfigured() &&
                                    (reusableArchRootfs || installState.preflightPassed) && !installState.preflightFailed,
                                onClick = { ArchLinuxArmInstaller.start(context, archUsername) }
                            ) { Text(if (installState.error != null) "Retry setup" else if (reusableArchRootfs) "Continue setup" else "Install Arch") }
                        }
                        if (setupError != null) {
                            Text(
                                "The rootfs and user files are preserved. Review Logs, then retry setup after resolving the reported issue.",
                                color = Color(0xFFFFCC80),
                                textAlign = TextAlign.Center,
                                fontSize = 12.sp
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    setupLogText = ArchDeveloperSetupLog.read(context)
                                    showSetupLog = true
                                }) { Text("View setup log") }
                                TextButton(onClick = {
                                    runCatching {
                                        val file = TerminalDiagnostics.createShareableSetupLogFile(context)
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            clipData = ClipData.newUri(context.contentResolver, "Arch setup log", uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share Arch setup log"))
                                    }.onFailure { failure ->
                                        Toast.makeText(context, "Could not share setup log: ${failure.message}", Toast.LENGTH_LONG).show()
                                    }
                                }) { Text("Share setup log") }
                            }
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
    if (showSetupLog) {
        AlertDialog(
            onDismissRequest = { showSetupLog = false },
            title = { Text("Arch setup log") },
            text = {
                SelectionContainer {
                    Text(
                        setupLogText,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Arch setup log", setupLogText))
                    Toast.makeText(context, "Setup log copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = { showSetupLog = false }) { Text("Close") }
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
