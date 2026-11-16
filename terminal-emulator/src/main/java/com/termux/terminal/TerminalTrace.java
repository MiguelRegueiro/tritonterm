package com.termux.terminal;

import android.os.Build;
import android.os.Trace;

/** Small, opt-in trace helpers for diagnosing terminal input and frame latency. */
public final class TerminalTrace {

    private TerminalTrace() {}

    /**
     * Start a trace section only while Android system tracing is active. Trace.isEnabled()
     * was added in Android 10; older supported releases keep the terminal path untouched.
     */
    public static boolean beginSection(String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !Trace.isEnabled()) return false;
        Trace.beginSection(name);
        return true;
    }

    /** Emit a point marker as a short section while system tracing is active. */
    public static void instant(String name) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && Trace.isEnabled()) {
            Trace.beginSection(name);
            Trace.endSection();
        }
    }

    public static void endSection(boolean started) {
        if (started) Trace.endSection();
    }
}
