<h1 align="left"><img src="assets/branding/triton-trident.png" height="48" alt="TritonTerm trident" align="absmiddle" />&nbsp; TritonTerm</h1>

A mobile terminal for Android, with a touch keyboard and a private Arch Linux ARM development environment.

[Download the latest APK](https://github.com/MiguelRegueiro/tritonterm/releases/latest/download/tritonterm.apk) · [Changelog](CHANGELOG.md) · [Documentation](docs/installation.md)

## Features

- **A terminal built for phones** — scrollback, text selection, clipboard, zoom, bundled fonts, and a configurable display.
- **A practical touch keyboard** — terminal keys and modifiers, plus support for Android's keyboard and shortcut row.
- **A private Arch Linux ARM environment** — install a signed image on ARM64 devices, then use Fish, Rust, Paru, `bat`, and Elio.
- **Useful Android integration** — session notifications, shell recovery, searchable diagnostics, and setup checks.
- **Configurable keys** — edit `tritonterm.conf` with syntax highlighting and Kitty-style key maps.

## Install

1. Download and install the [latest TritonTerm APK](https://github.com/MiguelRegueiro/tritonterm/releases/latest/download/tritonterm.apk).
2. Open TritonTerm and choose **Install Arch**.
3. Keep the app open while it downloads and verifies the Linux image. The first install needs several gigabytes of free storage.

The Arch environment is stored in app-private data. App updates preserve it; uninstalling TritonTerm removes it. See [installation and storage details](docs/installation.md).

## Documentation

- [Installation and first launch](docs/installation.md)
- [Preinstalled developer environment](docs/arch-developer-environment.md)
- [Arch userland and lifecycle](docs/arch-userland-and-lifecycle.md)
- [Native PRoot runtime](docs/native-runtime.md)
- [Terminal input and prompt latency investigation](docs/terminal-input-performance-investigation.md)
- [Runtime diagnostics](docs/device-runtime-diagnostics.md)
- [Changelog](CHANGELOG.md)

## Build

Build and test the debug APK with Android SDK platform 36, Java 17, and Fish installed:

```sh
./scripts/build-apk.sh
```

The APK is written to `dist/tritonterm-debug.apk`. Release builds require the protected Android signing key; see [build and artifact authentication](docs/arch-developer-environment.md#build-and-artifact-authentication).

## License

TritonTerm's Android app is licensed under GPL-3.0-only. Third-party components and the Arch Linux ARM userland retain their own licenses; license texts shipped with the app and image are listed in the linked documentation.
