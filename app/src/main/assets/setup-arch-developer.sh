#!/bin/bash
# Fresh TritonTerm Arch developer environment. Invoked as root inside PRoot.
set -Eeuo pipefail

readonly VERSION='4'
readonly FAKEROOT_VERSION='2.1.4'
readonly FAKEROOT_URL='https://deb.debian.org/debian/pool/main/f/fakeroot/fakeroot_2.1.4.orig.tar.xz'
readonly FAKEROOT_SHA256='0822bd5a9f0cf19d2ba0546b88b0432d4d3d9917db62c57b74044ccadba06e49'
readonly CACHE="/var/cache/tritonterm/fakeroot-tcp/${FAKEROOT_VERSION}"
readonly FAKEROOT_ARCHIVE="${CACHE}/fakeroot_${FAKEROOT_VERSION}.orig.tar.xz"
readonly FAKEROOT_LOG="${CACHE}/build-${FAKEROOT_VERSION}.log"
readonly FAKEROOT_PARENT='/opt/tritonterm/fakeroot-tcp'
readonly FAKEROOT_PREFIX="${FAKEROOT_PARENT}/${FAKEROOT_VERSION}"
readonly FAKEROOT_CURRENT="${FAKEROOT_PARENT}/current"
readonly ASSET_DIR='/root/.tritonterm-setup'
readonly DEV_USER='dev'
readonly READY_MARKER='/root/.tritonterm-developer-environment-ready'
readonly RUN_LOG='/var/cache/tritonterm/developer-setup.log'
readonly PACMAN_LOCK='/var/lib/pacman/db.lck'
readonly ACTIVE_PACMAN='/var/cache/tritonterm/pacman-transaction'
readonly LEGACY_UPDATE_EVIDENCE='Synchronizing and updating the fresh Arch rootfs before installing development packages.'

stage='startup'
say() { printf '\n[Arch setup][%s] %s\n' "$stage" "$*"; }
die() {
    local code="$1"
    shift
    printf '\n[Arch setup] FAILED at %s (exit %s): %s\n' "$stage" "$code" "$*" >&2
    exit "$code"
}
on_error() {
    local code=$?
    printf '\n[Arch setup] FAILED at %s (exit %s): command: %s\n' "$stage" "$code" "$BASH_COMMAND" >&2
    exit "$code"
}
trap on_error ERR

[[ "$(id -u)" == 0 ]] || die 10 'must run as root in the Arch PRoot guest'
[[ "$(uname -m)" == aarch64 ]] || die 11 'expected an AArch64 guest'
[[ -s "${ASSET_DIR}/starship.toml" && -s "${ASSET_DIR}/paru-bootstrap.PKGBUILD" ]] ||
    die 12 "bundled setup inputs are missing from ${ASSET_DIR}"

install -d -m 0755 /var/cache/tritonterm "$CACHE" "$FAKEROOT_PARENT"
rm -f "$READY_MARKER"
exec 3>&2
exec > >(tee -a "$RUN_LOG") 2> >(sed 's/^/[stderr] /' | tee -a "$RUN_LOG" >&3)
printf '[Arch setup] version=%s started=%s\n' "$VERSION" "$(date -Is)"

# PRoot does not boot this guest's systemd. Package hooks that use systemctl should
# use systemd's supported offline behavior instead of contacting Android's PID 1.
export SYSTEMD_OFFLINE=1

