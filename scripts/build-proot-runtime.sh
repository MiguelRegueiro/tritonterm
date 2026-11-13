#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PR_SOURCE="${PROJECT_ROOT}/third_party/oonid-pr"
KEYRING_SOURCE="${PROJECT_ROOT}/third_party/archlinuxarm-keyring"
EXPECTED_PR_COMMIT="31afc11ca3e675fa8bbbe530979afc8c467218dc"
EXPECTED_KEYRING_COMMIT="91e6b11698f8df66042d56aaa56fbe9c9263847d"
EXPECTED_RUST="1.98.0"
JNI_DIR="${PROJECT_ROOT}/app/src/main/jniLibs/arm64-v8a"

if [[ -f "${PR_SOURCE}/TRITONTERM_SOURCE_COMMIT" && -f "${KEYRING_SOURCE}/TRITONTERM_SOURCE_COMMIT" ]]; then
    # Public corresponding-source archives carry the exact source trees and commit
    # markers, but intentionally omit private development-repository Git metadata.
    test "$(<"${PR_SOURCE}/TRITONTERM_SOURCE_COMMIT")" = "${EXPECTED_PR_COMMIT}" || {
        echo "PRoot source snapshot does not match ${EXPECTED_PR_COMMIT}" >&2
        exit 1
    }
    test "$(<"${KEYRING_SOURCE}/TRITONTERM_SOURCE_COMMIT")" = "${EXPECTED_KEYRING_COMMIT}" || {
        echo "Arch Linux ARM keyring snapshot does not match ${EXPECTED_KEYRING_COMMIT}" >&2
        exit 1
    }
else
    git -C "${PROJECT_ROOT}" submodule update --init third_party/oonid-pr third_party/archlinuxarm-keyring
    test "$(git -C "${PR_SOURCE}" rev-parse HEAD)" = "${EXPECTED_PR_COMMIT}" || {
        echo "PRoot source is not at the pinned commit ${EXPECTED_PR_COMMIT}" >&2
        exit 1
    }
    test "$(git -C "${KEYRING_SOURCE}" rev-parse HEAD)" = "${EXPECTED_KEYRING_COMMIT}" || {
        echo "Arch Linux ARM keyring source is not at the pinned commit ${EXPECTED_KEYRING_COMMIT}" >&2
        exit 1
    }
fi

EXTRACTION_PATCH="${PROJECT_ROOT}/scripts/patches/pr-cli-install-progress.patch"
DIAGNOSTICS_PATCH="${PROJECT_ROOT}/scripts/patches/pr-cli-diagnostics.patch"
PRESERVE_PATCH="${PROJECT_ROOT}/scripts/patches/pr-cli-preserve-failures.patch"
USERLAND_PATCH="${PROJECT_ROOT}/scripts/patches/proot-userland-metadata.patch"
ARCH_USERLAND_PATCH="${PROJECT_ROOT}/scripts/patches/tritonterm-arch-userland.patch"
apply_runtime_patch() {
    local patch_file="$1"
    local marker="$2"
    local marker_file="${3:-src/pr-cli/src/install.rs}"
    if [[ -d "${PR_SOURCE}/.git" ]] || git -C "${PR_SOURCE}" rev-parse --git-dir >/dev/null 2>&1; then
        if git -C "${PR_SOURCE}" apply --check "${patch_file}" 2>/dev/null; then
            git -C "${PR_SOURCE}" apply "${patch_file}"
        elif ! grep -qF "${marker}" "${PR_SOURCE}/${marker_file}"; then
            echo "Could not apply TritonTerm's pinned PRoot patch: ${patch_file}" >&2
            return 1
        fi
    else
        # Public corresponding-source archives intentionally omit Git metadata.
        # GNU patch applies the same reviewed patch without requiring a checkout.
        if (cd "${PR_SOURCE}" && patch --dry-run -p1 < "${patch_file}" >/dev/null); then
            (cd "${PR_SOURCE}" && patch -p1 < "${patch_file}" >/dev/null)
        elif ! grep -qF "${marker}" "${PR_SOURCE}/${marker_file}"; then
            echo "Could not apply TritonTerm's pinned PRoot patch: ${patch_file}" >&2
            return 1
        fi
    fi
}

apply_runtime_patch "${EXTRACTION_PATCH}" 'write_extraction_progress'
apply_runtime_patch "${DIAGNOSTICS_PATCH}" 'archive_cache_resolution'
apply_runtime_patch "${PRESERVE_PATCH}" 'failed_rootfs_preservation'
apply_runtime_patch "${USERLAND_PATCH}" 'TRITONTERM_USERLAND_METADATA_MODE' 'src/proot/src/GNUmakefile'
apply_arch_userland_patch() {
    local -a markers=(
        'src/pr-cli/src/install.rs:TRITONTERM_ARCH_USERLAND_EXTRACTION_V1'
        'src/pr-cli/src/login.rs:TRITONTERM_ARCH_USERLAND_LOGIN_V1'
        'src/pr-cli/src/shared.rs:build_proot_args_for_identity'
        'src/proot/src/extension/fake_id0/exec.c:TRITONTERM_ARCH_USERLAND_SETUID_V1'
        'src/proot/src/extension/fake_id0/stat.c:TRITONTERM_ARCH_USERLAND_STATX_V1'
    )
    if [[ -d "${PR_SOURCE}/.git" ]] || git -C "${PR_SOURCE}" rev-parse --git-dir >/dev/null 2>&1; then
        if git -C "${PR_SOURCE}" apply --check "${ARCH_USERLAND_PATCH}" 2>/dev/null; then
            git -C "${PR_SOURCE}" apply "${ARCH_USERLAND_PATCH}"
        else
            local item file marker
            for item in "${markers[@]}"; do
                file="${item%%:*}"
                marker="${item#*:}"
                grep -qF "${marker}" "${PR_SOURCE}/${file}" || {
                    echo "PRoot has a partial or incompatible Arch USERLAND patch: ${file} lacks ${marker}" >&2
                    return 1
                }
            done
        fi
    else
        if (cd "${PR_SOURCE}" && patch --dry-run -p1 < "${ARCH_USERLAND_PATCH}" >/dev/null 2>&1); then
            (cd "${PR_SOURCE}" && patch -p1 < "${ARCH_USERLAND_PATCH}" >/dev/null)
        else
            local item file marker
            for item in "${markers[@]}"; do
                file="${item%%:*}"
                marker="${item#*:}"
                grep -qF "${marker}" "${PR_SOURCE}/${file}" || {
                    echo "PRoot source snapshot has a partial or incompatible Arch USERLAND patch: ${file} lacks ${marker}" >&2
                    return 1
                }
            done
        fi
    fi
}
apply_arch_userland_patch

if [[ -f "${PR_SOURCE}/vendor/samba/TRITONTERM_TALLOC_COMMIT" ]]; then
    test "$(<"${PR_SOURCE}/vendor/samba/TRITONTERM_TALLOC_COMMIT")" = "2f8dfde1210395175e726455bdb63a7b97245a72" || {
        echo "Bundled talloc source does not match the pinned Samba commit." >&2
        exit 1
    }
    test -f "${PR_SOURCE}/vendor/samba/lib/talloc/talloc.h" || {
        echo "Bundled talloc source is incomplete." >&2
        exit 1
    }
else
    # Upstream's .gitmodules includes SSH URLs. Rewrite only those URLs for this fetch.
    git -c url.https://github.com/.insteadOf=git@github.com: \
        -C "${PR_SOURCE}" submodule update --init vendor/samba
fi

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
