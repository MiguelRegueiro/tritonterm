# Prebuilt Arch Linux ARM development environment

TritonTerm's first release is designed around one complete AArch64 rootfs image. It carries a consistent pacman local database and the tested development tools, so Android does not compile Rust, Paru, or fakeroot during installation. The image stays a separate download from the APK.

## Image account and first setup

The image contains one uncredentialed account named `triton`, with UID and primary GID 1000, home `/home/triton`, and Fish as its login shell. The account is fixed in the image; setup does not ask for a name or perform a first-boot rename. The app opens a shell once the signed image, account, home, and Fish executable are present. Developer-tool checks are recorded individually and never make an otherwise usable terminal inaccessible.

The image has no shared password or generated SSH identity. The root and `triton` account password fields are locked and the builder rejects any remaining active password hash. The image also excludes the build host's generated pacman private key: first install initializes and populates a device-local pacman keyring before use. Sudo policy grants `triton` only the pacman command needed for `makepkg -si` and Paru install flows; it does not use `NOPASSWD: ALL`.

Android app-private files cannot carry Arch's numeric owners and setuid bits on host inodes. The extractor stores those attributes in a parallel `${rootfs}.tritonterm-metadata` host tree, outside the guest rootfs. USERLAND PRoot maps guest paths to these records for stat, permission, chown, and setuid behavior. Pacman never sees metadata filenames while enumerating `/var/lib/pacman/local`, and the metadata tree is not exposed as a guest path.

The failed `.2` phone attempt used an older image whose passwd database still defined `dev`; inspection of that archive found the three executables, but not the fixed `triton` account or current Fish configuration. The new image sets PATH, `SYSTEMD_OFFLINE`, greeting suppression, Starship, and the `up` abbreviation from `/etc/fish/conf.d/20-tritonterm.fish`. CI runs the packaged Fish smoke through the same `pr-cli login --user triton ... fish -l -i -c` invocation used by the installer, and reports absolute executable presence separately from PATH resolution. The exact Fish smoke asset is syntax-checked before APK builds; optional runtime check failures are logged individually and do not block shell startup.

## Why TCP fakeroot is in the image

The packaged Arch `/usr/bin/fakeroot` uses System V IPC, which returned `Function not implemented` on the tested Android PRoot device. Upstream fakeroot supports TCP IPC; upstream 2.1.4 configured with `--with-ipc=tcp` passed direct, makepkg, and Paru device smoke tests.

The matching `fakeroot` wrapper, `faked` daemon, and `libfakeroot` shared library are installed together under `/opt/tritonterm/fakeroot-tcp/2.1.4/`. The global Fish PATH selects that wrapper for ordinary `makepkg` and Paru. Pacman's `/usr/bin/fakeroot` remains owned by its normal package and is not overwritten. The source archive is verified against SHA-256 `0822bd5a9f0cf19d2ba0546b88b0432d4d3d9917db62c57b74044ccadba06e49`; the source is under GPL-3.0-or-later and is retained in the image for corresponding-source obligations.

Paru 2.2.0 is built from its upstream source archive with SHA-256 `213d55a67605ff69faefa523917fee7168ea5bf143be8cef800fb48c9759f348`, then installed as a normal pacman-owned `/usr/bin/paru` package. Its standard makepkg path inherits the user's fakeroot shim. The image does not automatically build arbitrary AUR packages; users remain in control of reviewing and running AUR recipes.

Rustup is Arch Linux ARM's `rustup` package, not the standalone `rust` or `cargo` packages. The stable AArch64 GNU toolchain is initialized for `triton` before the image is made. The image build verifies `rustup show`, rustc/cargo versions, a real `cargo build`, and execution of the result in a Fish login shell.

The shipped toolchain uses Rustup's minimal profile. Rust HTML documentation is optional for compiling and running Cargo projects, so the image recipe removes the `rust-docs` component while retaining rustc, Cargo, the standard library, Cargo's preloaded registry cache, and the stable default toolchain. The image report records the expanded documentation bytes removed. Users can add Rustup components later if they need them.

Each Android-launched guest process receives `LANG=C.UTF-8` and the phone's current IANA timezone ID as `TZ`. TritonTerm refreshes the Android timezone lookup for each new Arch session, so a phone timezone change applies on the next shell without rebuilding the image. The guest's tzdata supplies seasonal daylight-saving rules. CI checks the locale used by bsdtar, January/July Europe/Madrid offsets, and the configured Starship time module. The same Fish login smoke is also run by the Android installer.

The signed rootfs download is retained until extraction, keyring initialization, and required feature checks succeed. Setup then removes the roughly 868 MiB `.12` archive from app-private storage; an interrupted or failed setup retains it so retry does not need to download it again. The installed rootfs and the user's Cargo registry/build caches are not removed. Starship's bundled `scan_timeout` is 150 ms: the default 30 ms intermittently expired on the phone, while the prompt's normal path remains below that ceiling. The builder measures prompt latency at idle and while traversing the preloaded Cargo registry.

## Build and artifact authentication

`scripts/build-arch-dev-rootfs.sh` is the native AArch64 build recipe. `.github/workflows/build-arch-dev-rootfs.yml` runs it on GitHub's `ubuntu-24.04-arm` runner and uploads a short-lived **review artifact only**. It does not create a repository, tag, or GitHub Release. The script verifies the upstream Arch Linux ARM generic archive's detached signature against the pinned signing-key fingerprint `68B3537F39A313B3E574D06777193F152BDBE6A6`, records its SHA-256, provisions the development stack, checks pacman and package ownership, and records package versions and build/extraction timings.

