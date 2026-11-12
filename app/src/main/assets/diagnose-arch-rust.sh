#!/usr/bin/env bash
# Device-side Rust/PRoot reproducer. Pass the paru AUR source directory and,
# optionally, the rustup-init executable that previously crashed.
set -u

PARU_SOURCE="${1:-}"
RUSTUP_INIT="${2:-}"
CARGO_BIN=/usr/bin/cargo
RUSTC_BIN=/usr/bin/rustc
OUT="$(mktemp -d "${HOME}/tritonterm-rust-diagnostic.XXXXXX")" || exit 1
export PATH="/usr/bin:/bin:/usr/sbin:/sbin"
export RUSTC="$RUSTC_BIN"
export CARGO_HOME="${CARGO_HOME:-${HOME}/.cargo}"

run_case() {
    local label="$1"
    shift
    local status
    {
        printf 'CASE %s\nCOMMAND:' "$label"
        printf ' %q' "$@"
        printf '\n'
        if "$@" >"${OUT}/${label}.stdout" 2>"${OUT}/${label}.stderr"; then
            status=0
        else
            status=$?
        fi
        printf 'EXIT %s %s\n\n' "$label" "$status"
    } >>"${OUT}/summary.txt"
}

{
    printf 'created=%s\n' "$(date -Is)"
    printf 'uname='; uname -a
    printf 'cargo='; "$CARGO_BIN" --version
    printf 'rustc='; "$RUSTC_BIN" -vV
    printf 'cargo_path=%s\n' "$CARGO_BIN"
    printf 'rustc_path=%s\n' "$RUSTC_BIN"
    printf 'PATH=%s\nCARGO_HOME=%s\n' "$PATH" "$CARGO_HOME"
    for key in CARGO_NET_OFFLINE CARGO_NET_HTTP_MULTIPLEXING CARGO_BUILD_JOBS RUSTUP_HOME; do
        value="${!key:-<unset>}"
        printf '%s=%s\n' "$key" "$value"
    done
    if [[ -n "$PARU_SOURCE" ]]; then
        printf 'paru_source=%s\n' "$PARU_SOURCE"
    fi
    if [[ -n "$RUSTUP_INIT" ]]; then
        printf 'rustup_init=%s\n' "$RUSTUP_INIT"
    fi
} >"${OUT}/environment.txt" 2>&1

run_case cargo-new "$CARGO_BIN" new --vcs none "${OUT}/hello"
if [[ -f "${OUT}/hello/Cargo.toml" ]]; then
    run_case hello-build bash -c 'cd "$1" && /usr/bin/cargo build' _ "${OUT}/hello"
fi

if [[ -n "$PARU_SOURCE" && -d "$PARU_SOURCE" ]]; then
    run_case paru-fetch bash -c 'cd "$1" && /usr/bin/cargo fetch --locked' _ "$PARU_SOURCE"
    run_case paru-build-frozen bash -c 'cd "$1" && /usr/bin/cargo build --frozen --release' _ "$PARU_SOURCE"
    run_case paru-build-offline env CARGO_NET_OFFLINE=true bash -c 'cd "$1" && /usr/bin/cargo build --frozen --release' _ "$PARU_SOURCE"
else
    printf 'SKIP paru: pass its existing AUR source directory as argument 1.\n' >>"${OUT}/summary.txt"
fi

if [[ -n "$RUSTUP_INIT" && -x "$RUSTUP_INIT" ]]; then
    # Isolate rustup's writes; leave the user's ~/.cargo and downloaded crates intact.
    run_case rustup-init \
        env "CARGO_HOME=${OUT}/rustup-cargo-home" "RUSTUP_HOME=${OUT}/rustup-home" \
        "$RUSTUP_INIT" -y --no-modify-path --default-toolchain none --profile minimal
else
    printf 'SKIP rustup-init: pass the failing rustup-init binary as argument 2.\n' >>"${OUT}/summary.txt"
fi

printf 'Diagnostics saved under: %s\n' "$OUT"
printf 'Export TritonTerm Logs after reproducing to include crash-log.txt (SIGSEGV registers and maps).\n'
cat "${OUT}/summary.txt"