recover_interrupted_pacman() {
    if [[ ! -e "$PACMAN_LOCK" ]]; then
        rm -f "$ACTIVE_PACMAN"
        return 0
    fi
    if pgrep -x pacman >/dev/null 2>&1; then
        die 26 'pacman is still running and owns the database lock; no recovery action was taken'
    fi
    local owned_transaction=false
    if grep -qx 'owner=tritonterm' "$ACTIVE_PACMAN" 2>/dev/null; then
        owned_transaction=true
    elif [[ -f "$RUN_LOG" && ! -L "$RUN_LOG" ]] && grep -Fq "$LEGACY_UPDATE_EVIDENCE" "$RUN_LOG"; then
        # Compatibility for the first developer-setup version, which did not yet
        # write a per-transaction marker before starting its initial pacman upgrade.
        owned_transaction=true
    fi
    if [[ -L "$PACMAN_LOCK" || ! -f "$PACMAN_LOCK" || "$owned_transaction" != true ]]; then
        die 27 'a pacman lock exists without a confirmed interrupted TritonTerm transaction; the lock was preserved'
    fi
    say "Found a stale lock from TritonTerm's interrupted transaction; no pacman process is present. Removing only that stale lock, then retrying the full transaction."
    rm -f -- "$PACMAN_LOCK" "$ACTIVE_PACMAN"
}

run_pacman_transaction() {
    local label="$1"
    shift
    recover_interrupted_pacman
    {
        printf 'owner=tritonterm\n'
        printf 'stage=%s\n' "$stage"
        printf 'command=%s\n' "$label"
        printf 'started=%s\n' "$(date -Is)"
    } > "$ACTIVE_PACMAN"

    say "Running package transaction: ${label}"
    "$@" &
    local child_pid=$!
    local started_seconds=$SECONDS
    local last_heartbeat_seconds=$SECONDS
    while kill -0 "$child_pid" 2>/dev/null; do
        sleep 1
        local child_state
        child_state="$(ps -o stat= -p "$child_pid" 2>/dev/null | tr -d '[:space:]' || true)"
        [[ "$child_state" == Z* ]] && break
        if (( SECONDS - last_heartbeat_seconds >= 30 )) && kill -0 "$child_pid" 2>/dev/null; then
            local pacman_pids pacman_state
            pacman_pids="$(pgrep -x pacman 2>/dev/null | tr '\n' ',' | sed 's/,$//' || true)"
            pacman_state='no-pacman-pid-visible'
            if [[ -n "$pacman_pids" ]]; then
                pacman_state="$(ps -o pid=,stat=,etime=,wchan=,comm= -p "$pacman_pids" 2>/dev/null | tr '\n' ';' || true)"
            fi
            say "command_still_running label=${label} pid=${child_pid} elapsed_seconds=$((SECONDS - started_seconds)) process_state=${child_state:-unknown} pacman_pids=${pacman_pids:-none} pacman_state=${pacman_state} database_lock=$([[ -e "$PACMAN_LOCK" ]] && printf present || printf absent); pacman has not been terminated"
            last_heartbeat_seconds=$SECONDS
        fi
    done

    local result=0
    wait "$child_pid" || result=$?
    if (( result == 0 )) || [[ ! -e "$PACMAN_LOCK" ]]; then
        rm -f "$ACTIVE_PACMAN"
    fi
    return "$result"
}

require_pacman_transaction() {
    local label="$1"
    shift
    local result=0
    run_pacman_transaction "$label" "$@" || result=$?
    (( result == 0 )) || die "$result" "package transaction failed: ${label}"
}

stage='package_database_update'
say 'Synchronizing and updating the fresh Arch rootfs before installing development packages.'
require_pacman_transaction 'pacman -Syu --needed --noconfirm' pacman -Syu --needed --noconfirm

stage='development_packages'
say 'Installing build tools, fish, Starship, rustup, and narrowly scoped sudo through pacman.'
require_pacman_transaction 'install developer packages' pacman -S --needed --noconfirm \
    base-devel autoconf automake libtool curl ca-certificates git fish starship rustup sudo fakeroot strace
for tool in gcc make autoreconf libtoolize curl sha256sum rustup fish starship sudo makepkg bsdtar strace; do
    command -v "$tool" >/dev/null || die 22 "required command is missing after package installation: ${tool}"
done
standalone_rust_packages="$(pacman -Qq | awk '$0 == "rust" || $0 == "cargo" || $0 == "rustfmt"')"
if [[ -n "$standalone_rust_packages" ]]; then
    die 23 "standalone Rust packages are installed alongside rustup: ${standalone_rust_packages//$'\n'/, }; remove those exact packages before selecting rustup"
