#!/usr/bin/env bash
# Build the complete TritonTerm Arch Linux ARM image on a native AArch64 Linux host.
set -Eeuo pipefail

readonly BASE_URL="${TRITONTERM_ALARM_BASE_URL:-https://ca.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz}"
readonly BASE_NAME="${BASE_URL##*/}"
readonly ALARM_FINGERPRINT='68B3537F39A313B3E574D06777193F152BDBE6A6'
readonly ROOTFS_SIGNING_KEY="${TRITONTERM_ROOTFS_SIGNING_KEY:-}"
readonly ROOTFS_SIGNING_PASSPHRASE_FILE="${TRITONTERM_ROOTFS_SIGNING_PASSPHRASE_FILE:-}"
readonly VERSION="${1:-}"
readonly OUTPUT_DIR="${2:-$PWD/dist/arch-rootfs}"
readonly WORK_PARENT="${TRITONTERM_ROOTFS_WORK_PARENT:-${TMPDIR:-/tmp}}"

stage='preflight'
build_succeeded=0
work_dir=''
report=''
report_line() { printf '%s\n' "$*" | tee -a "$report"; }
fail() { local code="$1"; shift; printf '[rootfs-build] FAILED at %s (exit %s): %s\n' "$stage" "$code" "$*" >&2; exit "$code"; }
on_error() { local code=$?; printf '[rootfs-build] FAILED at %s (exit %s): command: %s\n' "$stage" "$code" "$BASH_COMMAND" >&2; exit "$code"; }
trap on_error ERR

