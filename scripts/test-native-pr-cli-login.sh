#!/usr/bin/env bash
# Exercise the same pr-cli login command and Fish smoke used by Android.
set -Eeuo pipefail
readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly PR_SOURCE="$PROJECT_ROOT/third_party/oonid-pr"
readonly ROOTFS="${1:-}"
readonly OUTPUT="${2:-}"
readonly EXPECTED_PR_COMMIT='31afc11ca3e675fa8bbbe530979afc8c467218dc'
stage='preflight'
fail() { local code="$1"; shift; printf '[native-pr-cli-login] FAILED at %s (exit %s): %s\n' "$stage" "$code" "$*" >&2; exit "$code"; }
on_error() { local code=$?; printf '[native-pr-cli-login] FAILED at %s (exit %s): command: %s\n' "$stage" "$code" "$BASH_COMMAND" >&2; exit "$code"; }
trap on_error ERR
[[ -d "$ROOTFS" && -s "$ROOTFS/etc/passwd" ]] || fail 2 'usage: test-native-pr-cli-login.sh ROOTFS OUTPUT'
[[ -n "$OUTPUT" ]] || fail 2 'an output directory is required'
[[ "$(uname -m)" == aarch64 ]] || fail 2 'this integration check requires native AArch64 execution'
[[ "$(git -C "$PR_SOURCE" rev-parse HEAD)" == "$EXPECTED_PR_COMMIT" ]] || fail 2 'PRoot checkout is not at the pinned commit'
for command in python3 timeout; do command -v "$command" >/dev/null || fail 2 "host command missing: $command"; done
if [[ -z "${TRITONTERM_NATIVE_PR_CLI_DIR:-}" ]]; then
    for command in make cargo rustup; do command -v "$command" >/dev/null || fail 2 "host command missing: $command"; done
fi
mkdir -p "$OUTPUT"
work="$(mktemp -d "${TMPDIR:-/tmp}/tritonterm-pr-cli-login.XXXXXX")"
trap 'rm -rf "$work"' EXIT
stage='build_native_proot'
if [[ -n "${TRITONTERM_NATIVE_PR_CLI_DIR:-}" ]]; then
    for binary in libproot.so libproot-loader.so libpr-cli.so; do
        [[ -x "$TRITONTERM_NATIVE_PR_CLI_DIR/$binary" ]] || fail 2 "prebuilt native test binary is missing: $binary"
    done
else
    make -C "$PR_SOURCE/src/proot/src" -j2 proot loader/loader
    stage='build_native_pr_cli'
    cargo +1.98.0 build --manifest-path "$PR_SOURCE/src/pr-cli/Cargo.toml" --release --locked
fi
stage='prepare_app_layout'
readonly PREFIX="$work/prefix"
readonly BIN="$work/native-bin"
readonly ROOTFS_LINK="$PREFIX/var/lib/pr/installed-rootfs/archlinuxarm"
mkdir -p "$BIN" "$PREFIX/bin" "$PREFIX/etc/pr" "$PREFIX/var/lib/pr/installed-rootfs" "$PREFIX/var/lib/pr/dlcache" "$PREFIX/tmp" "$work/home" "$work/cache/proot"
ln -s "$ROOTFS" "$ROOTFS_LINK"
cat > "$PREFIX/etc/pr/archlinuxarm.sh" <<'PLUGIN'
DISTRO_NAME="Arch Linux ARM"
TARBALL_URL_aarch64="https://invalid.example/not-used-by-login-test.tar.gz"
PLUGIN
if [[ -n "${TRITONTERM_NATIVE_PR_CLI_DIR:-}" ]]; then
    install -m 0755 "$TRITONTERM_NATIVE_PR_CLI_DIR/libproot.so" "$BIN/libproot.so"
    install -m 0755 "$TRITONTERM_NATIVE_PR_CLI_DIR/libproot-loader.so" "$BIN/libproot-loader.so"
    install -m 0755 "$TRITONTERM_NATIVE_PR_CLI_DIR/libpr-cli.so" "$BIN/libpr-cli.so"
else
    install -m 0755 "$PR_SOURCE/src/proot/src/proot" "$BIN/libproot.so"
    install -m 0755 "$PR_SOURCE/src/proot/src/loader/loader" "$BIN/libproot-loader.so"
    install -m 0755 "$PR_SOURCE/src/pr-cli/target/release/pr-cli" "$BIN/libpr-cli.so"
fi
ln -s "$BIN/libpr-cli.so" "$PREFIX/bin/pr-cli"
ln -s "$BIN/libproot.so" "$PREFIX/bin/proot"
stage='prepare_android_userland_metadata'
# Mirror the sidecars pr-cli writes on Android from the native rootfs inode metadata.
python3 - "$ROOTFS" "$ROOTFS_LINK.tritonterm-metadata" <<'PY'
import os
import stat
import sys
root, metadata_root = sys.argv[1:]
os.makedirs(metadata_root, mode=0o700, exist_ok=True)
def write_record(relative, info):
    if relative == ".":
        destination = os.path.join(metadata_root, ".proot-meta-file.root")
    else:
        parent, name = os.path.split(relative)
        destination = os.path.join(metadata_root, parent, ".proot-meta-file." + name)
    os.makedirs(os.path.dirname(destination), mode=0o700, exist_ok=True)
    temporary = destination + ".tmp." + str(os.getpid())
    with open(temporary, "w", encoding="ascii") as output:
        output.write(f"{stat.S_IMODE(info.st_mode):o}\n{info.st_uid}\n{info.st_gid}\n")
    os.chmod(temporary, 0o600)
    os.replace(temporary, destination)
write_record(".", os.lstat(root))
for directory, dirs, files in os.walk(root, topdown=True, followlinks=False):
    for name in list(dirs) + list(files):
        path = os.path.join(directory, name)
        write_record(os.path.relpath(path, root), os.lstat(path))
PY
stage='run_android_equivalent_login'
export APP_PREFIX="$PREFIX" APP_HOME="$work/home" APP_PACKAGE='com.tritonterm.android'
export HOME="$work/home" TMPDIR="$work/cache/proot" PROOT_TMP_DIR="$work/cache/proot"
export PROOT_NO_SECCOMP=1 TRITONTERM_PROOT_USERLAND_METADATA=1
export TRITONTERM_DIAGNOSTICS_FILE="$OUTPUT/native-pr-cli-diagnostics.jsonl"
export PROOT_CRASH_DIAGNOSTICS=1 PROOT_CRASH_DIAGNOSTICS_FILE="$OUTPUT/native-pr-cli-crash.log"
export PATH="$PREFIX/bin:/system/bin:/system/xbin" TERM=xterm-256color LANG=en_US.UTF-8
output="$OUTPUT/contract-native-pr-cli-login.txt"
args=(login archlinuxarm --user triton /usr/bin/fish -l -i -c 'source /etc/tritonterm/guest-smoke.fish')
printf 'Invocation: %q ' "$BIN/libpr-cli.so" "${args[@]}" > "$output"
printf '\n' >> "$output"
result=0
timeout 180 "$BIN/libpr-cli.so" "${args[@]}" >> "$output" 2>&1 || result=$?
cat "$output"
(( result == 0 )) || fail "$result" "pr-cli login exited unsuccessfully; output: $output"
! grep -q '^CHECK_FAILED ' "$output" || fail 4 "guest feature checks failed; output: $output"
grep -q '^TRITONTERM_GUEST_CHECKS_COMPLETED failures=0$' "$output" || fail 4 "Fish smoke did not report success; output: $output"
printf 'TRITONTERM_NATIVE_PR_CLI_LOGIN_PASSED\n'
