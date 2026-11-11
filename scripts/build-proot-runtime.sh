#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PR_SOURCE="${PROJECT_ROOT}/third_party/oonid-pr"
KEYRING_SOURCE="${PROJECT_ROOT}/third_party/archlinuxarm-keyring"
EXPECTED_PR_COMMIT="7029b1fe8fa84d64339cabe38f9c5be2ea2ae7ac"
EXPECTED_KEYRING_COMMIT="91e6b11698f8df66042d56aaa56fbe9c9263847d"
EXPECTED_RUST="1.98.0"
JNI_DIR="${PROJECT_ROOT}/app/src/main/jniLibs/arm64-v8a"

git -C "${PROJECT_ROOT}" submodule update --init third_party/oonid-pr third_party/archlinuxarm-keyring
test "$(git -C "${PR_SOURCE}" rev-parse HEAD)" = "${EXPECTED_PR_COMMIT}" || {
    echo "PRoot source is not at the pinned commit ${EXPECTED_PR_COMMIT}" >&2
    exit 1
}
test "$(git -C "${KEYRING_SOURCE}" rev-parse HEAD)" = "${EXPECTED_KEYRING_COMMIT}" || {
    echo "Arch Linux ARM keyring source is not at the pinned commit ${EXPECTED_KEYRING_COMMIT}" >&2
    exit 1
}

EXTRACTION_PATCH="${PROJECT_ROOT}/scripts/patches/pr-cli-install-progress.patch"
if git -C "${PR_SOURCE}" apply --check "${EXTRACTION_PATCH}" 2>/dev/null; then
    git -C "${PR_SOURCE}" apply "${EXTRACTION_PATCH}"
elif ! grep -qF 'write_extraction_progress' "${PR_SOURCE}/src/pr-cli/src/install.rs"; then
    echo "Could not apply TritonTerm's pinned pr-cli extraction patch." >&2
    exit 1
fi

DIAGNOSTICS_PATCH="${PROJECT_ROOT}/scripts/patches/pr-cli-diagnostics.patch"
if git -C "${PR_SOURCE}" apply --check "${DIAGNOSTICS_PATCH}" 2>/dev/null; then
    git -C "${PR_SOURCE}" apply "${DIAGNOSTICS_PATCH}"
elif ! grep -qF 'archive_cache_resolution' "${PR_SOURCE}/src/pr-cli/src/install.rs"; then
    echo "Could not apply TritonTerm's pinned pr-cli diagnostics patch." >&2
    exit 1
fi

PRESERVE_PATCH="${PROJECT_ROOT}/scripts/patches/pr-cli-preserve-failures.patch"
if git -C "${PR_SOURCE}" apply --check "${PRESERVE_PATCH}" 2>/dev/null; then
    git -C "${PR_SOURCE}" apply "${PRESERVE_PATCH}"
elif ! grep -qF 'failed_rootfs_preservation' "${PR_SOURCE}/src/pr-cli/src/install.rs"; then
    echo "Could not apply TritonTerm's failed-install preservation patch." >&2
    exit 1
fi

# Upstream's .gitmodules includes SSH URLs. Rewrite only those URLs for this fetch.
git -c url.https://github.com/.insteadOf=git@github.com: \
    -C "${PR_SOURCE}" submodule update --init vendor/samba

if ! command -v rustup >/dev/null 2>&1; then
    echo "Install rustup first; TritonTerm pins Rust ${EXPECTED_RUST}." >&2
    exit 1
fi
if ! rustup toolchain list | grep -q "^${EXPECTED_RUST}-"; then
    rustup toolchain install "${EXPECTED_RUST}" --profile minimal
fi
rustup target add aarch64-linux-android --toolchain "${EXPECTED_RUST}"
export RUSTUP_TOOLCHAIN="${EXPECTED_RUST}"
actual_rust="$(rustc --version | awk '{print $2}')"
test "${actual_rust}" = "${EXPECTED_RUST}" || {
    echo "Expected Rust ${EXPECTED_RUST}, found ${actual_rust}" >&2
    exit 1
}

# Upstream pins NDK r27c and verifies the archive SHA-256 in build.sh.
bash "${PR_SOURCE}/scripts/build.sh" --arch=arm64

mkdir -p "${JNI_DIR}"
install -m 0755 "${PR_SOURCE}/build/out/arm64/proot" "${JNI_DIR}/libproot.so"
install -m 0755 "${PR_SOURCE}/build/out/arm64/loader" "${JNI_DIR}/libproot-loader.so"

# Match upstream's Android target and path remapping while requiring the committed lockfile.
TARGET="aarch64-linux-android"
CARGO_HOME_PATH="${CARGO_HOME:-${HOME}/.cargo}"
RUST_SYSROOT="$(rustc --print sysroot)"
RUST_COMMIT="$(rustc -vV | sed -n 's/^commit-hash: //p')"
REMAP="target.${TARGET}.rustflags=['--remap-path-prefix=${CARGO_HOME_PATH}=/cargo', '--remap-path-prefix=${RUST_SYSROOT}=/rust', '--remap-path-prefix=${RUST_SYSROOT}/lib/rustlib/src/rust=/rustc/${RUST_COMMIT}']"
(cd "${PR_SOURCE}/src/pr-cli" && cargo build --release --target "${TARGET}" --locked --config "${REMAP}")
PR_CLI="${PR_SOURCE}/src/pr-cli/target/${TARGET}/release/pr-cli"
install -m 0755 "${PR_CLI}" "${JNI_DIR}/libpr-cli.so"
leaks="$(strings "${JNI_DIR}/libpr-cli.so" | grep -cF -e "${CARGO_HOME_PATH}" -e "${RUST_SYSROOT}" -e "${PR_SOURCE}" || true)"
test "${leaks}" -eq 0 || {
    echo "pr-cli contains ${leaks} build-machine paths" >&2
    exit 1
}
install -m 0644 "${KEYRING_SOURCE}/packager/builder.asc" \
    "${PROJECT_ROOT}/app/src/main/assets/archlinuxarm-builder.asc"

sha256sum --check "${PROJECT_ROOT}/docs/native-runtime.sha256"