fi

stage='development_user'
if ! getent passwd "$DEV_USER" >/dev/null; then
    uid_1000_entry="$(getent passwd 1000 || true)"
    gid_1000_entry="$(getent group 1000 || true)"
    if [[ -z "$uid_1000_entry" && -z "$gid_1000_entry" ]]; then
        say 'Creating the unprivileged template account.'
        useradd --create-home --uid 1000 --user-group --groups wheel --shell /usr/bin/fish "$DEV_USER"
    else
        # The official Arch Linux ARM base image reserves UID/GID 1000 for
        # its default `alarm` login. Reuse that known template identity on this
        # fresh image, preserving its numeric ownership and home contents.
        IFS=: read -r uid_1000_name _ uid_1000_value uid_1000_gid _ uid_1000_home uid_1000_shell <<< "$uid_1000_entry"
        IFS=: read -r gid_1000_name _ gid_1000_value gid_1000_members <<< "$gid_1000_entry"
        if [[ "$uid_1000_name" != alarm || "$uid_1000_value" != 1000 || "$uid_1000_gid" != 1000 ||
              "$uid_1000_home" != /home/alarm || "$uid_1000_shell" != /bin/bash ||
              "$gid_1000_name" != alarm || "$gid_1000_value" != 1000 ]]; then
            die 24 "UID/GID 1000 is occupied by an unexpected base-image identity (user=${uid_1000_name:-none}, group=${gid_1000_name:-none}); refusing to repurpose it"
        fi
        getent passwd "$DEV_USER" >/dev/null && die 24 'the template login name dev already exists with an unexpected UID'
        getent group "$DEV_USER" >/dev/null && die 24 'the template group name dev already exists with an unexpected GID'
        if [[ -e /home/dev ]]; then
            die 24 'cannot rename the base alarm account because /home/dev already exists'
        fi
        say 'Renaming the verified Arch Linux ARM alarm template account to dev while preserving UID/GID 1000 and its home files.'
        usermod --login "$DEV_USER" alarm
        groupmod --new-name "$DEV_USER" alarm
        usermod --home /home/dev --move-home "$DEV_USER"
    fi
fi
IFS=: read -r _ _ dev_uid dev_gid _ dev_home _ < <(getent passwd "$DEV_USER")
[[ "${dev_uid:-}" == 1000 && "${dev_gid:-}" == 1000 && -n "${dev_home:-}" && "$dev_home" == /home/dev ]] ||
    die 24 'template dev must own UID/GID 1000 and /home/dev'
dev_group="$(id -gn "$DEV_USER")"
install -d -o "$DEV_USER" -g "$dev_group" -m 0750 "$dev_home"
install -d -o "$DEV_USER" -g "$dev_group" -m 0750 \
    "$dev_home/.local" "$dev_home/.local/bin" \
    "$dev_home/.config" "$dev_home/.config/fish/conf.d" \
    "$dev_home/.config/paru" "$dev_home/.config/pacman" \
    "$dev_home/.cache/fish" "$dev_home/.cache/tritonterm" "$dev_home/.cargo" "$dev_home/.rustup"
# The Arch Linux ARM base login already owns UID 1000, but its inherited dot
# directories may have restrictive modes or unexpected group ownership. Make
# the preserved template home writable by its renamed owner before starting
# Fish or rustup; this changes no file's numeric UID.
chown -R "$DEV_USER:$dev_group" "$dev_home"
chmod u+rwx "$dev_home" "$dev_home/.config" "$dev_home/.config/fish" \
    "$dev_home/.cache" "$dev_home/.cache/fish"
usermod --shell /usr/bin/fish "$DEV_USER"
usermod --append --groups wheel "$DEV_USER"

stage='restricted_package_authorization'
sudoers='/etc/sudoers.d/10-tritonterm-pacman'
sudoers_line='%wheel ALL=(root) NOPASSWD: /usr/bin/pacman'
install -d -m 0750 /etc/sudoers.d
printf '%s\n' "$sudoers_line" > "${sudoers}.tritonterm-new"
chmod 0440 "${sudoers}.tritonterm-new"
mv -f "${sudoers}.tritonterm-new" "$sudoers"
if ! visudo -cf /etc/sudoers; then
    die 25 'sudoers validation failed; only pacman is authorized for members of wheel'
