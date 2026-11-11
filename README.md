# TritonTerm

An Android terminal with a Kitty-inspired renderer, a touch keyboard, and a private Arch Linux ARM environment on supported devices.

## Current local environment

- On ARM64 devices (Android 9+), TritonTerm can download the official Arch Linux ARM generic AArch64 root filesystem after the user explicitly starts setup, then run it using the bundled Android-compatible PRoot runtime.
- The compressed rootfs is downloaded at runtime; it is not part of the APK. The rootfs, installed packages, and `/root` live in TritonTerm's app-private storage. Android removes them when TritonTerm is uninstalled; APK updates preserve them. The verified archive is retained in this diagnostic build.
- Use `pacman -Syu` to update Arch and `pacman -S fish starship git openssh` to install tools. TritonTerm does not install a separate package repository or depend on Termux.
- First setup is explicit: test the harmless native installer launcher, then tap **Install Arch** once. Installation runs as a single noninteractive Android process; its stdout, stderr, exit status, and phase timings are available from **Logs**. Opening the app or Logs does not retry setup. On failure this diagnostic build preserves the verified archive and partial rootfs for investigation. After a successful extraction, it validates the rootfs, initializes pacman keys, and opens the interactive shell.
- The initial rootfs download is about 800 MiB and needs additional temporary space while it extracts; allow several gigabytes of free storage for first setup.
- The runtime uses PRoot, so it is not a VM, cannot provide a separate Linux kernel, and adds syscall/filesystem overhead. It executes ARM64 instructions directly.
- The Logs dialog provides setup diagnostics and the three isolated Android runtime probes. See [`docs/device-runtime-diagnostics.md`](docs/device-runtime-diagnostics.md) for the phone procedure.
- On unsupported CPU architectures the app currently falls back to Android's `/system/bin/sh`.

The Arch rootfs is fetched over HTTPS and its detached OpenPGP signature is checked against the pinned Arch Linux ARM Build System fingerprint before extraction. TritonTerm then gives `pr-cli` the computed SHA-256 so the installer verifies the archive again. Packages installed or upgraded by `pacman` are verified using Arch Linux ARM's package-signing keys, initialized on first launch.

The bundled `pr-cli`, PRoot, and loader are built from the pinned source submodule. Their source revisions, build toolchain, reproduction script, license texts, and SHA-256 hashes are recorded in [`docs/native-runtime.md`](docs/native-runtime.md).

## Android app

The app is Kotlin/Compose, using Termux's Apache-2.0 terminal-view/emulator libraries for VT parsing, rendering, and PTY handling. The terminal backend is behind a small interface, so the app can evolve independently from the Linux userspace.

The touch keyboard includes Esc, Tab, Ctrl, Alt, Shift, arrows, Backspace, Enter, F1–F12, and a symbol layer. Super is omitted. Ctrl/Alt/Shift are usable as held modifiers. The Config button currently edits the supported `font_family` and `font_size` settings.

## Build

With Android SDK platform 36 and Java 17 installed:

```sh
./scripts/build-apk.sh
```

The script creates `dist/tritonterm-debug.apk`.

## Next terminal milestones

- Improve setup UX with progress, cancellation, and recovery for interrupted downloads.
- Add Fish/Starship defaults and a first-run package setup option.
- Add persistent sessions, clipboard actions, SSH profiles, and keyboard layouts.
- Implement and test Kitty keyboard and graphics protocol support before advertising compatibility.

## References

- [Android-compatible PRoot runtime](https://github.com/oonid/pr)
- [Arch Linux ARM generic AArch64 installation](https://archlinuxarm.org/platforms/armv8/generic)
- [Termux terminal libraries](https://github.com/termux/termux-app/wiki/Termux-Libraries)
- [Kitty graphics protocol](https://sw.kovidgoyal.net/kitty/graphics-protocol/)
- Local inspiration: [Triton Connect Android client](/home/regueiro/triton-connect/android-client/app/src/main/java/com/tritonconnect/android/MainActivity.kt) and [regueiro-hyprland Kitty/Fish setup](/home/regueiro/regueiro-hyprland/README.md).
