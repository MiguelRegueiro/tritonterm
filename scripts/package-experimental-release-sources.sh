#!/usr/bin/env bash
set -Eeuo pipefail

version="${1:-}"
rootfs_dir="${2:-}"
output_dir="${3:-}"
source_index_dir="${4:-}"
android_version_code="${TRITONTERM_ANDROID_VERSION_CODE:-}"
android_version_name="${TRITONTERM_ANDROID_VERSION_NAME:-${version}-exp.1}"
release_tag="arch-rootfs-${version}-exp.1"
release_base_url="https://github.com/MiguelRegueiro/tritonterm-releases/releases/download/${release_tag}"
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

[[ "$version" =~ ^[0-9]{4}\.[0-9]{2}\.[0-9]{2}\.[0-9]+$ ]] || {
    printf 'Usage: %s YYYY.MM.DD.N ROOTFS_ARTIFACT_DIR OUTPUT_DIR\n' "$0" >&2
    exit 2
}
[[ -d "$rootfs_dir" && -d "$output_dir" && -d "$source_index_dir" ]] || {
    printf 'Rootfs artifact, output, and exact package source index directories must already exist.\n' >&2
    exit 2
}
[[ "$android_version_code" =~ ^[1-9][0-9]{0,8}$ ]] || {
    printf 'Set TRITONTERM_ANDROID_VERSION_CODE to the exact positive APK version code.\n' >&2
    exit 2
}

archive="$rootfs_dir/tritonterm-archlinuxarm-aarch64-${version}.tar.gz"
manifest="$archive.manifest"
signature="$manifest.sig"
license_csv="$rootfs_dir/package-licenses-${version}.csv"
[[ -s "$archive" && -s "$manifest" && -s "$signature" && -s "$license_csv" ]] || {
    printf 'Missing rootfs archive, manifest, signature, or package license inventory for %s.\n' "$version" >&2
    exit 2
}

work="$(mktemp -d "${TMPDIR:-/tmp}/tritonterm-release-source-${version}.XXXXXX")"
cleanup() { rm -rf "$work"; }
trap cleanup EXIT
source_tree="$work/source"
mkdir -p "$source_tree"

# Termux's GPLv3-only terminal-view is part of the combined app distribution.
# Include the complete license text and clearly license the combined TritonTerm
# source snapshot; separately licensed third-party files retain their notices.
cp "$project_root/app/src/main/assets/licenses/runtime/GPL-3.0.txt" \
    "$source_tree/COPYING"
cat > "$source_tree/LICENSE" <<'EOF'
TritonTerm's combined Android application and its app-owned source changes in this
experimental release are licensed under GNU GPL version 3 only. Third-party
components keep their own licenses and notices; see COPYING, component license
files, and SOURCE-AVAILABILITY.md. This grant does not change licenses of the
independently packaged Arch Linux ARM rootfs programs.
EOF

# Export the reviewed application checkout without Git metadata, ignored build output,
# signing material, or nested submodules. Overlay every tracked working-tree change so
# this source release can reproduce the exact APK and its provenance documentation.
git -C "$project_root" archive HEAD | tar -xf - -C "$source_tree"
while IFS= read -r -d '' changed_path; do
    [[ -f "$project_root/$changed_path" ]] || continue
    mkdir -p "$source_tree/$(dirname "$changed_path")"
    cp -p "$project_root/$changed_path" "$source_tree/$changed_path"
done < <(git -C "$project_root" diff --name-only -z HEAD)
git -C "$project_root" diff --binary HEAD > "$source_tree/LOCAL-WORKTREE-CHANGES.patch"

# Include new source files as well as tracked modifications. In particular,
# reproducible runtime patches and newly bundled shell/license assets may not
# be tracked yet while a review artifact is being prepared. Ignore rules keep
# build caches and Python bytecode out of the corresponding-source archive.
while IFS= read -r -d '' added_path; do
    [[ -f "$project_root/$added_path" ]] || continue
    mkdir -p "$source_tree/$(dirname "$added_path")"
    cp -p "$project_root/$added_path" "$source_tree/$added_path"
done < <(git -C "$project_root" ls-files --others --exclude-standard -z)

# These release-provenance tools are deliberately kept out of the APK. They are
# source-only files in the current worktree, so include them explicitly alongside
# the tracked snapshot and its worktree patch.
install -m 0644 "$project_root/scripts/generate-rootfs-source-index.py" \
    "$source_tree/scripts/generate-rootfs-source-index.py"
