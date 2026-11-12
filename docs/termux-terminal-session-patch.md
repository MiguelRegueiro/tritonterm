# Termux terminal session patch

TritonTerm uses the upstream Termux `terminal-view` 0.118.0 renderer. Its
transitive `terminal-emulator` dependency is replaced by the local
`terminal-emulator` Android library module so the session completion behavior
can be controlled without filtering or editing terminal output after the fact.

The local Java source is from `termux/termux-app` commit
`6e2689f55295fa444be8ac8592c527c2c5ef3253` (v0.118.0). It preserves the
upstream constructor and its default behavior. A new constructor overload lets
an application disable only the `[Process completed - press Enter]` transcript
line. Exit status is still saved, and `onSessionFinished()` is always called.
TritonTerm passes `false` for both its Arch ARM and fallback shell sessions;
its Compose recovery panel remains responsible for starting a replacement.

The patch is localized to `TerminalSession.java`. It does not change PTY
creation, input/output queues, scrollback, wait status handling, or process
cleanup. The upstream Java sources and Apache-2.0 notice are kept in
`terminal-emulator/`. To refresh the vendored source, copy
`terminal-emulator/src/main/java` from the pinned upstream revision and
reapply the `mShowProcessCompletionMessage` constructor option and conditional
append in `TerminalSession.java`.

The JNI implementation is not forked. The module packages the unchanged
per-ABI `libtermux.so` files from the upstream 0.118.0 AAR
(SHA-256 `fd0d262041b42713a5685625711a01f87d86c559eaa9cc663f82bc73520d08b5`);
the APK verification script checks their individual hashes.

`TerminalSessionTest` verifies that the line is omitted for normal exit (0),
nonzero exit (23), and signal termination (-9), and that upstream-formatted
messages remain available when the option is enabled. Ctrl+D is an interactive
PTY behavior and should be checked on-device; in Bash it should follow the
same exit-0 path as `exit 0`.
