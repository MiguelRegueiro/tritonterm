# Changelog

All notable changes to TritonTerm are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-11-15

### Added

- Added a full-screen Android terminal with a scrollback buffer, text selection, clipboard copy and paste, pinch-to-zoom, and configurable font family and size.
- Added a touch keyboard with terminal keys, held Ctrl/Alt/Shift modifiers, a symbol layer, and support for Android's system keyboard and its terminal shortcut row.
- Added a `tritonterm.conf` editor with syntax highlighting and Kitty-style key maps, including conditional bindings for Elio and terminal editors.
- Added a private Arch Linux ARM environment launched through Android-compatible PRoot, with signed image verification, resumable setup, and a preconfigured unprivileged `triton` account.
- Added a developer environment with Fish, Rustup and Cargo, Paru, TCP fakeroot, and Elio with its supplied theme; Starship is configured with TritonTerm's warm prompt colors.
- Added PRoot support for guest file ownership and permissions, Android-compatible pacman settings, device-local pacman signing keys, and the phone's locale, timezone, and Tailscale DNS.
- Added a foreground notification for active terminal and setup sessions, session recovery across activity recreation, and a shell exit screen with a start-new-shell action.
- Added a diagnostics screen with searchable and shareable logs, per-entry copy, setup and runtime checks, crash capture, executable and package-manager probes, and pacman signing-key repair.
- Added bundled terminal fonts and glyph routing for Nerd Font icons and box-drawing characters, plus a Triton trident launcher and notification icon.
- Added signed, version-pinned rootfs manifests and build workflows for reviewing and publishing Arch Linux ARM images and producing matching APKs.