install -m 0755 "$project_root/scripts/package-experimental-release-sources.sh" \
    "$source_tree/scripts/package-experimental-release-sources.sh"

# Ship PRoot's exact source commit as a source tree. Nested submodules are Git links,
# not expanded by git archive, so the AOSP bionic subtree (which carries a public
# test signing key) is never copied into this release bundle. The runtime build only
# requires talloc; include its exact pinned source and licensing notice below.
pr_source="$project_root/third_party/oonid-pr"
pr_commit="$(git -C "$pr_source" rev-parse HEAD)"
talloc_commit="$(git -C "$pr_source/vendor/samba" rev-parse HEAD)"
pr_archive_root="$source_tree/third_party/oonid-pr"
mkdir -p "$pr_archive_root"
git -C "$pr_source" archive HEAD | tar -xf - -C "$pr_archive_root"
rm -rf "$pr_archive_root/vendor"
mkdir -p "$pr_archive_root/vendor/samba/lib"
cp -a "$pr_source/vendor/samba/lib/talloc" "$pr_archive_root/vendor/samba/lib/talloc"
install -m 0644 "$pr_source/vendor/samba/COPYING" "$pr_archive_root/vendor/samba/COPYING"
# The checked-out PRoot tree may carry reviewed local changes that are not yet
# represented by the submodule commit. Preserve the exact diff and overlay the
# changed source files so this archive contains the source used for the APK's
# native PRoot binary, not just the upstream base snapshot.
git -C "$pr_source" diff --binary HEAD > \
    "$pr_archive_root/TRITONTERM_WORKTREE_CHANGES.patch"
while IFS= read -r -d '' changed_path; do
    [[ -f "$pr_source/$changed_path" ]] || continue
    mkdir -p "$pr_archive_root/$(dirname "$changed_path")"
    cp -p "$pr_source/$changed_path" "$pr_archive_root/$changed_path"
done < <(git -C "$pr_source" diff --name-only -z HEAD)
printf '%s\n' "$pr_commit" > "$pr_archive_root/TRITONTERM_SOURCE_COMMIT"
printf '%s\n' "$talloc_commit" > \
    "$pr_archive_root/vendor/samba/TRITONTERM_TALLOC_COMMIT"

# The Arch Linux ARM keyring source is used by the rootfs builder and is small;
# its public certificate is not a signing secret.
keyring_source="$project_root/third_party/archlinuxarm-keyring"
keyring_commit="$(git -C "$keyring_source" rev-parse HEAD)"
keyring_archive_root="$source_tree/third_party/archlinuxarm-keyring"
mkdir -p "$keyring_archive_root"
git -C "$keyring_source" archive HEAD | tar -xf - -C "$keyring_archive_root"
printf '%s\n' "$keyring_commit" > \
    "$keyring_archive_root/TRITONTERM_SOURCE_COMMIT"

install -m 0644 "$license_csv" "$source_tree/PACKAGE-LICENSES-${version}.csv"
[[ -s "$source_index_dir/package-source-index.csv" && -d "$source_index_dir/package-recipes" ]] || {
    printf 'Exact package-source index or recipe directory is missing.\n' >&2
    exit 2
}
[[ ! -e "$source_index_dir/source-index-failures.txt" ]] || {
    printf 'Package source index has unresolved recipes; refusing to package release sources.\n' >&2
    exit 1
}
cp -a "$source_index_dir/package-recipes" "$source_tree/"
install -m 0644 "$source_index_dir/package-source-index.csv" \
    "$source_tree/PACKAGE-SOURCE-INDEX-${version}.csv"
app_commit="$(git -C "$project_root" rev-parse HEAD)"
cat > "$work/SOURCE-AVAILABILITY.template" <<'EOF'
# TritonTerm experimental release @VERSION@: source and license materials

This release is an aggregate of independently licensed programs. The full package
license texts and notices shipped in the rootfs are also provided in the separate
`tritonterm-rootfs-licenses-@VERSION@.tar.gz` asset. The accompanying package CSV
records every installed package version, its pacman license expression, upstream
project URL, and Arch Linux ARM package source page.

## Rootfs packages

