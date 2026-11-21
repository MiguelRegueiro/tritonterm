# Terminal clipboard acceptance

The terminal view's built-in Android selection mode provides the touch selection
handles and native floating toolbar. Copy and Paste use Android's
`ClipboardManager`; pasted content is sent through Termux's terminal emulator
`paste()` API so bracketed-paste mode is honored by the active guest.

## Device checks

1. In the Arch shell, run `printf 'clipboard-one\nclipboard-two\n雪 🌊\n'`.
2. Long-press the output. Drag either selection handle, tap **Copy**, then paste
   into a text editor or another app. The selected Unicode text should match.
3. Copy `first line\nsecond line\n雪` from another app. Long-press an empty
   terminal area and tap **Paste**. Confirm all three lines arrive intact.
4. Run `printf '\e[?2004h'; cat`. Paste multiple lines and verify the guest
   receives the content in one bracketed-paste operation. Press **Ctrl+D** to
   leave `cat`, then disable bracketed paste with `printf '\e[?2004l'`.
5. While text is selected, hold the custom keyboard's **Ctrl** and **Shift**
   keys and tap **C**. Paste outside TritonTerm and verify the selection was
   copied. Hold both modifiers and tap **V** to paste into the shell.
6. Long-press the terminal and tap **Copy all** directly in the floating toolbar.
   The current screen and retained scrollback should be copied, with confirmation.
   Print a distinctive new line, copy again, and paste into another app: the new
   line must be present. Clear the screen and history with
   `printf '\e[3J\e[2J\e[Hfresh-content\n'`, copy again, and verify old output
   is absent. After starting a new shell, copy its output and verify that the
   previous shell's transcript is not substituted. A very quick Copy all tap
   should also dismiss the selection handles.
7. With a physical keyboard, select text by touch and press **Ctrl+Shift+C**;
   press **Ctrl+Shift+V** to paste. Then run `sleep 30` and press **Ctrl+C**;
   the command should be interrupted, proving ordinary Ctrl+C remains terminal
   input.

## Automated checks

`TerminalClipboardSupportTest` covers shortcut modifier gating, clipboard
content without altering Unicode or newlines, and empty clipboard rejection.
`TerminalCopyAllTest` exercises the real emulator's repeated copy callbacks,
new output, scrollback, cleared contents, alternate-screen transitions, Unicode,
soft wrapping and empty contents. `TerminalCopyAllSessionTest` is an Android
instrumentation check for a reused view switching sessions; it can be compiled
on the host but needs Android to execute. The device checks above exercise the Android view,
clipboard service, PTY, and the terminal emulator's bracketed-paste behavior.

## exp.46 copy-all repair

The old context-menu listener was installed in AndroidView's one-time factory
and captured that creation's session. A reused view could later display a new
session while Copy all still queried the earlier one. The new action resolves
`TerminalView.getCurrentSession()` at click time, snapshots that emulator's
current screen/transcript and forwards it through the normal session clipboard
callback. It stores no transcript or selection snapshot between invocations.

Copy all is the first floating-toolbar action, marked always visible; Copy
selection and Paste remain separate actions. The old More → Select All route
is replaced, and the fallback context menu uses the same Copy all entry point.
Explicit Copy all dismisses selection even during the initial 300 ms guard
that remains in place for incidental selection-hide gestures.

Host tests report 90 passed and one skipped across the app, emulator and view.
The real-widget session-switch instrumentation regression compiles, but has
not run without a connected Android device. After installing exp.46, the
user confirmed that the reported Copy all bug was fixed on the phone.
Native engines and the Arch image are unchanged from exp.45.

The local signed update is `dist/tritonterm-2026.11.15.1-exp.46.apk`
(version code 202612046), using the existing local development certificate.
Signature and package identity were verified; every native library and source
asset matches exp.45. APK SHA-256:
`02d4c610e45611820d7fdfea20c0fa5e569e5485781d277030a12d6052b2c336`.