fi

stage='persistent_shell_configuration'
cat > /etc/profile.d/tritonterm-devtools.sh <<'PROFILE'
case ":${PATH:-}:" in
    *":/opt/tritonterm/fakeroot-tcp/current/bin:"*) ;;
    *) export PATH="/opt/tritonterm/fakeroot-tcp/current/bin:${PATH:-/usr/local/sbin:/usr/local/bin:/usr/bin:/bin}" ;;
esac
case ":${PATH:-}:" in
    *":${HOME}/.cargo/bin:"*) ;;
    *) export PATH="${HOME}/.cargo/bin:${PATH:-/usr/local/sbin:/usr/local/bin:/usr/bin:/bin}" ;;
esac
case ":${PATH:-}:" in
    *":${HOME}/.local/bin:"*) ;;
    *) export PATH="${HOME}/.local/bin:${PATH}" ;;
esac
export SYSTEMD_OFFLINE=1
PROFILE
chmod 0644 /etc/profile.d/tritonterm-devtools.sh
cat > "$dev_home/.config/fish/conf.d/20-tritonterm-path.fish" <<'FISH_PATH'
fish_add_path --prepend "/opt/tritonterm/fakeroot-tcp/current/bin" "$HOME/.cargo/bin" "$HOME/.local/bin"
set -gx SYSTEMD_OFFLINE 1
FISH_PATH
cat > "$dev_home/.config/fish/conf.d/90-tritonterm-starship.fish" <<'FISH_PROMPT'
if status is-interactive
    starship init fish | source
end
FISH_PROMPT
install -o "$DEV_USER" -g "$dev_group" -m 0644 \
    "${ASSET_DIR}/starship.toml" "$dev_home/.config/starship.toml"
cat > "$dev_home/.config/pacman/makepkg.conf" <<'MAKEPKG_CONF'
# TritonTerm: use PRoot-compatible, pacman-only authorization for package installs.
PACMAN_AUTH=(/usr/bin/sudo -n)
MAKEPKG_CONF
cat > "$dev_home/.config/paru/paru.conf" <<'PARU_CONF'
[bin]
Makepkg = /usr/bin/makepkg
Pacman = /usr/bin/pacman
Sudo = /usr/bin/sudo
PARU_CONF
chmod 0644 "$dev_home/.config/fish/conf.d/20-tritonterm-path.fish" \
    "$dev_home/.config/fish/conf.d/90-tritonterm-starship.fish" \
    "$dev_home/.config/pacman/makepkg.conf" "$dev_home/.config/paru/paru.conf"

run_dev_fish() { su -l "$DEV_USER" -s /usr/bin/fish -c "$1"; }

stage='rustup_stable_toolchain'
say 'Installing the official stable AArch64 GNU toolchain as dev with Arch rustup.'
run_dev_fish 'rustup default stable'
run_dev_fish 'rustup show'
run_dev_fish 'rustup show active-toolchain | string match -q "stable-aarch64-unknown-linux-gnu*"'
run_dev_fish 'rustc --version'
run_dev_fish 'cargo --version'

stage='tcp_fakeroot_source'
if [[ ! -f "$FAKEROOT_ARCHIVE" ]] ||
    [[ "$(sha256sum "$FAKEROOT_ARCHIVE" | awk '{print $1}')" != "$FAKEROOT_SHA256" ]]; then
    if [[ -e "$FAKEROOT_ARCHIVE" ]]; then
        mv "$FAKEROOT_ARCHIVE" "${FAKEROOT_ARCHIVE}.invalid.$(date +%s).$$"
    fi
    part="${FAKEROOT_ARCHIVE}.part.$$"
    say "Downloading upstream fakeroot ${FAKEROOT_VERSION}."
    curl --fail --location --retry 3 --output "$part" "$FAKEROOT_URL"
    printf '%s  %s\n' "$FAKEROOT_SHA256" "$part" | sha256sum --check --status
    mv "$part" "$FAKEROOT_ARCHIVE"