Each row of `PACKAGE-LICENSES-@VERSION@.csv` identifies the exact package name and
version in this image. `PACKAGE-SOURCE-INDEX-@VERSION@.csv` maps each installed
package to the exact version-matched recipe commit. Each PKGBUILD is mirrored under
`package-recipes/` in this archive. Arch Linux ARM recipes link to immutable commits
in the public Arch Linux ARM PKGBUILDs repository; other official Arch recipes link
to immutable commits in Arch Linux's packaging GitLab project. The exact recipe
contains the source URLs, patch list, and checksums used for the build. These
publicly accessible upstream source locations, pinned by immutable recipe
revisions and checksums, provide corresponding source for the packaged programs;
TritonTerm's release source index preserves the access information. Meta
packages (`base`,
`base-devel`, and `licenses`) contain package metadata rather than executable
programs. The exact fakeroot source tarball used to build TCP fakeroot 2.1.4 is
included in this source archive; the setup recipe pins its SHA-256 and uses
`--with-ipc=tcp`. Paru's pinned bootstrap PKGBUILD and source checksum are in
`app/src/main/assets/paru-bootstrap.PKGBUILD`.

The release mirrors the exact recipe files so the source URL and patch lists remain
available if a current package page changes. TritonTerm does not modify these
repository packages' program source. The full copyright and license texts,
including custom and LicenseRef notices, are shipped without alteration in the
separate license bundle.

The image also includes the Rust crate source cache under
`/home/dev/.cargo/registry/src/`; this is the exact source cache for the preloaded
Rust development environment. Its crate license files, together with Rustup's
toolchain and documentation license notices, are included in the license bundle.

## TritonTerm APK and modified PRoot source

The Android application and local Termux terminal-emulator Java module are in this
archive. The terminal session behavior change is in
`terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`; the
upstream revision and patch description are in `docs/termux-terminal-session-patch.md`.
The APK uses Termux `terminal-view` 0.118.0. Its exact source is at immutable
upstream commit `6e2689f55295fa444be8ac8592c527c2c5ef3253` under `terminal-view/`;
the source artifact is also available at
https://jitpack.io/com/github/termux/termux-app/terminal-view/v0.118.0/terminal-view-v0.118.0-sources.jar.
The combined app source is licensed under GPLv3-only in `LICENSE` and `COPYING`;
the Termux module exceptions and notices remain in `terminal-emulator/LICENSE.md`.
The APK's Bouncy Castle 1.80.2 license and Apache-2.0 notice for its modified BZIP2
component are included in the app's runtime license assets.
The PRoot source snapshot `@PR_COMMIT@` descends from upstream oonid/pr commit
`7029b1fe8fa84d64339cabe38f9c5be2ea2ae7ac` and includes two local commits for
guest SIGSEGV recording and extraction progress. Those commits are not public
upstream Git refs; this archive contains the exact source snapshot. Additional
checked-out PRoot changes are included both as the exact overlay and as
`third_party/oonid-pr/TRITONTERM_WORKTREE_CHANGES.patch`, applicable to the pinned
commit. TritonTerm's other changes are preserved in the tracked patches under
`scripts/patches/`; the build script checks and applies them when needed. The exact
talloc source required by the PRoot native build
is included at `third_party/oonid-pr/vendor/samba/lib/talloc` from Samba commit
`@TALLOC_COMMIT@`. The AOSP bionic Git submodule
is not needed by the build and is intentionally omitted.

Build steps, toolchain versions, binary hashes, and license details are in
`docs/native-runtime.md`. The corresponding license texts are in
`app/src/main/assets/licenses/runtime/`. AndroidX Graphics Path and Termux JNI
artifacts are unmodified Apache-2.0 binaries; their source projects and exact
artifact hashes are documented there.

The Android APK was built from the source commit and the included local changes
patch using `scripts/build-signed-pinned-apk.sh` with these values:

    TRITONTERM_ARCH_ROOTFS_VERSION=@VERSION@
    TRITONTERM_ARCH_ROOTFS_RELEASE_TAG=@ROOTFS_RELEASE_TAG@
    TRITONTERM_ARCH_ROOTFS_RELEASE_BASE_URL=@ROOTFS_RELEASE_BASE_URL@
    TRITONTERM_ANDROID_VERSION_CODE=@ANDROID_VERSION_CODE@
    TRITONTERM_ANDROID_VERSION_NAME=@ANDROID_VERSION_NAME@

The local patch is included so the published source corresponds to the reviewed
APK even though the private development repository remains at the recorded base
commit `@APP_COMMIT@`.

## License review notes

