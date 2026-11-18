#!/usr/bin/env bash
# Apply TritonTerm's reviewed patches to the exact pinned PRoot source tree.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PR_SOURCE="${TRITONTERM_PR_SOURCE:-$PROJECT_ROOT/third_party/oonid-pr}"
EXPECTED_PR_COMMIT='7029b1fe8fa84d64339cabe38f9c5be2ea2ae7ac'

if [[ -f "$PR_SOURCE/TRITONTERM_SOURCE_COMMIT" ]]; then
    [[ "$(<"$PR_SOURCE/TRITONTERM_SOURCE_COMMIT")" == "$EXPECTED_PR_COMMIT" ]] || {
        echo "PRoot source snapshot does not match $EXPECTED_PR_COMMIT" >&2
        exit 1
    }
else
    git -C "$PR_SOURCE" rev-parse --git-dir >/dev/null 2>&1 || {
        echo 'PRoot source is missing; initialize the pinned third_party/oonid-pr submodule first.' >&2
        exit 1
    }
    [[ "$(git -C "$PR_SOURCE" rev-parse HEAD)" == "$EXPECTED_PR_COMMIT" ]] || {
        echo "PRoot source is not at pinned commit $EXPECTED_PR_COMMIT" >&2
        exit 1
    }
fi

apply_patch_or_check_marker() {
    local patch_file="$1" marker="$2" marker_file="$3"
    if git -C "$PR_SOURCE" rev-parse --git-dir >/dev/null 2>&1; then
        if git -C "$PR_SOURCE" apply --check "$patch_file" 2>/dev/null; then
            git -C "$PR_SOURCE" apply "$patch_file"
        elif ! grep -qF "$marker" "$PR_SOURCE/$marker_file"; then
            echo "Cannot apply pinned TritonTerm patch: $patch_file" >&2
            return 1
        fi
    elif (cd "$PR_SOURCE" && patch --dry-run -p1 < "$patch_file" >/dev/null 2>&1); then
        (cd "$PR_SOURCE" && patch -p1 < "$patch_file" >/dev/null)
    elif ! grep -qF "$marker" "$PR_SOURCE/$marker_file"; then
        echo "Cannot apply pinned TritonTerm patch to source snapshot: $patch_file" >&2
        return 1
    fi
}

apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/pr-cli-install-progress.patch" \
    'write_extraction_progress' 'src/pr-cli/src/install.rs'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/pr-cli-diagnostics.patch" \
    'archive_cache_resolution' 'src/pr-cli/src/install.rs'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/pr-cli-preserve-failures.patch" \
    'failed_rootfs_preservation' 'src/pr-cli/src/install.rs'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/proot-crash-recorder.patch" \
    'log_guest_segv' 'src/proot/src/tracee/event.c'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/proot-userland-metadata.patch" \
    'TRITONTERM_USERLAND_METADATA_MODE' 'src/proot/src/GNUmakefile'

arch_patch="$PROJECT_ROOT/scripts/patches/tritonterm-arch-userland.patch"
if git -C "$PR_SOURCE" rev-parse --git-dir >/dev/null 2>&1; then
    if git -C "$PR_SOURCE" apply --check "$arch_patch" 2>/dev/null; then
        git -C "$PR_SOURCE" apply "$arch_patch"
    else
        markers=(
            'src/pr-cli/src/install.rs:TRITONTERM_ARCH_USERLAND_EXTRACTION_V1'
            'src/pr-cli/src/login.rs:TRITONTERM_ARCH_USERLAND_LOGIN_V1'
            'src/pr-cli/src/shared.rs:build_proot_args_for_identity'
            'src/proot/src/extension/fake_id0/helper_functions.c:TRITONTERM_USERLAND_METADATA_STORAGE_V1'
            'src/proot/src/extension/fake_id0/exec.c:TRITONTERM_ARCH_USERLAND_SETUID_V1'
            'src/proot/src/extension/fake_id0/stat.c:TRITONTERM_ARCH_USERLAND_STATX_V1'
        )
        for item in "${markers[@]}"; do
            file="${item%%:*}" marker="${item#*:}"
            grep -qF "$marker" "$PR_SOURCE/$file" || {
                echo "PRoot source is partially patched: $file lacks $marker" >&2
                exit 1
            }
        done
    fi
elif (cd "$PR_SOURCE" && patch --dry-run -p1 < "$arch_patch" >/dev/null 2>&1); then
    (cd "$PR_SOURCE" && patch -p1 < "$arch_patch" >/dev/null)
