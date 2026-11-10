package com.tritonterm.android.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

internal class TerminalSessionController(
    context: Context,
    backend: TerminalBackend = AndroidShellBackend()
) {
    val client = TritonSessionClient()
    val session = backend.start(context, client)

    fun close() {
        client.terminalView = null
        session.finishIfRunning()
    }
}

internal interface TerminalBackend {
    fun start(context: Context, client: TerminalSessionClient): TerminalSession
}

internal class AndroidShellBackend : TerminalBackend {
    override fun start(context: Context, client: TerminalSessionClient): TerminalSession {
        val home = context.filesDir.absolutePath
        val environment = arrayOf(
            "HOME=$home",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "XDG_CONFIG_HOME=${File(context.filesDir, ".config").absolutePath}",
            "PATH=/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8"
        )
        return TerminalSession("/system/bin/sh", home, emptyArray(), environment, 2000, client)
    }
}

internal class TritonSessionClient : TerminalSessionClient {
    var terminalView: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.onScreenUpdated()
    }
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit
}

internal class TerminalCallbacks(
    private val session: TerminalSession,
    private val zoom: (Boolean) -> Unit
) : TerminalViewClient {
    override fun onScale(scale: Float): Float = 1f
    override fun onSingleTapUp(e: MotionEvent) = Unit
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = false
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (e.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_MINUS -> { zoom(false); return true }
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS -> { zoom(true); return true }
            }
        }
        return false
    }
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = false
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        session.writeCodePoint(ctrlDown, codePoint)
        return true
    }
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) = Unit
    override fun logWarn(tag: String, message: String) = Unit
    override fun logInfo(tag: String, message: String) = Unit
    override fun logDebug(tag: String, message: String) = Unit
    override fun logVerbose(tag: String, message: String) = Unit
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Unit
    override fun logStackTrace(tag: String, e: Exception) = Unit
}
