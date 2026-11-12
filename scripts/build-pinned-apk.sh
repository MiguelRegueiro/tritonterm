#!/usr/bin/env bash
set -Eeuo pipefail

: "${TRITONTERM_ARCH_ROOTFS_VERSION:?Set the immutable rootfs version that appears in its signed manifest}"
: "${TRITONTERM_ARCH_ROOTFS_RELEASE_TAG:?Set the GitHub Release tag containing the pinned rootfs assets}"
: "${TRITONTERM_ARCH_ROOTFS_RELEASE_BASE_URL:?Set the full HTTPS GitHub Release asset base URL}"

[[ "$TRITONTERM_ARCH_ROOTFS_VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || {
    printf 'Invalid rootfs version.\n' >&2
    exit 2
}
[[ "$TRITONTERM_ARCH_ROOTFS_RELEASE_TAG" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || {
    printf 'Invalid release tag.\n' >&2
    exit 2
}
[[ "$TRITONTERM_ARCH_ROOTFS_RELEASE_BASE_URL" == https://*"/$TRITONTERM_ARCH_ROOTFS_RELEASE_TAG" ]] || {
    printf 'Release base URL must be HTTPS and end in /%s.\n' "$TRITONTERM_ARCH_ROOTFS_RELEASE_TAG" >&2
    exit 2
}

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$project_root/scripts/build-apk.sh"