fi
printf '%s  %s\n' "$FAKEROOT_SHA256" "$FAKEROOT_ARCHIVE" | sha256sum --check

stage='tcp_fakeroot_build'
if [[ -e "$FAKEROOT_CURRENT" && ! -L "$FAKEROOT_CURRENT" ]]; then
    die 26 "reserved fakeroot selector is not a symlink: ${FAKEROOT_CURRENT}"
fi
if [[ ! -x "${FAKEROOT_PREFIX}/bin/fakeroot" || ! -x "${FAKEROOT_PREFIX}/bin/faked" ||
      ! -f "${FAKEROOT_PREFIX}/lib/libfakeroot-0.so" ||
      ! -f "${FAKEROOT_PREFIX}/.tritonterm-version" ]]; then
    if [[ -e "$FAKEROOT_PREFIX" || -L "$FAKEROOT_PREFIX" ]]; then
        mv "$FAKEROOT_PREFIX" "${FAKEROOT_PREFIX}.incomplete.$(date +%s).$$"
    fi
    build="${FAKEROOT_PARENT}/.staging-${FAKEROOT_VERSION}-$$"
    install -d -m 0755 "$build/source" "$build/dest"
    tar -xJf "$FAKEROOT_ARCHIVE" -C "$build/source" --strip-components=1
    (
        cd "$build/source"
        if ! autoreconf -fi > "$FAKEROOT_LOG" 2>&1; then
            tail -n 80 "$FAKEROOT_LOG" >&2
            die 27 "autoreconf failed; full log: ${FAKEROOT_LOG}"
        fi
        if ! ./configure --prefix="$FAKEROOT_PREFIX" --with-ipc=tcp >> "$FAKEROOT_LOG" 2>&1; then
            tail -n 80 "$FAKEROOT_LOG" >&2
            die 28 "configure failed; full log: ${FAKEROOT_LOG}"
        fi
        if ! make -j2 >> "$FAKEROOT_LOG" 2>&1; then
            tail -n 80 "$FAKEROOT_LOG" >&2
            die 29 "compile failed; full log: ${FAKEROOT_LOG}"
        fi
        if ! make DESTDIR="$build/dest" install >> "$FAKEROOT_LOG" 2>&1; then
            tail -n 80 "$FAKEROOT_LOG" >&2
            die 30 "staged install failed; full log: ${FAKEROOT_LOG}"
        fi
    )
    staged="${build}/dest${FAKEROOT_PREFIX}"
    if [[ ! -x "${staged}/bin/fakeroot" || ! -x "${staged}/bin/faked" ||
          ! -f "${staged}/lib/libfakeroot-0.so" ]]; then
        say 'Staged TCP fakeroot files did not match the expected prefix layout; found these relevant paths:' >&2
        find "$build/dest" \( -name fakeroot -o -name faked -o -name 'libfakeroot*' \) \
            -printf '%M %u:%g %p -> %l\n' >&2 || true
        die 31 'staged fakeroot build is missing its wrapper, daemon, or preload library'
    fi
    printf '%s\n' "$FAKEROOT_VERSION" > "${staged}/.tritonterm-version"
    mv "$staged" "$FAKEROOT_PREFIX"
    rm -rf "$build"
fi
if [[ -L "$FAKEROOT_CURRENT" ]]; then rm "$FAKEROOT_CURRENT"; fi
ln -s "$FAKEROOT_VERSION" "$FAKEROOT_CURRENT"
rm -f "$dev_home/.local/bin/fakeroot"
fake_uid="$(run_dev_fish 'fakeroot -- /usr/bin/id -u')"
fake_gid="$(run_dev_fish 'fakeroot -- /usr/bin/id -g')"
[[ "$fake_uid" == 0 && "$fake_gid" == 0 ]] ||
    die 31 "TCP fakeroot identity smoke failed (reported uid=${fake_uid:-empty}, gid=${fake_gid:-empty})"
