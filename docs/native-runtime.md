# Android native runtime provenance

## PRoot runtime

The bundled executables are built from the pinned [`oonid/pr` source](../third_party/oonid-pr), not downloaded as release assets or copied from a temporary directory.

- Upstream commit: `7029b1fe8fa84d64339cabe38f9c5be2ea2ae7ac`
- Source URL: <https://github.com/oonid/pr/tree/7029b1fe8fa84d64339cabe38f9c5be2ea2ae7ac>
- CLI source: `third_party/oonid-pr/src/pr-cli/` (GPL-3.0-or-later)
- PRoot source: `third_party/oonid-pr/src/proot/` (GPL-2.0-or-later; upstream identifies the combined `libproot.so` build as GPL-3.0-or-later due to its talloc component)
- Loader source: `third_party/oonid-pr/src/proot/src/loader/` (GPL-2.0-or-later)
- Pinned build dependency: `vendor/samba` at `2f8dfde1210395175e726455bdb63a7b97245a72`, supplying talloc
- Build recipe: [`scripts/build-proot-runtime.sh`](../scripts/build-proot-runtime.sh) runs upstream `scripts/build.sh` for PRoot and builds the CLI with `cargo build --release --target aarch64-linux-android --locked` using upstream's path remapping.
- Build toolchain: NDK r27c (upstream verifies its archive SHA-256), Rust 1.98.0, and the committed Cargo lockfiles
- GPL-2.0-or-later, GPL-3.0-or-later, and LGPL-3.0-or-later license texts are included in `app/src/main/assets/licenses/runtime/`.

The exact corresponding GPL source is available from this repository: the `third_party/oonid-pr` gitlink pins upstream source, while the tracked patches add extraction progress, structured installer diagnostics, and opt-in preservation of failed install artifacts. The preservation flag is set only by TritonTerm's diagnostic installer process; upstream cleanup remains unchanged for other invocations. Its talloc dependency is pinned at `vendor/samba` commit `2f8dfde1210395175e726455bdb63a7b97245a72`. The source locations, upstream license files, local patches, and build recipe are all present in the checkout; the license texts are also bundled in the APK. Run `git submodule update --init third_party/oonid-pr third_party/archlinuxarm-keyring`; the build wrapper initializes the required talloc submodule, applies the tracked patches, builds the GPL binaries, copies them into TritonTerm's `jniLibs`, and verifies their hashes against [`native-runtime.sha256`](native-runtime.sha256).

For device diagnosis, the pinned `pr-cli` source also writes structured install, cache, cleanup, extraction, validation, and PRoot launch events to the path in `TRITONTERM_DIAGNOSTICS_FILE`. TritonTerm preserves the verified archive and refuses to start a new install when an incomplete rootfs already exists. The packaged CLI hash in both manifests reflects this instrumentation build.

TritonTerm's `pr-cli` patch reports checksum progress for distributions that use SHA-256, interrupted-install cleanup counts, and compressed archive bytes read plus filesystem entries extracted through `APP_PREFIX/var/lib/pr/tritonterm-install-progress`. Android polls the file directly, so progress does not depend on terminal rendering or scrollback. The Arch rootfs uses its detached signature as the integrity check and skips redundant checksum passes. The installer also fails if setting an entry's permissions fails, replacing an extra full-tree scan after extraction; permission errors are reported at the file that caused them.

## All native libraries packaged in the APK

There are 11 native libraries in the universal debug APK: three TritonTerm PRoot executables and two upstream Android libraries for each ABI. The app resolves Android libraries from pinned Gradle coordinates; it does not manually copy their binaries. [`native-binaries.sha256`](native-binaries.sha256) records each library as extracted from the APK, and [`scripts/verify-packaged-native-libs.sh`](../scripts/verify-packaged-native-libs.sh) checks both the set and each hash after every APK build.

