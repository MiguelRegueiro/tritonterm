# TritonTerm

An Android-first terminal for people who want Kitty’s feel, Fish-friendly defaults, and a real keyboard on a touchscreen. It should work as a useful local shell and as a first-class SSH client for Triton Desktop, TritonBSD, Linux, and FreeBSD.

## Recommendation

Build the app **mostly in Kotlin**, with **Jetpack Compose** for UI. This is not a frontend/backend split like a website. The base uses Termux’s Kotlin `terminal-view` and `terminal-emulator` libraries, including their small native C/JNI PTY bridge for launching and resizing the local shell. C/C++ or Rust is not needed for a Kitty-like experience by itself; the protocols, renderer, keyboard handling, and compatibility work deliver that experience. A separate native terminal core is only worth considering if sharing code with TritonBSD/Linux becomes a real goal. The local shell and its packages are separate native executables, like in Termux.

Keep terminal rendering and input in a dedicated terminal-view module. TritonTerm currently imports Termux’s Apache-2.0 `terminal-emulator` and `terminal-view` libraries rather than writing a VT parser from scratch.

Use a **Termux-style, unrooted local environment**, not a pretend Android installation of Arch or FreeBSD. Android apps are sandboxed, and local native programs must target Android’s ABI/runtime. The everyday UX should be exactly the familiar pattern: `pkg install fish`, `pkg install git`, and so on. `pkg` is the friendly command backed by APT and TritonTerm’s Android-compatible package repository. Termux’s package system is the proven model, but its packages are built for its own prefix/app path; do not assume its repository can be pointed at a differently named app unchanged. Fish, Git, SSH, and common CLI tools belong in this environment. Offer **proot-distro** as an optional later feature for Debian/Arch userlands; `pacman`/`paru`, `dnf`, and FreeBSD `pkg` are for that distro or a remote host, not the native Android environment.

SSH should be a first-class session type: use the remote machine’s own shell and package manager, with host-key verification, key-based auth, agent/key import support, and saved profiles. Triton Connect can inspire the controls and visual language, but TritonTerm is an independent terminal app rather than a remote-control mode.

## Product shape

- **Terminal feel:** dark, low-glare Kitty-inspired theme; monospace font controls; bundled JetBrainsMono Nerd Font option; ANSI 16/256 colors and true color; scrollback, selection/copy/paste, links, cursor styles, tabs/sessions, and configurable key bindings.
- **Touch keyboard:** adapt Triton Connect’s layout: Esc, Tab, Ctrl, Alt, Shift/Caps, arrows, Backspace, Enter, F1–F12, and a compact symbol layer. Omit Super for the initial Android layout. Make modifiers sticky/lockable. Let users reorder/hide rows and save keyboard layouts by language or profile; preserve hardware keyboard input.
- **Kitty compatibility:** implement common xterm/VT behavior first, then Kitty keyboard protocol and Kitty graphics protocol as explicitly versioned features. Kitty graphics uses escape sequences plus image placements; Unicode placeholders are a mechanism for apps that cannot directly manage placement, not a substitute terminal renderer. Start with still images and placeholders after core terminal correctness; defer animation and advanced placement. Probe/report supported features accurately so apps can fall back.
- **Personal defaults:** Fish as the suggested shell (not a hard dependency), Kitty-like Cherry Midnight palette based on the existing config, and user-selectable fonts/theme. Do not copy Kitty configuration wholesale; support a small documented TritonTerm config and import common colors/font settings later.
- **Responsive use:** phone-first, with landscape, tablets, and external keyboards supported. Keep the terminal full-screen; show a subtle session bar and let the extra-key strip collapse when a hardware keyboard is active.

## First milestones

1. **Base app (started):** one local shell screen using Termux’s Apache-2.0 terminal-view/emulator libraries and the Triton Connect-inspired extra-key keyboard (Esc, Tab, Ctrl, Alt, Shift, arrows, F keys; no Super key).
2. **Local MVP:** bootstrap, `pkg`/APT repository, Fish, font/theme settings, configurable keyboard layouts, session persistence, clipboard, and accessible touch targets.
3. **SSH MVP:** encrypted profile/key storage, host-key prompts, reconnect, resize, and per-host keyboard/theme preferences.
4. **Compatibility:** terminal feature test suite; Kitty keyboard protocol; Kitty graphics still images and Unicode placeholders; then performance, power, and release packaging.

## Build

With Android SDK platform 36 installed, run:

```sh
./scripts/build-apk.sh
```

The script downloads Gradle locally if needed and copies the debug APK to `dist/tritonterm-debug.apk`. The current base starts Android's `/system/bin/sh`; the TritonTerm `pkg` environment and Fish bootstrap are later work.

## Key constraints

- Android local shell packages are Android-targeted binaries, not packages from Arch, Fedora, Debian, or FreeBSD. Termux documents that its programs use Android’s Bionic environment and a private prefix; package reuse therefore needs deliberate prefix and app-identity work.
- `pacman`, `paru`, `dnf`, and FreeBSD `pkg` are available only in a compatible remote host or a Linux userland under proot. Proot is not a VM and does not turn Android into a normal Linux/FreeBSD kernel environment.
- Kitty itself currently targets Linux/macOS (and documents various BSDs), not Android. TritonTerm should implement compatible terminal protocols rather than trying to package the Kitty desktop executable.
- Kitty graphics support is a substantial renderer feature. Do not advertise it until image placement, scrolling, resize, deletion, limits, and Unicode placeholders behave correctly.

## References

- [Termux app and terminal libraries](https://github.com/termux/termux-app/wiki/Termux-Libraries) · [Termux package management](https://github.com/termux/termux-packages/wiki/Package-Management) · [Termux execution environment and prefix](https://github.com/termux/termux-packages/wiki/Termux-execution-environment)
- [Termux PRoot-Distro](https://github.com/termux/proot-distro) · [Android NDK ABIs](https://developer.android.com/ndk/guides/abis)
- [Kitty graphics protocol](https://sw.kovidgoyal.net/kitty/graphics-protocol/) · [Kitty platform/install support](https://sw.kovidgoyal.net/kitty/binary/)
- Local inspiration: [Triton Connect Android client](/home/regueiro/triton-connect/android-client/app/src/main/java/com/tritonconnect/android/MainActivity.kt) and [regueiro-hyprland Kitty/Fish setup](/home/regueiro/regueiro-hyprland/README.md).