say 'TCP fakeroot identity smoke passed: fake uid=0 gid=0.'
fake_file="/tmp/tritonterm-fakeroot-file-smoke-$$"
fake_owner="$(run_dev_fish "fakeroot -- /bin/sh -c 'touch \"$fake_file\"; chown 0:0 \"$fake_file\"; /usr/bin/stat -c %u:%g \"$fake_file\"'")"
[[ "$fake_owner" == 0:0 ]] ||
    die 31 "TCP fakeroot file metadata smoke failed (reported owner=${fake_owner:-empty})"
rm -f "$fake_file"
say 'TCP fakeroot file metadata smoke passed: fake owner=0:0.'

stage='makepkg_install_smoke'
say 'Running makepkg -si as dev to validate TCP fakeroot and restricted pacman authorization.'
smoke="/tmp/tritonterm-package-smoke-$$"
install -d -o "$DEV_USER" -g "$dev_group" -m 0750 "$smoke" "$smoke/pkgdest"
makepkg_connect_trace="$smoke/makepkg-connect.trace"
selected_fakeroot="$(run_dev_fish 'command -v fakeroot')"
say "makepkg PATH selects ${selected_fakeroot}."
archive_probe="$smoke/archive-probe"
fake_archive="$smoke/fakeroot-archive-smoke.tar.zst"
install -d -o "$DEV_USER" -g "$dev_group" -m 0750 "$archive_probe"
printf 'TritonTerm fakeroot archive probe\n' > "$archive_probe/payload"
chown -R "$DEV_USER:$dev_group" "$archive_probe"
run_dev_fish "fakeroot -- /bin/sh -c 'chown -R 0:0 \"$archive_probe\" && /usr/bin/bsdtar --numeric-owner --zstd -cpf \"$fake_archive\" -C \"$archive_probe\" .'"
bsdtar --numeric-owner -tvf "$fake_archive" | awk '
    { print > "/dev/stderr" }
    $NF ~ /(^|\/)payload$/ && (($2 == "0" && $3 == "0") ||
                                ($2 == "0" && $3 == "root" && $4 == "root") ||
                                ($2 == "root" && $3 == "root") || $2 == "0/0") { found=1 }
    END { exit !found }
' || die 32 'TCP fakeroot bsdtar archive probe did not record root:root ownership'
say 'TCP fakeroot zstd-compressed bsdtar archive probe passed: root:root ownership preserved.'
nested_fakeroot_version="$(run_dev_fish 'fakeroot -- /bin/sh -c "fakeroot -v"')"
say "Nested TCP fakeroot version query passed: ${nested_fakeroot_version}"
run_dev_fish 'fakeroot -- /usr/bin/pacman -Qi pacman >/dev/null'
say 'TCP fakeroot package metadata query passed.'
for compressor in xz zstd; do
    compressor_trace="$smoke/${compressor}-fakeroot.trace"
    if ! run_dev_fish \
        "strace -f -e trace=process,network -o '$compressor_trace' fakeroot -- /bin/sh -c 'set -o pipefail; printf smoke | /usr/bin/$compressor -c >/dev/null'"; then
        say "The ${compressor} subprocess failed inside TCP fakeroot; process/network trace follows. Full trace: ${compressor_trace}"
        if [[ -f "$compressor_trace" ]]; then
            awk '/execve\(.*(fakeroot|faked|xz|zstd)|socket\(AF_INET|bind\(|listen\(|accept\(|connect\(.*= -1/ { print }' \
                "$compressor_trace" | tail -n 80 >&2
        fi
        die 32 "TCP fakeroot ${compressor} subprocess smoke failed"
    fi
    say "TCP fakeroot ${compressor} subprocess smoke passed."