else
    for item in \
        'src/pr-cli/src/install.rs:TRITONTERM_ARCH_USERLAND_EXTRACTION_V1' \
        'src/pr-cli/src/login.rs:TRITONTERM_ARCH_USERLAND_LOGIN_V1' \
        'src/pr-cli/src/shared.rs:build_proot_args_for_identity' \
        'src/proot/src/extension/fake_id0/helper_functions.c:TRITONTERM_USERLAND_METADATA_STORAGE_V1' \
        'src/proot/src/extension/fake_id0/exec.c:TRITONTERM_ARCH_USERLAND_SETUID_V1' \
        'src/proot/src/extension/fake_id0/stat.c:TRITONTERM_ARCH_USERLAND_STATX_V1'; do
        file="${item%%:*}" marker="${item#*:}"
        grep -qF "$marker" "$PR_SOURCE/$file" || {
            echo "PRoot source snapshot is partially patched: $file lacks $marker" >&2
            exit 1
        }
    done
fi
root_traversal_patch="$PROJECT_ROOT/scripts/patches/tritonterm-root-traversal.patch"
if git -C "$PR_SOURCE" rev-parse --git-dir >/dev/null 2>&1; then
    if git -C "$PR_SOURCE" apply --check "$root_traversal_patch" 2>/dev/null; then
        git -C "$PR_SOURCE" apply "$root_traversal_patch"
    elif ! grep -qF 'TRITONTERM_ARCH_ROOT_TRAVERSAL_V1' "$PR_SOURCE/src/pr-cli/src/install.rs" \
        || ! grep -qF 'TRITONTERM_ARCH_ROOT_TRAVERSAL_LOGIN_V1' "$PR_SOURCE/src/pr-cli/src/login.rs"; then
        echo "Cannot apply pinned TritonTerm patch: $root_traversal_patch" >&2
        exit 1
    fi
elif (cd "$PR_SOURCE" && patch --dry-run -p1 < "$root_traversal_patch" >/dev/null 2>&1); then
    (cd "$PR_SOURCE" && patch -p1 < "$root_traversal_patch" >/dev/null)
elif ! grep -qF 'TRITONTERM_ARCH_ROOT_TRAVERSAL_V1' "$PR_SOURCE/src/pr-cli/src/install.rs" \
    || ! grep -qF 'TRITONTERM_ARCH_ROOT_TRAVERSAL_LOGIN_V1' "$PR_SOURCE/src/pr-cli/src/login.rs"; then
    echo "Cannot apply pinned TritonTerm patch to source snapshot: $root_traversal_patch" >&2
    exit 1
fi
archive_test_patch="$PROJECT_ROOT/scripts/patches/pr-cli-rootfs-archive-test.patch"
if ! grep -qF 'fn extract_arch_fakeroot_payload()' "$PR_SOURCE/src/pr-cli/src/install.rs"; then
    apply_patch_or_check_marker "$archive_test_patch" \
        'extract_arch_fakeroot_payload' 'src/pr-cli/src/install.rs'
fi
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/tritonterm-arch-guest-env.patch" \
    'TRITONTERM_GUEST_LOCALE_TZ_V1' 'src/pr-cli/src/login.rs'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/pr-cli-network-dns.patch" \
    'TRITONTERM_ANDROID_NETWORK_DNS_V1' 'src/pr-cli/src/shared.rs'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/tritonterm-arch-guest-mounts.patch" \
    'TRITONTERM_ANDROID_MOUNT_TABLE_FILTER_V1' 'src/pr-cli/src/shared.rs'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/proot-fast-io.patch" \
    'enable_syscall_passthrough' 'src/proot/src/syscall/seccomp.c'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/proot-bound-owner.patch" \
    'if(status == 0 && get_meta_path(tracee, path, meta_path) == 0)' 'src/proot/src/extension/fake_id0/stat.c'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/proot-metadata-reads.patch" \
    'bool reuse_translated_path' 'src/proot/src/tracee/statx.c'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/proot-fstat-buffer.patch" \
    'static int handle_fstat_readlink_exit' 'src/proot/src/extension/fake_id0/fake_id0.c'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/proot-statx-missing.patch" \
    'bool native_exit' 'src/proot/src/tracee/statx.c'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/proot-runtime-perf.patch" \
    'PROOT_PERF_COUNTERS' 'src/proot/src/tracee/perf.h'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/proot-metadata-single-open.patch" \
    'read_meta_file_if_present' 'src/proot/src/extension/fake_id0/helper_functions.h'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/proot-runtime-timing.patch" \
    'PROOT_PERF_TIMING' 'src/proot/src/tracee/perf.c'
apply_patch_or_check_marker "$PROJECT_ROOT/scripts/patches/pr-cli-build-hygiene.patch" \
    'entries.is_multiple_of(64)' 'src/pr-cli/src/install.rs'
printf 'TRITONTERM_PINNED_PROOT_PATCHES_READY %s\n' "$EXPECTED_PR_COMMIT"