The package inventory contains GPL/LGPL packages and custom identifiers. The
included license tree preserves each package's own notice. The reviewed custom
entries include Autoconf's GPL configure-script exception, Flex's BSD-derived
license, MIT Kerberos's BSD-style license, leancrypto's GPL/BSD options, LMDB's
OpenLDAP license, popt's MIT-style terms, sudo's ISC-style terms, Vim's license,
public-domain tzdata and IANA protocol data, and SQLite's public-domain notice.
GnuPG's Unicode data license and ICU's Unicode License V3 notices are also included.
For this exact image, the package inventory contains @PACKAGE_COUNT@ installed
records across @PACKAGE_BASE_COUNT@ package bases, with a package license expression and upstream URL for every
record. The custom identifiers (`custom`, `custom:OpenLDAP`, `custom:flex`,
`custom:none`, and `custom:vim`) were checked against their corresponding license
texts in the accompanying license bundle. The full declared license expressions
are preserved in the CSV; no package declares a proprietary or non-free license.
The rootfs builder also checks that its signed archive retains root ownership and
the setuid mode required by `/usr/bin/sudo`.

The Android APK's embedded rootfs verification PEM is a public key only. No private
rootfs signing key, passphrase, developer credential, or device-specific signing
material is included in this source bundle.
EOF
sed -e "s/@VERSION@/$version/g" \
    -e "s/@ROOTFS_RELEASE_TAG@/$release_tag/g" \
    -e "s|@ROOTFS_RELEASE_BASE_URL@|$release_base_url|g" \
    -e "s/@ANDROID_VERSION_CODE@/$android_version_code/g" \
    -e "s/@ANDROID_VERSION_NAME@/$android_version_name/g" \
    -e "s/@PACKAGE_COUNT@/$(($(wc -l < "$license_csv") - 1))/g" \
    -e "s/@PACKAGE_BASE_COUNT@/$(tail -n +2 "$license_csv" | cut -d, -f5 | sort -u | wc -l | tr -d ' ')/g" \
    -e "s/@PR_COMMIT@/$pr_commit/g" \
    -e "s/@TALLOC_COMMIT@/$talloc_commit/g" \
    -e "s/@APP_COMMIT@/$app_commit/g" \
    "$work/SOURCE-AVAILABILITY.template" > "$source_tree/SOURCE-AVAILABILITY.md"

mkdir -p "$work/licenses"
# Extract only license and notice files from the signed rootfs archive; do not unpack
# the full 2.9 GiB rootfs to prepare the standalone notice asset.
tar --wildcards --wildcards-match-slash -xzf "$archive" -C "$work/licenses" \
    ./usr/share/licenses \
    './home/dev/.cargo/registry/src/*/*/LICENSE*' \
    './home/dev/.cargo/registry/src/*/*/COPYING*' \
    './home/dev/.cargo/registry/src/*/*/NOTICE*' \
    './home/dev/.rustup/toolchains/*/share/doc/rust/**/LICENSE*'
fakeroot_name='fakeroot_2.1.4.orig.tar.xz'
tar -xOzf "$archive" "./var/cache/tritonterm/fakeroot-tcp/2.1.4/$fakeroot_name" \
    > "$source_tree/$fakeroot_name"
printf '%s  %s\n' \
    '0822bd5a9f0cf19d2ba0546b88b0432d4d3d9917db62c57b74044ccadba06e49' \
    "$fakeroot_name" > "$source_tree/fakeroot-source.sha256"

tar --sort=name --mtime='UTC 2026-11-12' --owner=0 --group=0 --numeric-owner \
    -czf "$output_dir/tritonterm-android-pr-source-${version}.tar.gz" \
    -C "$work" source
tar -xOzf "$output_dir/tritonterm-android-pr-source-${version}.tar.gz" \
    source/SOURCE-AVAILABILITY.md > "$output_dir/SOURCE-AVAILABILITY-${version}.md"
tar --sort=name --mtime='UTC 2026-11-12' --owner=0 --group=0 --numeric-owner \
    -czf "$output_dir/tritonterm-rootfs-licenses-${version}.tar.gz" \
    -C "$work/licenses" ./usr/share/licenses ./home/dev/.cargo ./home/dev/.rustup
install -m 0644 "$license_csv" "$output_dir/package-licenses-${version}.csv"

sha256sum \
    "$output_dir/tritonterm-android-pr-source-${version}.tar.gz" \
    "$output_dir/tritonterm-rootfs-licenses-${version}.tar.gz" \
    "$output_dir/package-licenses-${version}.csv"