[[ "$(id -u)" == 0 ]] || fail 2 'run through sudo on a native AArch64 runner'
[[ "$(uname -m)" == aarch64 ]] || fail 2 'the image must be built on a native AArch64 Linux runner'
[[ "$VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || fail 2 'usage: build-arch-dev-rootfs.sh VERSION OUTPUT_DIR'
[[ -s "$ROOTFS_SIGNING_KEY" && -s "$ROOTFS_SIGNING_PASSPHRASE_FILE" ]] ||
    fail 2 'set TRITONTERM_ROOTFS_SIGNING_KEY and TRITONTERM_ROOTFS_SIGNING_PASSPHRASE_FILE to the release-signing inputs'
for command in curl gpg gpgv bsdtar chroot tar gzip sha256sum openssl; do
    command -v "$command" >/dev/null || fail 2 "required host command is missing: $command"
done
mkdir -p "$OUTPUT_DIR" "$WORK_PARENT"
work_dir="$(mktemp -d "$WORK_PARENT/tritonterm-rootfs-${VERSION}.XXXXXX")"
report="$OUTPUT_DIR/benchmark-${VERSION}.txt"
: > "$report"
build_started="$(date +%s)"

readonly ROOTFS="$work_dir/rootfs"
readonly BASE_ARCHIVE="$work_dir/$BASE_NAME"
readonly BASE_SIGNATURE="$BASE_ARCHIVE.sig"
readonly GOPGHOME="$work_dir/gnupg"
readonly BUILD_LOG="$work_dir/rootfs-build.log"
readonly ARCHIVE_NAME="tritonterm-archlinuxarm-aarch64-${VERSION}.tar.gz"
readonly OUTPUT_ARCHIVE="$OUTPUT_DIR/$ARCHIVE_NAME"
readonly OUTPUT_MANIFEST="$OUTPUT_DIR/$ARCHIVE_NAME.manifest"
readonly OUTPUT_SIGNATURE="$OUTPUT_MANIFEST.sig"

report_line "format=tritonterm-rootfs-build-report-v1"
report_line "version=$VERSION"
report_line "base_url=$BASE_URL"
report_line "started_utc=$(date -u +%FT%TZ)"
report_line "builder_arch=$(uname -m)"

stage='base_archive_download'
stage_started="$(date +%s)"
curl --fail --location --retry 4 --retry-all-errors --output "$BASE_ARCHIVE" "$BASE_URL"
curl --fail --location --retry 4 --retry-all-errors --output "$BASE_SIGNATURE" "$BASE_URL.sig"
BASE_SHA256="$(sha256sum "$BASE_ARCHIVE" | awk '{print $1}')"
report_line "base_archive_bytes=$(stat -c %s "$BASE_ARCHIVE")"
report_line "base_archive_sha256=$BASE_SHA256"
report_line "base_download_seconds=$(( $(date +%s) - stage_started ))"

stage='base_archive_signature'
install -d -m 0700 "$GOPGHOME"
GNUPGHOME="$GOPGHOME" gpg --batch --import "$PWD/app/src/main/assets/archlinuxarm-builder.asc" >/dev/null 2>&1
actual_fingerprint="$(GNUPGHOME="$GOPGHOME" gpg --with-colons --fingerprint | awk -F: '$1 == "fpr" { print $10; exit }')"
[[ "$actual_fingerprint" == "$ALARM_FINGERPRINT" ]] || fail 3 "unexpected Arch Linux ARM signing key fingerprint: $actual_fingerprint"
GNUPGHOME="$GOPGHOME" gpg --batch --export "$ALARM_FINGERPRINT" > "$work_dir/alarm.gpg"
gpgv --keyring "$work_dir/alarm.gpg" "$BASE_SIGNATURE" "$BASE_ARCHIVE"

stage='base_rootfs_extraction'
stage_started="$(date +%s)"
mkdir -p "$ROOTFS"
bsdtar --numeric-owner -xpf "$BASE_ARCHIVE" -C "$ROOTFS"
report_line "base_extraction_seconds=$(( $(date +%s) - stage_started ))"
cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf"

chroot_guest() {
    chroot "$ROOTFS" /usr/bin/env -i \
        HOME=/root \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/bin \
        LANG=C.UTF-8 \
        LC_ALL=C.UTF-8 \
        SYSTEMD_OFFLINE=1 \
        "$@"
}

stage='pacman_keyring'
chroot_guest /bin/bash -lc 'pacman-key --init && pacman-key --populate archlinuxarm'

stage='developer_environment_provisioning'
stage_started="$(date +%s)"
install -d -m 0700 "$ROOTFS/root/.tritonterm-setup"
install -m 0755 app/src/main/assets/setup-arch-developer.sh "$ROOTFS/root/.tritonterm-setup/setup-arch-developer.sh"
install -m 0644 app/src/main/assets/starship.toml "$ROOTFS/root/.tritonterm-setup/starship.toml"
install -m 0644 app/src/main/assets/paru-bootstrap.PKGBUILD "$ROOTFS/root/.tritonterm-setup/paru-bootstrap.PKGBUILD"
chroot_guest /bin/bash -lc 'bash /root/.tritonterm-setup/setup-arch-developer.sh' 2>&1 | tee "$BUILD_LOG"
report_line "developer_provisioning_seconds=$(( $(date +%s) - stage_started ))"

stage='prune_boot_only_packages'
installed_packages="$(chroot_guest /usr/bin/pacman -Qq)"
remove_packages="$(printf '%s\n' "$installed_packages" | awk '$0 == "linux-aarch64" || $0 == "linux-firmware" || ($0 ~ /^linux-firmware-/ && $0 != "linux-firmware-tools") || $0 == "mkinitcpio" || $0 == "mkinitcpio-busybox"')"
if [[ -n "$remove_packages" ]]; then
    chroot_guest /usr/bin/pacman -Rns --noconfirm -- $remove_packages
fi
chroot_guest /usr/bin/pacman -Dk
remaining_boot_packages="$(chroot_guest /usr/bin/pacman -Qq | awk '$0 == "linux-aarch64" || $0 == "linux-firmware" || ($0 ~ /^linux-firmware-/ && $0 != "linux-firmware-tools") || $0 == "mkinitcpio" || $0 == "mkinitcpio-busybox')"
[[ -z "$remaining_boot_packages" ]] || fail 4 "boot-only packages remain installed: $remaining_boot_packages"

stage='image_personalization_contract'
install -d -m 0755 "$ROOTFS/etc/tritonterm" "$ROOTFS/usr/lib/tritonterm"
install -m 0755 app/src/main/assets/personalize-arch-account.sh "$ROOTFS/usr/lib/tritonterm/personalize-account.sh"
printf '%s\n' "$VERSION" > "$ROOTFS/etc/tritonterm/rootfs-release"
chmod 0644 "$ROOTFS/etc/tritonterm/rootfs-release"
touch "$ROOTFS/root/.tritonterm-userland-profile-v1"
chroot_guest /usr/bin/pacman -Qo /usr/bin/fakeroot /usr/bin/rustup /usr/bin/paru > "$OUTPUT_DIR/package-ownership-${VERSION}.txt"
chroot_guest /usr/bin/pacman -Q > "$OUTPUT_DIR/packages-${VERSION}.txt"
report_line "installed_package_count=$(wc -l < "$OUTPUT_DIR/packages-${VERSION}.txt" | tr -d ' ')"
chroot_guest /usr/bin/su -l dev -s /usr/bin/fish -c 'rustup show && rustc --version && cargo --version && fish -ic "starship prompt"' > "$OUTPUT_DIR/toolchain-${VERSION}.txt"
cp "$ROOTFS/var/cache/tritonterm/developer-setup.log" "$OUTPUT_DIR/build-log-${VERSION}.txt"

stage='account_personalization_smoke'
chroot_guest /usr/lib/tritonterm/personalize-account.sh tritonterm-review
chroot_guest /bin/bash -lc '
    entry="$(getent passwd tritonterm-review)"
    [[ -n "$entry" ]]
    IFS=: read -r name _ uid gid _ home shell <<< "$entry"
    [[ "$name" == tritonterm-review && "$uid" == 1000 && "$gid" == 1000 &&
       "$home" == /home/tritonterm-review && "$shell" == /usr/bin/fish ]]
    [[ "$(getent group 1000 | cut -d: -f1)" == tritonterm-review ]]
    id -nG tritonterm-review | tr " " "\n" | grep -qx wheel
    [[ -d /home/tritonterm-review/.rustup/toolchains/stable-aarch64-unknown-linux-gnu ]]
    [[ -d /home/tritonterm-review/.cargo ]]
    [[ "$(cat /etc/tritonterm/selected-user)" == tritonterm-review ]]
'
printf 'TRITONTERM_ACCOUNT_PERSONALIZATION_SMOKE_PASSED\n' | tee -a "$report"
chroot_guest /usr/bin/usermod --login dev tritonterm-review
chroot_guest /usr/bin/groupmod --new-name dev tritonterm-review
chroot_guest /usr/bin/usermod --home /home/dev --move-home dev
rm -f "$ROOTFS/etc/tritonterm/selected-user" "$ROOTFS/etc/tritonterm/account-selection"
chroot_guest /bin/bash -lc '[[ "$(getent passwd dev | cut -d: -f3-4)" == "1000:1000" && "$(getent passwd dev | cut -d: -f6-7)" == "/home/dev:/usr/bin/fish" ]]'

stage='remove_machine_specific_state'
chroot_guest /usr/bin/usermod --password '!' root
chroot_guest /usr/bin/usermod --password '!' dev
active_password_hashes="$(awk -F: '$2 !~ /^(!|\*)/ { print $1 }' "$ROOTFS/etc/shadow")"
[[ -z "$active_password_hashes" ]] || fail 4 "image contains accounts with active password hashes: $active_password_hashes"
rm -f "$ROOTFS/etc/machine-id" "$ROOTFS/var/lib/dbus/machine-id" "$ROOTFS/root/.bash_history" "$ROOTFS/home/dev/.bash_history"
rm -f "$ROOTFS"/etc/ssh/ssh_host_* 2>/dev/null || true
rm -rf "$ROOTFS/root/.ssh" "$ROOTFS/home/dev/.ssh"
rm -rf "$ROOTFS/root/.gnupg" "$ROOTFS/home/dev/.gnupg"
# pacman-key --init creates a local secret key. Never ship the builder's identity;
# first installation initializes and populates a device-local keyring instead.
rm -rf "$ROOTFS/etc/pacman.d/gnupg"
rm -f "$ROOTFS/root/.tritonterm-pacman-keyring-ready"
rm -rf "$ROOTFS/home/dev/rustup-smoke" "$ROOTFS/home/dev/.cache/tritonterm/paru-2.2.0"
find "$ROOTFS/var/cache/pacman/pkg" -mindepth 1 -maxdepth 1 -type f -delete 2>/dev/null || true

stage='rootfs_archive_creation'
stage_started="$(date +%s)"
tar --numeric-owner --xattrs --xattrs-include='*' --acls --sparse \
    --use-compress-program='gzip -1' -cf "$OUTPUT_ARCHIVE" -C "$ROOTFS" .
ARCHIVE_SIZE="$(stat -c %s "$OUTPUT_ARCHIVE")"
ARCHIVE_SHA256="$(sha256sum "$OUTPUT_ARCHIVE" | awk '{print $1}')"
BASE_SIZE="$(stat -c %s "$BASE_ARCHIVE")"
report_line "rootfs_uncompressed_bytes=$(du -sb "$ROOTFS" | awk '{print $1}')"
report_line "rootfs_archive_bytes=$ARCHIVE_SIZE"
report_line "rootfs_archive_sha256=$ARCHIVE_SHA256"
report_line "rootfs_compression_seconds=$(( $(date +%s) - stage_started ))"
report_line "base_archive_bytes=$BASE_SIZE"
report_line "rootfs_vs_base_download_percent=$(( ARCHIVE_SIZE * 100 / BASE_SIZE ))"

stage='release_manifest_signing'
cat > "$OUTPUT_MANIFEST" <<MANIFEST
format=tritonterm-arch-rootfs-v1
version=$VERSION
archive=$ARCHIVE_NAME
size=$ARCHIVE_SIZE
sha256=$ARCHIVE_SHA256
base_archive_sha256=$BASE_SHA256
MANIFEST
openssl pkeyutl -sign -rawin \
    -inkey "$ROOTFS_SIGNING_KEY" \
    -passin "file:$ROOTFS_SIGNING_PASSPHRASE_FILE" \
    -in "$OUTPUT_MANIFEST" -out "$OUTPUT_SIGNATURE"
[[ "$(stat -c %s "$OUTPUT_SIGNATURE")" == 64 ]] || fail 5 'release signature has an unexpected size'
openssl pkeyutl -verify -pubin \
    -inkey "$PWD/app/src/main/assets/tritonterm-rootfs-release-key.pem" \
    -rawin -in "$OUTPUT_MANIFEST" -sigfile "$OUTPUT_SIGNATURE" >/dev/null

stage='archive_extraction_benchmark'
stage_started="$(date +%s)"
verify_dir="$work_dir/archive-verification"
mkdir -p "$verify_dir"
tar --numeric-owner --xattrs --acls -xzf "$OUTPUT_ARCHIVE" -C "$verify_dir"
[[ -s "$verify_dir/etc/os-release" && -d "$verify_dir/var/lib/pacman/local" &&
   -x "$verify_dir/usr/bin/fish" && -x "$verify_dir/usr/bin/paru" &&
   -x "$verify_dir/usr/bin/rustup" && -d "$verify_dir/home/dev/.rustup/toolchains" ]] ||
    fail 6 're-extracted image did not pass required filesystem checks'
[[ ! -e "$verify_dir/etc/pacman.d/gnupg" && ! -e "$verify_dir/root/.tritonterm-pacman-keyring-ready" ]] ||
    fail 6 're-extracted image contains the build host pacman keyring'
[[ -z "$(awk -F: '$2 !~ /^(!|\*)/ { print $1 }' "$verify_dir/etc/shadow")" ]] ||
    fail 6 're-extracted image contains an active password hash'
report_line "archive_extraction_benchmark_seconds=$(( $(date +%s) - stage_started ))"
report_line "total_build_seconds=$(( $(date +%s) - build_started ))"
report_line "completed_utc=$(date -u +%FT%TZ)"
printf '%s\n' 'TRITONTERM_ARCH_DEV_ROOTFS_BUILD_PASSED' | tee -a "$report"
build_succeeded=1
rm -rf "$work_dir"