Each image version produces a gzip tar archive, a strict manifest containing the version, asset name, byte size, rootfs SHA-256, and source archive SHA-256, and a detached Ed25519 signature over the exact manifest bytes. The public key is embedded in the APK. The encrypted private key and passphrase stay outside the repository; CI expects them as `ROOTFS_SIGNING_KEY` and `ROOTFS_SIGNING_PASSPHRASE` secrets. The workflow removes its temporary signing files and uploads no secret material. Review the key custody and recovery plan before adding those repository secrets.

The rootfs archive is intentionally written without GNU `tar --sparse`. The Android installer uses Rust's `tar` crate, which does not reconstruct the GNU sparse PAX representation emitted by that option. With sparse output, files can be materialized under `GNUSparseFile.*` placeholder paths, so required files such as TCP fakeroot's `faked` daemon and `libfakeroot-0.so` disappear from their expected paths even though desktop GNU tar lists them normally. Gzip compresses zero-filled holes efficiently, so dense tar output has similar download size. The ARM64 build workflow runs the production extractor against the complete generated archive and requires both fakeroot binaries, the shared library, symlink, modes, and guest ownership metadata to survive before uploading the review artifact.

The Android build pins an exact rootfs version, release tag, and HTTPS release asset base URL through Gradle properties. `scripts/build-pinned-apk.sh` rejects unpinned builds. Installation verifies the manifest signature before trusting its asset name, downloads resumably, checks the signed size and SHA-256, and only then gives the cached archive to pr-cli. A changed rootfs uses a new version and a matching APK build; the prior signed image remains available for rollback. GitHub Releases can carry the APK and image as separate assets once the repository owner approves publication.

For a signed Android release, `scripts/build-signed-pinned-apk.sh` requires a protected Android keystore and explicit monotonic version code/name plus the same rootfs pin. Keystore values are read from environment variables and are never passed as Gradle command-line properties. It runs `assembleRelease`, verifies the APK signature with Android `apksigner`, checks packaged native libraries, and writes a versioned file under `dist/`. Keep the Android keystore separate from the rootfs Ed25519 signing key; configure either as CI secrets only after reviewing custody and recovery. The current build pipeline has no repository secrets configured and performs no release publication.

The recipe is repeatable and records exact inputs and outputs. Arch Linux ARM is rolling, so rebuilding at a later date may resolve newer repository packages and a newer Rust stable toolchain; it is not claimed to be byte-for-byte reproducible. `benchmark-<version>.txt`, `packages-<version>.txt`, `package-ownership-<version>.txt`, and contract logs record what a particular signed build actually contains. The native workflow builds PRoot and pr-cli from their pinned source and exercises the app's login arguments and guest environment with Android-style external ownership records.

## Installation progress and measurement

The setup screen reports transferred bytes, percentage, measured rate, phase, and elapsed time. Verification shows status without invented percentages. Extraction uses pr-cli's archive-consumption counters; when those counters are unavailable it uses a slow, subtle indeterminate segment and respects Android's animator setting. The build report measures the native CI extraction time as a comparison point. Actual Android download and PRoot extraction time require a device run of a published, pinned artifact and will be recorded separately; CI timings are not presented as phone benchmarks.

The prior base-only archive was 829,367,415 bytes (about 790.8 MiB). The older `.2` complete image was 909,815,353 bytes (about 867.8 MiB), 9.7% larger; its native report measured 20 seconds for extraction and 271 seconds for image construction. That artifact used the obsolete `dev` account and is not the new candidate. The next signed image gets its own timings; CI measurements are not Android phone benchmarks.

## Review and release steps

1. Review the script, pinned upstream inputs, public key, workflow permissions, license notices, and CI secret handling.
2. After repository details and publication are approved, configure the two signing secrets and run the workflow with a new immutable image version.
3. Inspect the downloaded review artifact and all benchmark, package inventory, ownership, and toolchain reports. Verify the signature and archive hash independently.
4. Publish the signed rootfs assets and manifest under the matching release tag; then build the APK with matching Gradle pins.
5. Verify the APK only downloads that release and test the fresh-install `triton` account and runtime smoke checks on the phone.

No release asset is currently published. A local debug APK built without release properties deliberately keeps Install disabled and reports that it has no pinned signed image; do not use it for another reinstall.

## Checks built into image provisioning

The image builder runs the existing acceptance checks before packaging:

- `pacman -Dk` and `pacman -Qo` confirm database integrity and package-managed `/usr/bin/fakeroot`, `rustup`, and `paru`;
- direct TCP fakeroot and normal makepkg produce a root-owned archive with the expected setuid mode;
- Paru builds and installs an isolated local smoke package through normal makepkg;
- Rustup has default stable, a dependency-free Cargo project compiles, and its binary runs;
- Fish loads Starship and the regular login-shell PATH works.
- The exact packaged Fish smoke runs under the `triton` login environment and fails native image construction if any required feature check reports a failure.
- Native PRoot and pr-cli built from pinned source run that smoke using the app's `login archlinuxarm --user triton /usr/bin/fish -l -i -c ...` command and Android-style external ownership records.
- Restricted sudo actually executes `sudo -n /usr/bin/pacman -Dk`; `sudo -n /usr/bin/id` must remain denied.

The user's earlier device results also confirmed TCP fakeroot with normal makepkg and Paru, installed Elio through `paru -S elio`, and launched Elio interactively. Those results validate the compatibility approach, not yet this prebuilt image pipeline. A full fresh-image phone validation remains mandatory before calling the default experience complete.