| Library | Upstream source / revision | Build or artifact | License |
| --- | --- | --- | --- |
| `libpr-cli.so` | `oonid/pr`, commit `7029b1fe8fa84d64339cabe38f9c5be2ea2ae7ac`, `src/pr-cli/` | `scripts/build-proot-runtime.sh`; Cargo locked build for `aarch64-linux-android` | GPL-3.0-or-later |
| `libproot.so` | `oonid/pr`, same commit, `src/proot/` plus talloc from `vendor/samba` commit `2f8dfde1210395175e726455bdb63a7b97245a72` | `scripts/build-proot-runtime.sh` → upstream `scripts/build.sh --arch=arm64`; NDK r27c | GPL-3.0-or-later (combined build; talloc source is LGPL-3.0-or-later) |
| `libproot-loader.so` | `oonid/pr`, same commit, `src/proot/src/loader/` | Same NDK build recipe as `libproot.so` | GPL-2.0-or-later |
| `libtermux.so` | [`termux/termux-app`](https://github.com/termux/termux-app/tree/6e2689f55295fa444be8ac8592c527c2c5ef3253), commit `6e2689f55295fa444be8ac8592c527c2c5ef3253`, `terminal-emulator/src/main/jni/` | Gradle artifact `com.termux.termux-app:terminal-emulator:0.118.0`; upstream module task `:terminal-emulator:assembleRelease`; AAR SHA-256 `fd0d262041b42713a5685625711a01f87d86c559eaa9cc663f82bc73520d08b5` | Apache-2.0 |
| `libandroidx.graphics.path.so` | AndroidX [`frameworks/support`](https://android.googlesource.com/platform/frameworks/support/), `graphics/graphics-path/`, release snapshot commit `8a05a22af450d589ef911d772a001a49dcb05b71` | Google Maven artifact `androidx.graphics:graphics-path:1.0.1`; source task `:graphics:graphics-path:assembleRelease`; AAR SHA-256 `8ca4032b6d79b351f0b59ad4b580eddbb9423e1652f7c958830687f1eee2ec03` | Apache-2.0 |

## Arch Linux ARM rootfs trust

- Archive: `http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz` (the official generic AArch64 install page's download host)
- Detached signature: the same URL with `.sig` appended
- Trusted key fingerprint: `68B3537F39A313B3E574D06777193F152BDBE6A6` (Arch Linux ARM Build System)
- Signing-certificate source: `third_party/archlinuxarm-keyring/packager/builder.asc`, from [`archlinuxarm-keyring`](../third_party/archlinuxarm-keyring) commit `91e6b11698f8df66042d56aaa56fbe9c9263847d`; the build script copies this official certificate into `app/src/main/assets/archlinuxarm-builder.asc`.
- The app checks the full fingerprint and verifies the detached signature while streaming the archive. That signature authenticates the exact rootfs bytes, so TritonTerm does not hash the 790 MiB archive in separate full-file passes before extraction. The app bundles only the signer certificate it trusts; this avoids passing the entire multi-key/cross-certified keyring to the app's OpenPGP parser. Other `pr-cli` distro installs continue to use their configured SHA-256 checks.

Arch Linux ARM documents this signing key for rootfs releases at <https://archlinuxarm.org/about/downloads> and the fingerprint at <https://archlinuxarm.org/about/package-signing>.

End-to-end verification performed on 2026-11-11 against the current mirror artifact: rootfs SHA-256 `42a4eeaa038994ffd31fa173256ef2f0ef511358eeb41b9ea1f8626391b9b319`; detached-signature SHA-256 `0157d8cd6261c85205931c766b754d6d56112b28800666fb64add1de192ebe11`. Both GnuPG and TritonTerm's Bouncy Castle verifier accepted the signature under the pinned fingerprint. The archive is a mutable `latest` artifact, so these hashes identify the test input, not a permanent release version.

## Android execution constraints

The runtime ELF files must exist in Android's extracted `nativeLibraryDir` because TritonTerm creates executable symlinks there to work around app-data `execve` restrictions. `packaging.jniLibs.useLegacyPackaging = true` makes AGP extract them, and the built merged manifest contains `extractNativeLibs="true"`. The app's minSdk changed from 26 to 28 to align with upstream PRoot's NDK `API_LEVEL=28` build. That's a conservative compatibility declaration; an API-28-targeted ELF alone does not prove API 26–27 are impossible, so supporting those versions would need separate symbol and device validation. This is independent of targetSdk 35.

The debug APK does not contain the Arch Linux ARM rootfs. It contains the app, renderer/font assets, the Bouncy Castle OpenPGP verifier, and the 11 native libraries listed above. The rootfs downloads to private app storage on first launch. With minSdk 28, AGP stores DEX files uncompressed by default; that explains most of the apparent APK increase in the first build. `packaging.dex.useLegacyPackaging = true` restores DEX compression for APK builds. The current debug APK is 18.5 MB (17.6 MiB), compared with 12.6 MB (12.0 MiB) for the pre-runtime APK. Remaining growth includes Bouncy Castle's verifier code and resources and about 2.4 MiB of compressed PRoot executables and supporting native libraries. Debug builds retain unused cryptography algorithms and resources.

The terminal renderer and input UI depend only on `TerminalBackend`. `ArchLinuxArmBootstrap.kt` handles download and signature verification; `ArchLinuxArmInstaller.kt` owns the explicit noninteractive `ProcessBuilder` install and keyring commands; `TerminalSessionController.kt` launches only the interactive Arch shell after setup succeeds.