done
cat > "$smoke/PKGBUILD" <<'SMOKE_PKGBUILD'
pkgname=tritonterm-default-smoke
pkgver=1
pkgrel=1
pkgdesc='TritonTerm fresh provisioning smoke test'
arch=('aarch64')
license=('MIT')
package() {
    install -d "$pkgdir/usr/share/tritonterm-default-smoke"
    printf 'TritonTerm\n' > "$pkgdir/usr/share/tritonterm-default-smoke/installed"
    chmod 4755 "$pkgdir/usr/share/tritonterm-default-smoke/installed"
}
SMOKE_PKGBUILD
chown "$DEV_USER:$dev_group" "$smoke/PKGBUILD"
makepkg_result=0
run_pacman_transaction 'makepkg smoke package install' run_dev_fish \
    "cd '$smoke' && env PKGDEST='$smoke/pkgdest' strace -f -e trace=process,network -o '$makepkg_connect_trace' makepkg --noconfirm --syncdeps --install --force" || makepkg_result=$?
if (( makepkg_result != 0 )); then
    say "makepkg failed with exit ${makepkg_result}; fakeroot process and network lifecycle follows. Full trace: ${makepkg_connect_trace}"
    if [[ -f "$makepkg_connect_trace" ]]; then
        awk '/execve\(|clone\(|fork\(|vfork\(|bind\(|listen\(|accept\(|connect\(/ && (/execve\(|clone\(|fork\(|vfork\(|bind\(|listen\(|accept\(/ || /connect\(.*= -1/)' \
            "$makepkg_connect_trace" | tail -n 120 >&2
    fi
    die "$makepkg_result" 'package transaction failed: makepkg smoke package install'
fi
test -f /usr/share/tritonterm-default-smoke/installed
archive="$(find "$smoke/pkgdest" -maxdepth 1 -type f -name '*.pkg.tar.*' -print -quit)"
[[ -n "$archive" ]] || die 32 'makepkg did not produce a package archive'
bsdtar -tvf "$archive" | awk '
    $NF == "usr/share/tritonterm-default-smoke/installed" {
        mode = $1
        owner_ok = ($2 == "0" && $3 == "root" && $4 == "root") ||
                   ($2 == "root" && $3 == "root") || $2 == "0/0"
        if (mode ~ /^-rws/ && owner_ok) found = 1
    }
    END { exit !found }
' || die 33 'makepkg archive did not preserve root:root ownership and mode 4755'
require_pacman_transaction 'remove makepkg smoke package' pacman -Rns --noconfirm tritonterm-default-smoke
rm -rf "$smoke"

stage='paru_source_build'
say 'Building Paru from its pinned upstream v2.2.0 source as dev.'
paru_build="$dev_home/.cache/tritonterm/paru-2.2.0"
install -d -o "$DEV_USER" -g "$dev_group" -m 0750 "$paru_build"
install -o "$DEV_USER" -g "$dev_group" -m 0644 \
    "${ASSET_DIR}/paru-bootstrap.PKGBUILD" "$paru_build/PKGBUILD"
run_dev_fish "cd '$paru_build' && makepkg --noconfirm --syncdeps --force"
paru_package="$(find "$paru_build" -maxdepth 1 -type f -name 'paru-2.2.0-*.pkg.tar.*' -print -quit)"
[[ -n "$paru_package" ]] || die 34 'the pinned Paru PKGBUILD produced no package archive'
require_pacman_transaction 'install Paru package' pacman -U --noconfirm "$paru_package"
pacman -Qo /usr/bin/paru

