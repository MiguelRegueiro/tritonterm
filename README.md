# TritonTerm

An Android terminal with a Kitty-inspired renderer, a touch keyboard, and a private Arch Linux ARM environment on supported devices.

## Current local environment

- On ARM64 devices (Android 9+), TritonTerm can download the official Arch Linux ARM generic AArch64 root filesystem after the user explicitly starts setup, then run it using the bundled Android-compatible PRoot runtime.
- The compressed rootfs is downloaded at runtime; it is not part of the APK. The rootfs, installed packages, and `/root` live in TritonTerm's app-private storage. Android removes them when TritonTerm is uninstalled; APK updates preserve them. The verified archive is retained in this diagnostic build.
- The generic signed rootfs is reduced during explicit first-run setup using ordinary dependency-aware `pacman -Rns` operations. The kernel, Linux firmware bundles, and mkinitcpio are removed; core userland, networking, certificates, keyrings, Bash, glibc, and pacman remain. Setup validates `pacman -Dk` before marking the environment ready. It does not silently run a system upgrade; once the shell is ready, users choose when to run `pacman -Syu` and install tools such as Fish, Starship, Git, and OpenSSH from official repositories.
- First setup is explicit: test the harmless native installer launcher, then tap **Install Arch** once. Installation and userland preparation run outside the PTY in a notification-backed foreground service; stdout, stderr, package inventory, exit status, and phase timings are available from **Logs**. Opening the app or Logs does not retry setup. On failure this diagnostic build preserves the verified archive and partial rootfs for investigation. After extraction it initializes pacman keys, uses pacman to remove hardware boot packages, checks the database, and only then marks the rootfs ready.
- The initial rootfs download is about 800 MiB and needs additional temporary space while it extracts; allow several gigabytes of free storage for first setup.
- The runtime uses PRoot, so it is not a VM, cannot provide a separate Linux kernel, and adds syscall/filesystem overhead. It executes ARM64 instructions directly.
- The Logs dialog provides setup diagnostics and the three isolated Android runtime probes. See [`docs/device-runtime-diagnostics.md`](docs/device-runtime-diagnostics.md) for the phone procedure.
- The foreground notification represents an active shell or setup task. Recreating the activity detaches and reattaches the terminal view without ending its PTY. Android can still terminate the app process under exceptional memory pressure or force-stop; the next launch records an interrupted-session diagnostic. A visible pacman database lock is never removed automatically because it may belong to a still-running package operation.
- On unsupported CPU architectures the app currently falls back to Android's `/system/bin/sh`.

The Arch rootfs is fetched from the official host listed by Arch Linux ARM's generic installation instructions. Its detached OpenPGP signature is checked against the pinned Arch Linux ARM Build System fingerprint before extraction, so archive authenticity does not rely on transport encryption. TritonTerm then gives `pr-cli` the computed SHA-256 so the installer verifies the archive again. Packages installed or upgraded by `pacman` are verified using Arch Linux ARM's package-signing keys, initialized on first launch.

On first setup and when opening an existing Arch installation, TritonTerm makes a hash-named backup of `/etc/pacman.conf`, comments out `DownloadUser`, and enables `DisableSandboxFilesystem` for Android/PRoot compatibility. It preserves repository, signature, and other settings and leaves `DisableSandboxSyscalls` unchanged.

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

## Validation

See [`docs/arch-userland-and-lifecycle.md`](docs/arch-userland-and-lifecycle.md) for the package-profile rationale, interrupted transaction handling, and on-device acceptance steps. Desktop builds and unit tests do not establish Android background behavior.

## References

- [Android-compatible PRoot runtime](https://github.com/oonid/pr)
- [Arch Linux ARM generic AArch64 installation](https://archlinuxarm.org/platforms/armv8/generic)
- [Termux terminal libraries](https://github.com/termux/termux-app/wiki/Termux-Libraries)
- [Kitty graphics protocol](https://sw.kovidgoyal.net/kitty/graphics-protocol/)
- Local inspiration: [Triton Connect Android client](/home/regueiro/triton-connect/android-client/app/src/main/java/com/tritonconnect/android/MainActivity.kt) and [regueiro-hyprland Kitty/Fish setup](/home/regueiro/regueiro-hyprland/README.md).
