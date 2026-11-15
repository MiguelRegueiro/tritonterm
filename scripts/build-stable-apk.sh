#!/usr/bin/env bash
set -Eeuo pipefail

# Build the installable APK for a stable SemVer tag. Publishing is handled by the
# tag-triggered GitHub Actions workflow after it adds the APK to the GitHub Release.
: "${TRITONTERM_ANDROID_VERSION_CODE:?Set the monotonically increasing Android version code}"
: "${TRITONTERM_ANDROID_RELEASE_TAG:?Set the stable Git tag (for example, v1.0.1)}"
: "${TRITONTERM_ARCH_ROOTFS_VERSION:?Set the stable Arch Linux ARM image version}"
: "${TRITONTERM_ARCH_ROOTFS_RELEASE_TAG:?Set the exact stable Arch rootfs release tag}"
: "${TRITONTERM_ARCH_ROOTFS_RELEASE_BASE_URL:?Set the HTTPS Arch rootfs release asset URL}"

[[ "$TRITONTERM_ANDROID_VERSION_CODE" =~ ^[1-9][0-9]{0,8}$ ]] || {
    printf 'Invalid Android version code.\n' >&2
    exit 2
}
[[ "$TRITONTERM_ANDROID_RELEASE_TAG" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || {
    printf 'Stable APKs require a vMAJOR.MINOR.PATCH Git tag.\n' >&2
    exit 2
}
[[ "$TRITONTERM_ARCH_ROOTFS_RELEASE_TAG" != *-rc.* && "$TRITONTERM_ARCH_ROOTFS_RELEASE_TAG" != *-exp.* ]] || {
    printf 'Stable APKs must pin a stable Arch rootfs release.\n' >&2
    exit 2
}

version_name="${TRITONTERM_ANDROID_RELEASE_TAG#v}"
export TRITONTERM_ANDROID_VERSION_NAME="$version_name"
exec "$(dirname "${BASH_SOURCE[0]}")/build-signed-pinned-apk.sh"