stage='paru_install_smoke'
say 'Verifying the normal paru command with a local, trusted smoke PKGBUILD.'
run_dev_fish 'paru --version'
paru_smoke="/tmp/tritonterm-paru-smoke-$$"
install -d -o "$DEV_USER" -g "$dev_group" -m 0750 "$paru_smoke" "$paru_smoke/pkgdest"
cat > "$paru_smoke/PKGBUILD" <<'PARU_SMOKE_PKGBUILD'
pkgname=tritonterm-paru-default-smoke
pkgver=1
pkgrel=1
pkgdesc='TritonTerm Paru provisioning smoke test'
arch=('aarch64')
license=('MIT')
package() {
    install -d "$pkgdir/usr/share/tritonterm-paru-default-smoke"
    printf 'TritonTerm\n' > "$pkgdir/usr/share/tritonterm-paru-default-smoke/installed"
    chmod 4755 "$pkgdir/usr/share/tritonterm-paru-default-smoke/installed"
}
PARU_SMOKE_PKGBUILD
chown "$DEV_USER:$dev_group" "$paru_smoke/PKGBUILD"
require_pacman_transaction 'Paru smoke package install' run_dev_fish "cd '$paru_smoke' && env PKGDEST='$paru_smoke/pkgdest' paru --noconfirm --skipreview -Bi ."
test -f /usr/share/tritonterm-paru-default-smoke/installed
paru_archive="$(find "$paru_smoke/pkgdest" -maxdepth 1 -type f -name '*.pkg.tar.*' -print -quit)"
[[ -n "$paru_archive" ]] || die 35 'Paru did not produce a package archive in the fresh PKGDEST'
bsdtar -tvf "$paru_archive" | awk '
    $NF == "usr/share/tritonterm-paru-default-smoke/installed" {
        mode = $1
        owner_ok = ($2 == "0" && $3 == "root" && $4 == "root") ||
                   ($2 == "root" && $3 == "root") || $2 == "0/0"
        if (mode ~ /^-rws/ && owner_ok) found = 1
    }
    END { exit !found }
' || die 36 'Paru archive did not preserve root:root ownership and mode 4755'
require_pacman_transaction 'remove Paru smoke package' pacman -Rns --noconfirm tritonterm-paru-default-smoke
rm -rf "$paru_smoke"

stage='rust_compilation_smoke'
say 'Compiling and executing a dependency-free Rust program from fresh login shells.'
rust_smoke="$dev_home/rustup-smoke"
install -d -o "$DEV_USER" -g "$dev_group" -m 0750 "$rust_smoke/src"
cat > "$rust_smoke/Cargo.toml" <<'CARGO_TOML'
[package]
name = "tritonterm-rustup-smoke"
version = "0.1.0"
edition = "2021"
CARGO_TOML
cat > "$rust_smoke/src/main.rs" <<'RUST_MAIN'
fn main() { println!("TRITONTERM_RUSTUP_STABLE_BUILD_OK"); }
RUST_MAIN
chown -R "$DEV_USER:$dev_group" "$rust_smoke"
run_dev_fish "cd '$rust_smoke' && cargo build --offline && ./target/debug/tritonterm-rustup-smoke"
run_dev_fish 'rustup show'
run_dev_fish 'rustc --version'
run_dev_fish 'cargo --version'
run_dev_fish 'fish -ic "functions fish_prompt | string match -q \"*starship*\""'
run_dev_fish 'env STARSHIP_CONFIG="$HOME/.config/starship.toml" starship prompt >/dev/null'
run_dev_fish 'bash -lc "rustup show active-toolchain && rustc --version && cargo --version"'

stage='final_validation'
pacman -Dk
pacman -Qo /usr/bin/fakeroot
pacman -Qo /usr/bin/rustup
pacman -Qo /usr/bin/paru
[[ "$(getent passwd "$DEV_USER" | cut -d: -f7)" == /usr/bin/fish ]]
[[ "$(readlink "$FAKEROOT_CURRENT")" == "$FAKEROOT_VERSION" ]]
run_dev_fish 'string match -q "/opt/tritonterm/fakeroot-tcp/current/bin/fakeroot" (command -v fakeroot)'
[[ -x "$dev_home/.rustup/toolchains/stable-aarch64-unknown-linux-gnu/bin/rustc" ]]
[[ -x /usr/bin/paru && -x /usr/bin/fish && -x /usr/bin/starship ]]
printf '%s\n' "$VERSION" > "$READY_MARKER"
say 'Fresh Arch developer environment passed pacman, TCP fakeroot, makepkg, Paru, Fish/Starship, and Rustup checks.'
printf '%s\n' 'TRITONTERM_ARCH_DEVELOPER_ENVIRONMENT_READY'
