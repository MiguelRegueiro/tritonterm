package com.tritonterm.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.Menu
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.tritonterm.android.config.TerminalConfig
import com.tritonterm.android.config.TerminalConfigSyntaxHighlighter
import com.tritonterm.android.R
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
import com.tritonterm.android.terminal.TerminalCallbacks
import com.tritonterm.android.terminal.TerminalClipboardSupport
import com.tritonterm.android.config.TerminalKeyAction
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    val rootfsReadyAtStartup = remember(terminalController) {
        terminalController.isReady(context)
    }
    var pacmanLockWarning by remember(context) { mutableStateOf(false) }
    var setupReady by remember(terminalController) { mutableStateOf(rootfsReadyAtStartup) }
    var shellStarting by remember(terminalController) { mutableStateOf(false) }
    var shellStartupError by remember(terminalController) { mutableStateOf<String?>(null) }
    var setupError by remember(context) { mutableStateOf<String?>(null) }
    var showFeatureWarnings by remember(context) { mutableStateOf(false) }
    var setupWarning by remember(context) { mutableStateOf<String?>(null) }
    var setupProgress by remember(context) {
        mutableStateOf(TerminalSetupProgress(TerminalSetupStage.IDLE))
    }
    var installElapsedSeconds by remember(context) { mutableLongStateOf(0L) }
    var session by remember(terminalController) { mutableStateOf<TerminalSession?>(null) }
    val currentTerminalSession = rememberUpdatedState(session)
    var terminalProcessFinished by remember(terminalController) { mutableStateOf(false) }
    val setupScope = androidx.compose.runtime.rememberCoroutineScope()
    val sessionClient = terminalController.client
    val configFile = remember(context) { TerminalConfig.file(context.filesDir) }
    val initialConfig = remember(configFile) { TerminalConfig.loadOrCreate(configFile) }
    var terminalConfig by remember { mutableStateOf(initialConfig) }
    var terminalFontSize by remember { mutableIntStateOf(initialConfig.fontSize) }
    var terminalFontFamily by remember { mutableStateOf(initialConfig.fontFamily) }
    var terminalKeyBindings by remember { mutableStateOf(initialConfig.keyBindings) }
    val currentTerminalKeyBindings = rememberUpdatedState(terminalKeyBindings)
    var appliedFontSize by remember { mutableIntStateOf(terminalFontSize) }
    var appliedFontFamily by remember { mutableStateOf(terminalFontFamily) }

    val startInteractiveShell: () -> Unit = {
        if (!shellStarting && session == null) {
            // Initialize cursor defaults before Fish starts. Shell startup may
            // emit its own cursor shape before AndroidView gets attached.
            sessionClient.setTerminalConfig(terminalConfig)
            shellStarting = true
            shellStartupError = null
            setupScope.launch {
                try {
                    // Post the ongoing notification before file preparation and PRoot startup.
                    TerminalForegroundService.startSession(context)
                    withContext(Dispatchers.IO) { terminalController.prepareForStart() }
                    // TerminalSession creates its main-thread Handler in the constructor.
                    session = terminalController.startPrepared()
                    setupReady = true
                    shellStarting = false
                    terminalProcessFinished = false
                } catch (cancelled: CancellationException) {
                    TerminalForegroundService.stop(context)
                    TerminalSessionHost.markSessionInactive(context)
                    shellStarting = false
                    throw cancelled
                } catch (failure: Exception) {
                    TerminalForegroundService.stop(context)
                    TerminalSessionHost.markSessionInactive(context)
                    shellStarting = false
                    shellStartupError = failure.message ?: "The terminal shell could not start."
                    TerminalDiagnostics.record(context, "shell_startup", "failed", mapOf("error" to shellStartupError))
                }
            }
        }
    }

    LaunchedEffect(terminalController, rootfsReadyAtStartup) {
        if (rootfsReadyAtStartup) {
            startInteractiveShell()
        } else {
            setupReady = false
            shellStarting = false
        }
    }

    LaunchedEffect(installState) {
        setupProgress = installState.progress
        setupError = installState.error
        if (installState.ready && !installState.running && session == null && !shellStarting) {
            setupReady = true
            startInteractiveShell()
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

    LaunchedEffect(installState.running) {
        if (!installState.running) return@LaunchedEffect
        val started = android.os.SystemClock.elapsedRealtime()
        while (ArchLinuxArmInstaller.state.value.running) {
            installElapsedSeconds = (android.os.SystemClock.elapsedRealtime() - started) / 1_000L
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
    var showConfig by remember { mutableStateOf(false) }
    var configText by remember { mutableStateOf("") }
    var showDiagnostics by remember { mutableStateOf(false) }
    var diagnosticEvents by remember { mutableStateOf(emptyList<DiagnosticEvent>()) }
    var crashRecords by remember { mutableStateOf(emptyList<GuestCrashRecord>()) }
    var recorderStatusText by remember { mutableStateOf("Recorder status unknown.") }
    var diagnosticsMessage by remember { mutableStateOf("") }
    var smokeRunning by remember { mutableStateOf(false) }
    var sharingLogs by remember { mutableStateOf(false) }
    var smokeResult by remember { mutableStateOf("Not run") }
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
    val appliedCursorConfig = remember { arrayOfNulls<TerminalConfig>(1) }
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
    LaunchedEffect(useSystemKeyboard, keyboardVisible) {
        if (useSystemKeyboard && keyboardVisible) {
            // Let Compose move the terminal into its system-keyboard layout before
            // asking Android to attach the IME to its view.
            delay(120)
            showSystemKeyboard()
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
        }
    }
    val toggleKeyboardVisibility: () -> Unit = {
        if (pendingBuiltInKeyboard) pendingBuiltInKeyboard = false
        keyboardVisible = !keyboardVisible
        if (useSystemKeyboard) {
            if (!keyboardVisible) hideSystemKeyboard()
        }
    }
    val openDiagnostics: () -> Unit = {
        diagnosticEvents = runCatching { TerminalDiagnostics.recentEvents(context) }.getOrDefault(emptyList())
        recorderStatusText = TerminalDiagnostics.recorderStatusText(context)
        crashRecords = TerminalDiagnostics.guestCrashRecords(context)
        diagnosticsMessage = ""
        showDiagnostics = true
    }
    if (showConfig) {
        TerminalConfigScreen(
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
                    terminalConfig = parsed
                    terminalFontSize = parsed.fontSize
                    terminalFontFamily = parsed.fontFamily
                    terminalKeyBindings = parsed.keyBindings
                    showConfig = false
                    null
                }
            }
        )
    } else if (showDiagnostics) {
        // Keep the terminal view and its keyboard out of the composition while
        // the full-screen Logs page is active.
        Box(Modifier.fillMaxSize().background(Color.Black))
    } else {
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
        val currentSession = session
        val terminalInteractive = setupReady && currentSession != null
        Row(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                configText = configFile.readText()
                showConfig = true
            }) { Text("Config", fontSize = 12.sp) }
            TextButton(onClick = openDiagnostics) { Text("Logs", fontSize = 12.sp) }
            if (terminalInteractive) {
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

        if (pacmanLockWarning && terminalInteractive) {
            Text(
                "A pacman database lock has remained without an active pacman process. It may be stale or left by an interrupted transaction. TritonTerm will not remove it; verify pacman is stopped before recovering the lock.",
                modifier = Modifier.fillMaxWidth().background(Color(0xFF3B2D1A)).padding(horizontal = 12.dp, vertical = 8.dp),
                color = Color(0xFFFFCC80),
                fontSize = 12.sp
            )
        }

        if (terminalInteractive && installState.featureWarnings.isNotEmpty()) {
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
        val terminalPane: @Composable (Modifier) -> Unit = { paneModifier ->
        if (terminalInteractive) {
            Box(modifier = paneModifier.fillMaxSize()) {
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
                        sessionClient.setTerminalConfig(terminalConfig)
                        appliedCursorConfig[0] = terminalConfig
                        terminalViewHolder[0] = this
                            setBackgroundColor(android.graphics.Color.BLACK)
                            isFocusable = true
                            isFocusableInTouchMode = true
                        }
                    },
                    update = {
                        it.attachSession(currentSession)
                        if (appliedCursorConfig[0] != terminalConfig) {
                            sessionClient.setTerminalConfig(terminalConfig)
                            appliedCursorConfig[0] = terminalConfig
                        }
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
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF242426),
                        border = BorderStroke(1.dp, Color(0xFF3A3A3D))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                if (exitStatus < 0) "Shell stopped · signal ${-exitStatus}"
                                else "Shell exited · status $exitStatus",
                                modifier = Modifier.weight(1f),
                                color = Color(0xFFE3E3E8),
                                fontSize = 13.sp,
                                maxLines = 2
                            )
                            TritonActionButton(
                                label = if (shellStarting) "Starting…" else "New shell",
                                enabled = !shellStarting,
                                onClick = {
                                    shellStarting = true
                                    setupScope.launch {
                                        try {
                                            TerminalForegroundService.startSession(context)
                                            withContext(Dispatchers.IO) { terminalController.prepareForStart() }
                                            session = terminalController.restartFinishedSessionPrepared()
                                            terminalProcessFinished = false
                                            shellStarting = false
                                        } catch (cancelled: CancellationException) {
                                            TerminalForegroundService.stop(context)
                                            TerminalSessionHost.markSessionInactive(context)
                                            shellStarting = false
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            TerminalForegroundService.stop(context)
                                            TerminalSessionHost.markSessionInactive(context)
                                            shellStarting = false
                                            diagnosticsMessage = "Could not start a new shell: ${failure.message ?: failure.javaClass.simpleName}"
                                            TerminalDiagnostics.record(context, "shell_restart", "failed", mapOf("error" to failure.toString()))
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } else if (setupReady && shellStartupError == null) {
            Box(modifier = paneModifier.fillMaxSize().background(Color.Black))
        } else if (setupReady) {
            Box(
                modifier = paneModifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("The terminal couldn’t start", color = Color(0xFFF1F1F1), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(shellStartupError.orEmpty(), color = Color(0xFFFF8A80), fontSize = 12.sp, textAlign = TextAlign.Center)
                    TritonActionButton(
                        label = if (shellStarting) "Starting…" else "Retry terminal",
                        enabled = !shellStarting,
                        onClick = startInteractiveShell
                    )
                }
            }
        } else {
            val phases = listOf("Download image", "Verify image", "Install image", "Check development tools", "Prepare package signing")
            val currentStage = setupProgress.stage
            val downloadTotal = setupProgress.total
            val downloadFraction = downloadTotal?.takeIf { it > 0L }?.let {
                (setupProgress.downloaded.toFloat() / it).coerceIn(0f, 1f)
            }
            val verifyFraction = setupProgress.extractionTotal.takeIf { currentStage == TerminalSetupStage.VERIFYING && it > 0L }?.let {
                (setupProgress.extractedBytes.toFloat() / it).coerceIn(0f, 1f)
            }
            val installFraction = setupProgress.extractionTotal.takeIf { currentStage == TerminalSetupStage.INSTALLING && it > 0L }?.let {
                (setupProgress.extractedBytes.toFloat() / it).coerceIn(0f, 1f)
            }
            val phaseActive = when (currentStage) {
                TerminalSetupStage.DOWNLOADING -> 0
                TerminalSetupStage.VERIFYING -> 1
                TerminalSetupStage.CLEANING, TerminalSetupStage.INSTALLING -> 2
                TerminalSetupStage.DEVELOPER_TOOLS -> 3
                TerminalSetupStage.PREPARING, TerminalSetupStage.INITIALIZING -> 4
                else -> -1
            }
            val extracted = reusableArchRootfs || currentStage in setOf(
                TerminalSetupStage.PREPARING,
                TerminalSetupStage.DEVELOPER_TOOLS,
                TerminalSetupStage.INITIALIZING,
                TerminalSetupStage.READY
            )
            val phaseDone = listOf(
                extracted || currentStage in setOf(TerminalSetupStage.VERIFYING, TerminalSetupStage.CLEANING,
                    TerminalSetupStage.INSTALLING, TerminalSetupStage.PREPARING, TerminalSetupStage.DEVELOPER_TOOLS,
                    TerminalSetupStage.INITIALIZING, TerminalSetupStage.READY),
                extracted || currentStage in setOf(TerminalSetupStage.CLEANING, TerminalSetupStage.INSTALLING,
                    TerminalSetupStage.PREPARING, TerminalSetupStage.DEVELOPER_TOOLS,
                    TerminalSetupStage.INITIALIZING, TerminalSetupStage.READY) ||
                    (currentStage == TerminalSetupStage.VERIFYING && verifyFraction == 1f),
                extracted || currentStage in setOf(TerminalSetupStage.PREPARING, TerminalSetupStage.DEVELOPER_TOOLS,
                    TerminalSetupStage.INITIALIZING, TerminalSetupStage.READY) ||
                    (currentStage == TerminalSetupStage.INSTALLING && installFraction == 1f),
                currentStage in setOf(TerminalSetupStage.PREPARING, TerminalSetupStage.INITIALIZING, TerminalSetupStage.READY),
                currentStage == TerminalSetupStage.READY
            )
            val phaseDetails = listOf(
                when {
                    phaseDone[0] -> "Signed image downloaded"
                    currentStage == TerminalSetupStage.DOWNLOADING && downloadTotal != null && downloadTotal > 0L ->
                        "${formatMiB(setupProgress.downloaded)} / ${formatMiB(downloadTotal)} · ${downloadFraction?.times(100)?.toInt() ?: 0}% · ${formatMiB(setupProgress.downloadBytesPerSecond)}/s"
                    currentStage == TerminalSetupStage.DOWNLOADING -> "Fetching the signed release metadata and image"
                    else -> "Arch Linux ARM · ${ArchRootfsRelease.pinnedVersion()}"
                },
                when {
                    phaseDone[1] -> "Signed manifest and SHA-256 verified"
                    currentStage == TerminalSetupStage.VERIFYING && setupProgress.extractionTotal > 0L ->
                        "${formatMiB(setupProgress.extractedBytes)} / ${formatMiB(setupProgress.extractionTotal)} · ${verifyFraction?.times(100)?.toInt() ?: 0}%"
                    else -> "Authenticate the image before installation"
                },
                when {
                    phaseDone[2] -> "Image unpacked into app storage"
                    currentStage == TerminalSetupStage.CLEANING -> "Preparing the install destination · ${setupProgress.extractedEntries} entries"
                    currentStage == TerminalSetupStage.INSTALLING && setupProgress.extractionTotal > 0L ->
                        "${formatMiB(setupProgress.extractedBytes)} / ${formatMiB(setupProgress.extractionTotal)} · ${installFraction?.times(100)?.toInt() ?: 0}% · ${setupProgress.extractedEntries} entries"
                    else -> "Unpack the verified image into app storage"
                },
                if (phaseDone[3]) "Required development tools checked" else "Testing the shell, developer tools, package database and permissions.",
                if (phaseDone[4]) "Device-local package signing trust is ready" else "Initialize pacman’s signing keys for this device"
            )
            val phaseProgress = listOf(downloadFraction, verifyFraction, installFraction, null, null)

            Box(
                modifier = paneModifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Image(
                                painter = painterResource(R.drawable.triton_trident),
                                contentDescription = null,
                                modifier = Modifier.width(22.dp).height(40.dp)
                            )
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("TritonTerm", color = Color(0xFFF1F1F1), fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                                Text("Arch Linux ARM", color = Color(0xFFAAAAAA), fontSize = 13.sp)
                            }
                            Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF292929)) {
                                Text(
                                    ArchRootfsRelease.pinnedVersion(),
                                    color = Color(0xFFDEA016),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF171717),
                        border = BorderStroke(1.dp, Color(0xFF303030))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            phases.forEachIndexed { index, title ->
                                val completed = phaseDone[index]
                                val active = installState.running && phaseActive == index && !completed
                                val failed = setupError != null && phaseActive == index && !completed
                                SetupPhaseRow(
                                    index = index + 1,
                                    title = title,
                                    detail = phaseDetails[index],
                                    state = when {
                                        completed -> SetupPhaseState.COMPLETE
                                        failed -> SetupPhaseState.FAILED
                                        active -> SetupPhaseState.ACTIVE
                                        else -> SetupPhaseState.PENDING
                                    },
                                    progress = if (active) phaseProgress[index] else null
                                )
                            }
                        }
                    }

                    if (installState.running) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (phaseActive >= 0) "Step ${phaseActive + 1} of ${phases.size}" else "Finishing setup",
                                color = Color(0xFFBDBDBD), fontSize = 13.sp
                            )
                            Text("Elapsed ${formatElapsed(installElapsedSeconds)}", color = Color(0xFFBDBDBD), fontSize = 13.sp)
                        }
                    } else if (!setupReady) {
                        setupError?.let {
                            Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp)
                        }
                        setupWarning?.let {
                            Text(it, color = Color(0xFFFFCC80), fontSize = 12.sp)
                        }
                        if (!ArchRootfsRelease.isConfigured()) {
                            Text("This build is not pinned to a signed Linux image release.", color = Color(0xFFFFCC80), fontSize = 12.sp)
                        }
                        TritonActionButton(
                            label = if (setupError != null) "Retry setup" else if (reusableArchRootfs) "Continue setup" else "Install Arch Linux ARM",
                            enabled = ArchRootfsRelease.isConfigured(),
                            onClick = { ArchLinuxArmInstaller.start(context) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "The verified archive is removed automatically after setup completes.",
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF888888), fontSize = 12.sp, textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
        }

        val keyboardContent: @Composable (Modifier, Boolean) -> Unit = { keyboardModifier, landscapeLayout ->
            TerminalKeyboard(
                modifier = keyboardModifier,
                onZoom = zoomTerminal,
                onCopy = { sessionClient.copySelection() },
                onPaste = { sessionClient.pasteFromClipboard(session) },
                keyBindings = terminalKeyBindings,
                activeUserVariables = { currentTerminalSession.value?.emulator?.userVariables.orEmpty() },
                // Keep the Termux-style extra-key strip above Android's IME;
                // only the full custom keyboard is exclusive to built-in mode.
                compact = useSystemKeyboard,
                landscapeLayout = landscapeLayout,
                onInput = { input ->
                    val bytes = input.toByteArray(StandardCharsets.UTF_8)
                    session?.write(bytes, 0, bytes.size)
                }
            )
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val sideBySide = maxWidth > maxHeight && terminalInteractive && keyboardVisible && !useSystemKeyboard
            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    terminalPane(Modifier.weight(0.56f).fillMaxHeight())
                    keyboardContent(
                        Modifier.weight(0.44f).fillMaxHeight().padding(start = 2.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        true
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    terminalPane(Modifier.weight(1f).fillMaxWidth())
                    if (terminalInteractive && keyboardVisible) {
                        keyboardContent(
                            Modifier
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
                            false
                        )
                    }
                }
            }
        }
    }
    }
    if (showDiagnostics) {
        DiagnosticsScreen(
            crashes = crashRecords,
            events = diagnosticEvents,
            message = diagnosticsMessage,
            testResult = smokeResult,
            busy = smokeRunning,
            sharing = sharingLogs,
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
            onShareFile = {
                if (!sharingLogs) {
                    sharingLogs = true
                    diagnosticsMessage = "Preparing log file…"
                    setupScope.launch {
                        val fileResult = runCatching {
                            withContext(Dispatchers.IO) { TerminalDiagnostics.createShareableDiagnosticsFile(context) }
                        }
                        sharingLogs = false
                        fileResult.fold(
                            onSuccess = { file ->
                                runCatching {
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/x-ndjson"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        clipData = ClipData.newUri(context.contentResolver, "TritonTerm log file", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share log file"))
                                }.onSuccess {
                                    diagnosticsMessage = "Log file ready · ${file.length()} bytes."
                                }.onFailure {
                                    diagnosticsMessage = "Could not share log file: ${it.message ?: it.javaClass.simpleName}"
                                }
                            },
                            onFailure = {
                                diagnosticsMessage = "Could not prepare log file: ${it.message ?: it.javaClass.simpleName}"
                            }
                        )
                    }
                }
            },
            onCopyLog = { rawLog ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("TritonTerm log entry", rawLog))
                Toast.makeText(context, "Log entry copied", Toast.LENGTH_SHORT).show()
            },
            onClear = {
                diagnosticsMessage = "Clearing logs…"
                setupScope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) { TerminalDiagnostics.clear(context) } }
                    diagnosticEvents = runCatching { TerminalDiagnostics.recentEvents(context) }.getOrDefault(emptyList())
                    recorderStatusText = TerminalDiagnostics.recorderStatusText(context)
                    crashRecords = TerminalDiagnostics.guestCrashRecords(context)
                    diagnosticsMessage = result.fold(
                        onSuccess = { "Logs cleared. New activity will be recorded as usual." },
                        onFailure = { "Could not clear logs: ${it.message ?: it.javaClass.simpleName}" }
                    )
                }
            },
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


