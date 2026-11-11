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
6. Long-press the terminal, choose **More**, then **Select All**. The complete
   terminal scrollback should be copied to the Android clipboard and a brief
   confirmation should appear.
7. With a physical keyboard, select text by touch and press **Ctrl+Shift+C**;
   press **Ctrl+Shift+V** to paste. Then run `sleep 30` and press **Ctrl+C**;
   the command should be interrupted, proving ordinary Ctrl+C remains terminal
   input.

## Automated checks

`TerminalClipboardSupportTest` covers shortcut modifier gating, clipboard
content without altering Unicode or newlines, empty clipboard rejection, and
Select All text handling. The device checks above exercise the Android view,
clipboard service, PTY, and the terminal emulator's bracketed-paste behavior.
