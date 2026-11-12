# Arch Linux ARM installation lifecycle

TritonTerm's target first-run path installs one complete prebuilt AArch64 development rootfs. It is built on native AArch64 CI from the signed Arch Linux ARM generic archive, provisioned with the pinned compatibility recipes, signed as a TritonTerm release artifact, and downloaded separately from the APK. See [the developer image guide](arch-developer-environment.md) for artifact format, verification, account personalization, and release review.

The Android app verifies the signed manifest and archive checksum before giving the exact cached tarball to pr-cli. pr-cli extracts into its owned rootfs location and retains its interrupted-install marker. The app only proceeds after extraction is complete, then applies the narrow pacman settings required by Android/PRoot and customizes the image's template account. Account personalization is restart-safe and preserves numeric ownership.

Normal setup does not upgrade packages, compile developer tools, replace `/usr/bin/fakeroot`, or rewrite the pacman package database. The image already includes Git, Fish, Starship, base-devel, Rustup stable, Paru, and matching TCP fakeroot components. The first setup refreshes repository databases without upgrading installed packages. Package installation and upgrades remain user-controlled.

Downloads report bytes, percentage, and measured transfer rate. Extraction reports pr-cli's consumed archive bytes and entry count when available. Signature verification is a status phase. Operations without a measured fraction use a slow neutral indicator or remain static when Android animations are disabled. Setup failures expose stage/command details and an in-app view/share action for the setup log.

The image build workflow produces a temporary review artifact with the archive, signed manifest, benchmark report, package inventory, package ownership checks, and toolchain versions. It does not publish a GitHub Release. Only after those files and signing arrangements are reviewed should a matching image release and APK be published and used for a clean-device acceptance run.