private enum class SetupPhaseState { COMPLETE, ACTIVE, PENDING, FAILED }

@Composable
private fun SetupPhaseRow(
    index: Int,
    title: String,
    detail: String,
    state: SetupPhaseState,
    progress: Float?
) {
    val accent = Color(0xFFDEA016)
    val foreground = when (state) {
        SetupPhaseState.COMPLETE, SetupPhaseState.ACTIVE -> Color(0xFFF0F0F0)
        SetupPhaseState.PENDING -> Color(0xFF777777)
        SetupPhaseState.FAILED -> Color(0xFFFF8A80)
    }
    val secondary = when (state) {
        SetupPhaseState.COMPLETE -> Color(0xFF9B9B9B)
        SetupPhaseState.ACTIVE -> Color(0xFFC7C7C7)
        SetupPhaseState.PENDING -> Color(0xFF666666)
        SetupPhaseState.FAILED -> Color(0xFFFF8A80)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = when (state) {
            SetupPhaseState.ACTIVE -> Color(0xFF242119)
            SetupPhaseState.FAILED -> Color(0xFF261B1B)
            else -> Color.Transparent
        },
        border = when (state) {
            SetupPhaseState.ACTIVE -> BorderStroke(1.dp, Color(0xFF6D531C))
            SetupPhaseState.FAILED -> BorderStroke(1.dp, Color(0xFF6B3434))
            else -> null
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = RoundedCornerShape(50),
                    color = when (state) {
                        SetupPhaseState.COMPLETE -> Color(0xFF193323)
                        SetupPhaseState.ACTIVE -> Color(0xFF4A3917)
                        SetupPhaseState.FAILED -> Color(0xFF492323)
                        SetupPhaseState.PENDING -> Color(0xFF292929)
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            when (state) {
                                SetupPhaseState.COMPLETE -> "✓"
                                SetupPhaseState.FAILED -> "!"
                                else -> index.toString()
                            },
                            color = when (state) {
                                SetupPhaseState.COMPLETE -> Color(0xFF8FD0A0)
                                SetupPhaseState.ACTIVE -> accent
                                SetupPhaseState.FAILED -> Color(0xFFFF8A80)
                                SetupPhaseState.PENDING -> Color(0xFF777777)
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = foreground, fontSize = 14.sp, fontWeight = if (state == SetupPhaseState.ACTIVE) FontWeight.SemiBold else FontWeight.Medium)
                    Text(detail, color = secondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (state == SetupPhaseState.ACTIVE) {
                    Text("ACTIVE", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (state == SetupPhaseState.ACTIVE && progress != null) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0xFF393939), RoundedCornerShape(8.dp))) {
                    Box(
                        Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(5.dp)
                            .background(accent, RoundedCornerShape(8.dp))
                    )
                }
            }
        }
    }
}

