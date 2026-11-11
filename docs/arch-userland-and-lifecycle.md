# Arch userland profile and Android process lifecycle

## Decision

TritonTerm uses Arch Linux ARM's signed generic AArch64 root filesystem because the official generic image is the maintained bootstrap artifact and its packages come from the upstream Arch Linux ARM repositories. The [official generic install page](https://archlinuxarm.org/platforms/armv8/generic) says it includes the base userspace, the mainline kernel, firmware and utilities, OpenSSH, and haveged; it also documents systemd networkd, resolved DNS, and timesyncd defaults. It is intended for developers who arrange their own boot functionality, not a PRoot or Android-specific minimal image. There is no supported official minimal rootfs for this generic target in the download path used here.

The generic image therefore arrives with hardware boot components. Package names and versions are inventoried from the image's own pacman local database at first install and written to diagnostics; the exact package set can vary with the mutable `latest` artifact. TritonTerm removes only the kernel package (`linux-aarch64`), Linux firmware bundles (`linux-firmware` and split `linux-firmware-*` packages, except the standalone `linux-firmware-tools` utility), and initramfs generator packages (`mkinitcpio` and `mkinitcpio-busybox`). It uses `pacman -Rns` with normal dependency checks; it does not edit pacman's database or delete package-owned files directly. Unrelated `linux-*` packages, systemd, glibc, Bash, pacman, certificates, networking, and keyrings are retained. If the package graph refuses the removal, setup fails and leaves the rootfs incomplete for diagnosis.

The profile runs only during explicit first install. It does not run `pacman -Syu` or refresh and upgrade the full system in the background. After an interactive shell is ready, package management remains standard Arch Linux ARM pacman with package signature verification enabled. The terminal uses the mirror list shipped by Arch Linux ARM; it does not add an unofficial mirror. Package 404s or a stale database are surfaced as pacman errors and the user can refresh/retry using normal pacman operations after checking repository status.

The rootfs download uses an HTTPS Arch Linux ARM mirror because the generic image's documented `os.archlinuxarm.org` link uses HTTP, which Android blocks by default. The detached signature and pinned build-system fingerprint remain mandatory; TritonTerm does not enable cleartext traffic.

`/root/.tritonterm-userland-profile-v1` is written only after pacman removal succeeds, the installed package inventory has no targeted boot packages, and `pacman -Dk` succeeds. Rootfs readiness requires that marker. An older rootfs without the validated marker is preserved and reported as incomplete; for this pre-release project, uninstalling/clearing app data and reinstalling is the supported clean reset. No migration runs an unreviewed removal on an existing user environment.

`pacman.conf` retains the existing Android/PRoot compatibility migration: `DownloadUser` is disabled and `DisableSandboxFilesystem` is enabled, while signature settings and repositories are left intact. Package signatures are not bypassed.

## Process ownership and interruption

The PTY controller is process-scoped (`TerminalSessionHost`), not Compose-scoped. A screen disposal detaches its `TerminalView`; it does not finish the session. A recreated screen can attach to the same `TerminalSession` and emulator while the app process remains alive. A foreground service with a persistent notification is started for active shells and first-run installation/preparation. It raises process importance during normal backgrounding, including opening another app or taking a screenshot, but Android can still kill the process or terminate it after a force-stop, reboot, or severe resource pressure. A PTY cannot be reconstructed after process death; relaunch creates a new shell and logs the interruption.

The active-shell marker is removed when the PTY reports a normal exit. If a later process finds it, diagnostics record the interruption and whether pacman's database lock exists. The UI warns whenever `var/lib/pacman/db.lck` exists. TritonTerm never deletes that lock automatically: it may still belong to a live pacman process. Users must first establish that no package operation is running, then follow pacman's documented recovery guidance. An interrupted first-run setup has no profile marker and is never treated as ready; the partial rootfs is preserved for diagnosis and clean reset.

## Fresh-install acceptance on an Android phone

Use an ARM64 Android device with several GB free and a clean app install. A desktop build is not evidence for these checks.

1. Install the APK and allow the terminal foreground notification. Run **Test launcher**, then explicitly choose **Install Arch**. Verify the official rootfs signature succeeds, setup shows package removal/database validation, and Bash becomes interactive. Do not expect a first-run system upgrade.
2. In Bash run `uname -m`, `pacman -Q bash glibc pacman ca-certificates archlinuxarm-keyring`, `pacman -Q linux-aarch64 linux-firmware mkinitcpio` (the latter query should report absent packages), and `pacman -Dk`.
3. Run `pacman -Syyu` and allow it to finish. Verify the transaction contains no kernel/firmware bundles or mkinitcpio image build hooks. This may be a sizeable explicit upgrade because the signed image and current repositories can be at different dates.
4. Run `pacman -S --needed git`, then `git --version`.
5. Start a long-running harmless command such as `sleep 120; echo session-survived`, switch to another app, take a screenshot, and return. Confirm the command completes in the same terminal. Rotate/recreate the activity if the device allows it and confirm the same PTY remains attached.
6. Relaunch TritonTerm after leaving it normally. Confirm the installed shell opens without another rootfs download or install. Check clipboard copy/paste.
7. For interruption diagnosis, while a harmless command is running, force-stop TritonTerm from Android Settings (this is a destructive lifecycle check, not ordinary backgrounding), relaunch, and confirm diagnostics record process loss. If a pacman lock remains, confirm the UI warns and does not remove it. Only clear app data for the final clean-install acceptance run after the recovery behavior has been reviewed.

Export **Logs** and retain the package inventory, `pacman -Dk`, package transaction output, and process lifecycle events with the test notes. These on-device checks remain pending until performed on a phone.
