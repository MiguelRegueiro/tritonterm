# Arch Linux ARM installation lifecycle

TritonTerm's target first-run path installs one complete prebuilt AArch64 development rootfs. It is built on native AArch64 CI from the signed Arch Linux ARM generic archive, provisioned with the pinned compatibility recipes, signed as a TritonTerm release artifact, and downloaded separately from the APK. See [the developer image guide](arch-developer-environment.md) for artifact format, verification, the fixed `triton` account, and release review.

The Android app verifies the signed manifest and archive checksum before giving the exact cached tarball to pr-cli. pr-cli extracts into its owned rootfs location and retains its interrupted-install marker. Once extraction completes, the app applies the existing narrow pacman/PRoot compatibility settings, checks the fixed account, and initializes a device-local pacman keyring. There is no first-boot user rename, package installation, or pacman repository refresh.

Normal setup does not upgrade packages, compile developer tools, replace `/usr/bin/fakeroot`, or rewrite the pacman package database. The image already includes Git, Fish, Starship, base-devel, Rustup stable, Paru, `bat`, and matching TCP fakeroot components. Pacman database refreshes, package installation, and upgrades remain user-controlled.

Downloads report bytes, percentage, and measured transfer rate. Extraction reports pr-cli's consumed archive bytes and entry count when available. Signature verification is a status phase. Operations without a measured fraction use a slow neutral indicator or remain static when Android animations are disabled. Setup failures retain the process output and stage details in Diagnostics; optional image-feature checks are reported separately and do not prevent a usable shell from opening.

The manually dispatched image build workflow publishes the archive, signed manifest, benchmark report, package inventory, package ownership checks, and toolchain versions as a GitHub Release in `tritonterm-releases`. Its temporary PRoot test binary is deleted after use; the rootfs is not stored as an Actions artifact. A matching APK must be built separately with the new immutable image pin.