private fun formatMiB(bytes: Long): String = "${bytes.coerceAtLeast(0L) / (1024L * 1024L)} MiB"

private fun formatElapsed(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
    else "${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
}


@Composable
private fun TerminalConfigScreen(
    text: String,
    onTextChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> String?
) {
    var error by remember { mutableStateOf<String?>(null) }
    var editorValue by remember {
        mutableStateOf(
            TextFieldValue(
                annotatedString = TerminalConfigSyntaxHighlighter.highlight(text),
                selection = TextRange(text.length)
            )
        )
    }
    var showDiscardConfirmation by remember { mutableStateOf(false) }
    val originalText = remember { text }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val requestLeave = {
        if (text != originalText) {
            showDiscardConfirmation = true
        } else {
            focusManager.clearFocus()
            onDismiss()
        }
    }
    BackHandler { requestLeave() }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .background(Color.Black)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 16.dp)
        ) {
            TextButton(onClick = {
                requestLeave()
            }, modifier = Modifier.align(Alignment.CenterStart)) { Text("← Terminal") }
            Text(
                "tritonterm.conf",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = editorValue,
                onValueChange = { updated ->
                    editorValue = updated.copy(
                        annotatedString = TerminalConfigSyntaxHighlighter.highlight(updated.text)
                    )
                    onTextChanged(updated.text)
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = TerminalConfigSyntaxHighlighter.foreground
                ),
                shape = MaterialTheme.shapes.medium
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    focusManager.clearFocus()
                    onDismiss()
                }) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                TritonActionButton(label = "Save & apply", onClick = {
                    error = onSave(editorValue.text)
                    if (error == null) focusManager.clearFocus()
                })
            }
        }
    }
    if (showDiscardConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirmation = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your edits to tritonterm.conf have not been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirmation = false
                    focusManager.clearFocus()
                    onDismiss()
                }) { Text("Discard changes", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirmation = false }) { Text("Keep editing") }
            }
        )
    }
}
