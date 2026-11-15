# Install TritonTerm

## Requirements

- Android 9 or newer.
- ARM64 for the full Arch Linux ARM environment.
- Several gigabytes of free storage for the first setup.

The app can run on other Android ABIs, but those builds use Android's `/system/bin/sh` instead of the Arch environment.

## First launch

1. Download the [latest TritonTerm APK](https://github.com/MiguelRegueiro/tritonterm/releases/latest/download/tritonterm.apk) and install it. Android may ask you to allow installs from your browser or file manager.
2. Open TritonTerm and tap **Install Arch**.
3. Leave the app open while it downloads, verifies, and extracts the image. Setup reports its current phase and elapsed time.
4. When setup completes, TritonTerm opens a Fish shell as the unprivileged `triton` user.

The download is a separate signed Arch Linux ARM image, not part of the APK. The installer verifies its signed manifest and archive checksum before extraction. It initializes a device-local pacman keyring and checks the shell and included tools. Package upgrades remain under your control; setup does not run a system upgrade. See the [developer image guide](arch-developer-environment.md) for the image contents and build details.

## App data and updates

The root filesystem, home directory, and installed packages live in TritonTerm's private app data. Updating the APK preserves them. Uninstalling the app removes them. The verified image archive is deleted after successful setup; if setup fails or is interrupted, it is retained so setup can resume or be retried.

## Runtime notes

The Arch environment runs through Android-compatible PRoot. It shares the Android kernel, so it is not a virtual machine and cannot provide a separate Linux kernel. PRoot also adds some filesystem and syscall overhead. Read more in [Arch userland and lifecycle](arch-userland-and-lifecycle.md) and [native runtime](native-runtime.md).

## Diagnostics

Open **Logs** in TritonTerm to inspect setup events, run the available runtime checks, or share the log file. See the [device diagnostics guide](device-runtime-diagnostics.md) for detailed troubleshooting steps.
