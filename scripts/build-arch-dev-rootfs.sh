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
guest_mounts=()
report_line() { printf '%s\n' "$*" | tee -a "$report"; }
fail() { local code="$1"; shift; printf '[rootfs-build] FAILED at %s (exit %s): %s\n' "$stage" "$code" "$*" >&2; exit "$code"; }
on_error() { local code=$?; printf '[rootfs-build] FAILED at %s (exit %s): command: %s\n' "$stage" "$code" "$BASH_COMMAND" >&2; exit "$code"; }
trap on_error ERR
cleanup_guest_mounts() {
    local index
    for ((index=${#guest_mounts[@]} - 1; index >= 0; index--)); do
        umount -R "${guest_mounts[index]}" 2>/dev/null || true
    done
}
trap cleanup_guest_mounts EXIT

[[ "$(id -u)" == 0 ]] || fail 2 'run through sudo on a native AArch64 runner'
[[ "$(uname -m)" == aarch64 ]] || fail 2 'the image must be built on a native AArch64 Linux runner'
[[ "$VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || fail 2 'usage: build-arch-dev-rootfs.sh VERSION OUTPUT_DIR'
[[ -s "$ROOTFS_SIGNING_KEY" && -s "$ROOTFS_SIGNING_PASSPHRASE_FILE" ]] ||
    fail 2 'set TRITONTERM_ROOTFS_SIGNING_KEY and TRITONTERM_ROOTFS_SIGNING_PASSPHRASE_FILE to the release-signing inputs'
for command in curl gpg gpgv bsdtar chroot mount mountpoint umount tar gzip sha256sum openssl python3; do
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
readonly ROOTFS_RESOLV_BACKUP="$work_dir/rootfs-resolv.conf.original"
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
resolver_source='/etc/resolv.conf'
if [[ -s /run/systemd/resolve/resolv.conf ]] &&
    awk '$1 == "nameserver" && $2 !~ /^127\./ && $2 != "::1" { found=1 } END { exit !found }' /run/systemd/resolve/resolv.conf; then
    resolver_source='/run/systemd/resolve/resolv.conf'
fi
rootfs_resolv_existed=false
if [[ -e "$ROOTFS/etc/resolv.conf" || -L "$ROOTFS/etc/resolv.conf" ]]; then
    cp -a "$ROOTFS/etc/resolv.conf" "$ROOTFS_RESOLV_BACKUP"
    rootfs_resolv_existed=true
fi
rm -f "$ROOTFS/etc/resolv.conf"
install -m 0644 "$resolver_source" "$ROOTFS/etc/resolv.conf"
report_line "build_resolver_source=$resolver_source"
report_line "build_resolver_nameservers=$(awk '$1 == "nameserver" { printf "%s%s", sep, $2; sep="," } END { print "" }' "$resolver_source")"

chroot_guest() {
    chroot "$ROOTFS" /usr/bin/env -i \
        HOME=/root \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/bin:/bin \
        LANG=C.UTF-8 \
        LC_ALL=C.UTF-8 \
        SYSTEMD_OFFLINE=1 \
        "$@"
}

run_image_contract() {
    local label="$1"
    shift
    local output="$OUTPUT_DIR/contract-${VERSION}-${label}.txt"
    local result=0
    chroot_guest "$@" > "$output" 2>&1 || result=$?
    if (( result != 0 )); then
        cat "$output" >&2
        fail 4 "image contract '${label}' failed (exit ${result}); output: ${output}"
    fi
    cat "$output"
}

mount_guest_filesystems() {
    # libalpm's CheckSpace logic needs the chroot root to appear as a mount
    # point in /proc/self/mountinfo when it resolves the pacman cache's device.
    # arch-chroot does this bind mount for the same reason.
    mount --bind "$ROOTFS" "$ROOTFS"
    guest_mounts+=("$ROOTFS")
    mount --make-private "$ROOTFS"
    mkdir -p "$ROOTFS/dev" "$ROOTFS/proc" "$ROOTFS/sys"
    # The build chroot is non-interactive and only needs the device nodes
    # themselves (null, zero, random, etc.). A recursive bind also imports
    # the host's nested /dev/pts, /dev/shm, and other mounts; on hosted ARM
    # runners those can remain busy after the chroot has exited and prevent
    # packaging the otherwise complete rootfs.
    mount --bind /dev "$ROOTFS/dev"
    guest_mounts+=("$ROOTFS/dev")
    mount -t proc proc "$ROOTFS/proc"
    guest_mounts+=("$ROOTFS/proc")
    mount -t sysfs sysfs "$ROOTFS/sys"
    guest_mounts+=("$ROOTFS/sys")
}

unmount_guest_filesystems() {
    local index target
    for ((index=${#guest_mounts[@]} - 1; index >= 0; index--)); do
        target="${guest_mounts[index]}"
        if mountpoint -q "$target"; then
            if [[ "$target" == "$ROOTFS/dev" ]]; then
                # Hosted runners may retain references to host device nodes
                # after all chroot commands have exited. Detach this
                # build-only mount subtree, then ensure it no longer overlays
                # the rootfs before creating the archive.
                umount -R -l "$target"
                if mountpoint -q "$target"; then
                    fail 4 "temporary device mount is still attached: $target"
                fi
            else
                if ! umount -R "$target"; then
                    local proc proc_root proc_name proc_cwd
                    for proc in /proc/[0-9]*; do
                        proc_root="$(readlink "$proc/root" 2>/dev/null || true)"
                        case "$proc_root" in
                            "$ROOTFS"|"$ROOTFS"/*)
                                proc_name="$(cat "$proc/comm" 2>/dev/null || true)"
                                proc_cwd="$(readlink "$proc/cwd" 2>/dev/null || true)"
                                printf '[rootfs-build] chroot process holding mount: pid=%s name=%s root=%s cwd=%s\n' \
                                    "${proc##*/}" "$proc_name" "$proc_root" "$proc_cwd" >&2
                                ;;
                        esac
                    done
                    return 1
                fi
            fi
        fi
    done
    guest_mounts=()
}

stage='chroot_mount_setup'
mount_guest_filesystems

stage='pacman_keyring'
chroot_guest /bin/bash -lc 'pacman-key --init && pacman-key --populate archlinuxarm'

stage='developer_environment_provisioning'
stage_started="$(date +%s)"
install -d -m 0700 "$ROOTFS/root/.tritonterm-setup"
install -m 0755 scripts/arch-image/setup-arch-developer.sh "$ROOTFS/root/.tritonterm-setup/setup-arch-developer.sh"
install -m 0644 scripts/arch-image/starship.toml "$ROOTFS/root/.tritonterm-setup/starship.toml"
install -m 0644 app/src/main/assets/tritonterm-fish-system-config.fish "$ROOTFS/root/.tritonterm-setup/tritonterm-fish-system-config.fish"
install -m 0644 scripts/arch-image/paru-bootstrap.PKGBUILD "$ROOTFS/root/.tritonterm-setup/paru-bootstrap.PKGBUILD"
install -m 0644 scripts/arch-image/starship-prompt-benchmark.fish "$ROOTFS/tmp/tritonterm-starship-prompt-benchmark.fish"
install -d -m 0755 "$ROOTFS/etc/tritonterm"
install -m 0644 app/src/main/assets/arch-guest-identity-smoke.fish "$ROOTFS/etc/tritonterm/guest-smoke.fish"
stage='guest_dns_preflight'
resolved_mirror="$(chroot_guest /usr/bin/getent ahostsv4 mirror.archlinuxarm.org | awk 'NR == 1 { print $1 }')"
[[ -n "$resolved_mirror" ]] || fail 7 'the guest could not resolve mirror.archlinuxarm.org before pacman provisioning'
report_line "guest_mirror_ipv4=$resolved_mirror"
stage='developer_environment_provisioning'
chroot_guest /bin/bash -lc 'bash /root/.tritonterm-setup/setup-arch-developer.sh' 2>&1 | tee "$BUILD_LOG"
report_line "developer_provisioning_seconds=$(( $(date +%s) - stage_started ))"

stage='fresh_install_pacman_database_integrity'
chroot_guest /usr/bin/pacman -Dk
printf 'TRITONTERM_FRESH_INSTALL_PACMAN_DATABASE_PASSED\n' | tee -a "$report"

stage='prune_boot_only_packages'
installed_packages="$(chroot_guest /usr/bin/pacman -Qq)"
remove_packages="$(printf '%s\n' "$installed_packages" | awk '$0 == "linux-aarch64" || $0 == "linux-firmware" || ($0 ~ /^linux-firmware-/ && $0 != "linux-firmware-tools") || $0 == "mkinitcpio" || $0 == "mkinitcpio-busybox"')"
if [[ -n "$remove_packages" ]]; then
    chroot_guest /usr/bin/pacman -Rns --noconfirm -- $remove_packages
fi
chroot_guest /usr/bin/pacman -Dk
remaining_boot_packages="$(chroot_guest /usr/bin/pacman -Qq | awk '$0 == "linux-aarch64" || $0 == "linux-firmware" || ($0 ~ /^linux-firmware-/ && $0 != "linux-firmware-tools") || $0 == "mkinitcpio" || $0 == "mkinitcpio-busybox"')"
[[ -z "$remaining_boot_packages" ]] || fail 4 "boot-only packages remain installed: $remaining_boot_packages"

stage='image_identity_contract'
install -d -m 0755 "$ROOTFS/etc/tritonterm"
printf '%s\n' "$VERSION" > "$ROOTFS/etc/tritonterm/rootfs-release"
chmod 0644 "$ROOTFS/etc/tritonterm/rootfs-release"
printf '%s\n' 'v1' > "$ROOTFS/etc/tritonterm/proot-userland-metadata-v1"
chmod 0644 "$ROOTFS/etc/tritonterm/proot-userland-metadata-v1"
touch "$ROOTFS/root/.tritonterm-userland-profile-v1"
chroot_guest /usr/bin/pacman -Qo /usr/bin/fakeroot /usr/bin/rustup /usr/bin/paru > "$OUTPUT_DIR/package-ownership-${VERSION}.txt"
chroot_guest /usr/bin/pacman -Q > "$OUTPUT_DIR/packages-${VERSION}.txt"
report_line "installed_package_count=$(wc -l < "$OUTPUT_DIR/packages-${VERSION}.txt" | tr -d ' ')"
run_image_contract fixed_account /bin/bash -lc '[[ "$(getent passwd triton | cut -d: -f3-4)" == "1000:1000" && "$(getent passwd triton | cut -d: -f6-7)" == "/home/triton:/usr/bin/fish" && "$(getent group 1000 | cut -d: -f1)" == triton && "$(stat -c %u:%g /home/triton)" == "1000:1000" && -z "$(getent passwd alarm || true)" ]]
! grep -qE "usermod[[:space:]]+--login|groupmod[[:space:]]+--new-name" /root/.tritonterm-setup/setup-arch-developer.sh'
run_image_contract rustup_show /usr/bin/su -l triton -s /usr/bin/fish -c 'rustup show'
run_image_contract rustc_version /usr/bin/su -l triton -s /usr/bin/fish -c 'rustc --version'
run_image_contract cargo_version /usr/bin/su -l triton -s /usr/bin/fish -c 'cargo --version'
run_image_contract utf8_locale /usr/bin/env LANG=C.UTF-8 LC_ALL=C.UTF-8 /bin/bash -lc 'test "$(locale charmap)" = UTF-8 && bsdtar -cf /dev/null -T /dev/null'
run_image_contract timezone_winter /usr/bin/env TZ=Europe/Madrid /usr/bin/date -d '2026-01-15 12:00:00 UTC' +%H:%M
grep -qx '13:00' "$OUTPUT_DIR/contract-${VERSION}-timezone_winter.txt" || fail 4 'Europe/Madrid winter offset did not include standard time'
run_image_contract timezone_summer /usr/bin/env TZ=Europe/Madrid /usr/bin/date -d '2026-07-15 12:00:00 UTC' +%H:%M
grep -qx '14:00' "$OUTPUT_DIR/contract-${VERSION}-timezone_summer.txt" || fail 4 'Europe/Madrid summer offset did not include daylight saving time'
run_image_contract starship_time_timezone /usr/bin/su -l triton -s /usr/bin/fish -c \
    'set output (env TZ=Europe/Madrid starship module time); and set expected (env TZ=Europe/Madrid date "+%H:%M"); and string match -q "*$expected*" "$output"'
run_image_contract starship_login_prompt /usr/bin/su -l triton -s /usr/bin/fish -c 'fish -ic "starship prompt"'
run_image_contract starship_prompt_benchmark /usr/bin/su -l triton -s /usr/bin/fish -c \
    '/usr/bin/fish /tmp/tritonterm-starship-prompt-benchmark.fish'
run_image_contract identity /usr/bin/su -l triton -s /usr/bin/fish -c \
    'test (id -u) = 1000; and test (id -g) = 1000; and test "$USER" = triton; and test "$HOME" = /home/triton; and test "$PWD" = "$HOME"; and test "$SHELL" = /usr/bin/fish; and test -x (command -s id); and test -x (command -s sudo); and test -x (command -s fakeroot); and set -q TRITONTERM_FISH_CONFIG_LOADED'
stage='packaged_fish_smoke'
fish_smoke_output="$OUTPUT_DIR/contract-${VERSION}-packaged_fish_smoke.txt"
chroot_guest /usr/bin/su -l triton -s /usr/bin/fish -c \
    'env LANG=C.UTF-8 TZ=Europe/Madrid /usr/bin/fish -l -i -c "source /etc/tritonterm/guest-smoke.fish"' > "$fish_smoke_output" 2>&1 || {
    cat "$fish_smoke_output" >&2
    fail 4 'the packaged Fish smoke script did not complete under the triton login environment'
}
cat "$fish_smoke_output"
if grep -q '^CHECK_FAILED ' "$fish_smoke_output" || ! grep -q '^TRITONTERM_GUEST_CHECKS_COMPLETED failures=0$' "$fish_smoke_output"; then
    fail 4 "the packaged Fish login smoke reported failed features; output: $fish_smoke_output"
fi
run_image_contract fakeroot_identity /usr/bin/su -l triton -s /usr/bin/fish -c 'test (fakeroot -- /usr/bin/id -u) = 0'
run_image_contract sudo_pacman_allowed /usr/bin/su -l triton -s /usr/bin/fish -c 'sudo -n /usr/bin/pacman -Q pacman'
run_image_contract sudo_pacman_database_check /usr/bin/su -l triton -s /usr/bin/fish -c 'sudo -n /usr/bin/pacman -Dk'
run_image_contract sudo_unrestricted_root_denied /usr/bin/su -l triton -s /usr/bin/fish -c 'not sudo -n /usr/bin/id -u >/dev/null 2>&1'
run_image_contract fish_abbreviation /usr/bin/su -l triton -s /usr/bin/fish -c 'fish -ic "abbr -q up"'
run_image_contract fish_starship_prompt_function /usr/bin/su -l triton -s /usr/bin/fish -c 'fish -ic "functions fish_prompt | string match -q \"*starship*\""'
printf 'TRITONTERM_NATIVE_ARM64_USERLAND_SMOKE_PASSED\n' | tee -a "$report"
rm -f "$ROOTFS/tmp/tritonterm-starship-prompt-benchmark.fish"
cp "$ROOTFS/var/cache/tritonterm/developer-setup.log" "$OUTPUT_DIR/build-log-${VERSION}.txt"

stage='repeated_pacman_database_integrity'
chroot_guest /usr/bin/pacman -Dk
printf 'TRITONTERM_REPEATED_PACMAN_DATABASE_PASSED\n' | tee -a "$report"

stage='native_pr_cli_login_integration'
./scripts/test-native-pr-cli-login.sh "$ROOTFS" "$OUTPUT_DIR"
printf 'TRITONTERM_NATIVE_PR_CLI_LOGIN_PASSED\n' | tee -a "$report"

# The setup log above is a CI build artifact, not device history. Keep it in
# OUTPUT_DIR for review, but never bake it into the distributable rootfs where
# a later image build could append to stale CI output.
rm -f "$ROOTFS/var/cache/tritonterm/developer-setup.log"
rm -f "$ROOTFS/tmp/tritonterm-starship-prompt-benchmark.fish"

stage='remove_machine_specific_state'
chroot_guest /usr/bin/usermod --password '!' root
chroot_guest /usr/bin/usermod --password '!' triton
# pacman-key starts a persistent gpg-agent inside the chroot. Stop it before
# unmounting so its chroot root cannot keep the temporary rootfs busy.
chroot_guest /usr/bin/gpgconf --homedir /etc/pacman.d/gnupg --kill all
active_password_hashes="$(awk -F: '$2 !~ /^(!|\*)/ { print $1 }' "$ROOTFS/etc/shadow")"
[[ -z "$active_password_hashes" ]] || fail 4 "image contains accounts with active password hashes: $active_password_hashes"
# Account-management tools retain previous databases in *- files. The base
# Arch Linux ARM image's UID-1000 account can have a usable password hash in
# shadow-, even after the live account is locked. Never ship
# these snapshots so the image cannot retain a recoverable template credential.
rm -f "$ROOTFS/etc/passwd-" "$ROOTFS/etc/group-" "$ROOTFS/etc/shadow-" "$ROOTFS/etc/gshadow-"
unmount_guest_filesystems
rm -f "$ROOTFS/etc/resolv.conf"
if [[ "$rootfs_resolv_existed" == true ]]; then
    cp -a "$ROOTFS_RESOLV_BACKUP" "$ROOTFS/etc/resolv.conf"
fi
rm -f "$ROOTFS/etc/machine-id" "$ROOTFS/var/lib/dbus/machine-id" "$ROOTFS/root/.bash_history" "$ROOTFS/home/triton/.bash_history"
rm -f "$ROOTFS"/etc/ssh/ssh_host_* 2>/dev/null || true
rm -rf "$ROOTFS/root/.ssh" "$ROOTFS/home/triton/.ssh"
rm -rf "$ROOTFS/root/.gnupg" "$ROOTFS/home/triton/.gnupg"
# pacman-key --init creates a local secret key. Never ship the builder's identity;
# first installation initializes and populates a device-local keyring instead.
rm -rf "$ROOTFS/etc/pacman.d/gnupg"
rm -f "$ROOTFS/root/.tritonterm-pacman-keyring-ready"
rm -rf "$ROOTFS/home/triton/rustup-smoke" "$ROOTFS/home/triton/.cache/tritonterm/paru-2.2.0"
find "$ROOTFS/var/cache/pacman/pkg" -mindepth 1 -maxdepth 1 -type f -delete 2>/dev/null || true

stage='rootfs_archive_creation'
stage_started="$(date +%s)"
# Keep the archive dense: pr-cli's Rust tar reader does not reconstruct GNU
# sparse PAX entries (it sees GNUSparseFile.* placeholder paths and the sparse
# map payload). Gzip already compresses runs of zero bytes, so --sparse saves
# little download space while making the signed image unusable in TritonTerm.
tar --numeric-owner --xattrs --xattrs-include='*' --acls \
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

stage='package_license_inventory'
python3 scripts/generate-rootfs-package-inventory.py \
    "$OUTPUT_ARCHIVE" "$OUTPUT_DIR/package-licenses-${VERSION}.csv"

stage='sudo_archive_metadata'
tar --numeric-owner -tvf "$OUTPUT_ARCHIVE" ./etc/sudo.conf ./usr/bin/sudo \
    > "$OUTPUT_DIR/sudo-metadata-${VERSION}.txt"
awk '
    $NF == "./etc/sudo.conf" { if ($1 != "-rw-r--r--" || $2 != "0/0") exit 1; conf=1 }
    $NF == "./usr/bin/sudo" { if ($1 != "-rwsr-xr-x" || $2 != "0/0") exit 1; sudo=1 }
    END { if (!conf || !sudo) exit 1 }
' "$OUTPUT_DIR/sudo-metadata-${VERSION}.txt" ||
    fail 6 'sudo archive metadata must retain root:root ownership, 0644 sudo.conf, and setuid-root 4755 sudo'
report_line 'TRITONTERM_SUDO_ARCHIVE_METADATA_PASSED'

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
   -x "$verify_dir/usr/bin/rustup" && -d "$verify_dir/home/triton/.rustup/toolchains" &&
   ! -e "$verify_dir/var/cache/tritonterm/developer-setup.log" ]] ||
    fail 6 're-extracted image did not pass required filesystem checks'
[[ ! -e "$verify_dir/etc/pacman.d/gnupg" && ! -e "$verify_dir/root/.tritonterm-pacman-keyring-ready" ]] ||
    fail 6 're-extracted image contains the build host pacman keyring'
[[ -z "$(awk -F: '$2 !~ /^(!|\*)/ { print $1 }' "$verify_dir/etc/shadow")" ]] ||
    fail 6 're-extracted image contains an active password hash'
for account_backup in passwd- group- shadow- gshadow-; do
    [[ ! -e "$verify_dir/etc/$account_backup" ]] ||
        fail 6 "re-extracted image contains a stale account database backup: /etc/$account_backup"
done
report_line "archive_extraction_benchmark_seconds=$(( $(date +%s) - stage_started ))"
report_line "total_build_seconds=$(( $(date +%s) - build_started ))"
report_line "completed_utc=$(date -u +%FT%TZ)"
printf '%s\n' 'TRITONTERM_ARCH_DEV_ROOTFS_BUILD_PASSED' | tee -a "$report"
build_succeeded=1
rm -rf "$work_dir"
