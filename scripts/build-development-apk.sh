#!/usr/bin/env bash
set -Eeuo pipefail

# Build an installable development APK with the production signing key. Development
# APKs use the same app ID and signer as tagged releases so updates preserve app data.
: "${TRITONTERM_ANDROID_VERSION_CODE:?Set a version code higher than the APK currently installed on the target device}"
: "${TRITONTERM_ANDROID_EXPERIMENTAL_BUILD:?Set the development build number (for example, 7)}"
: "${TRITONTERM_ARCH_ROOTFS_VERSION:?Set the pinned Arch Linux ARM image version}"
: "${TRITONTERM_ARCH_ROOTFS_RELEASE_TAG:?Set the exact Arch rootfs release tag}"
: "${TRITONTERM_ARCH_ROOTFS_RELEASE_BASE_URL:?Set the HTTPS Arch rootfs release asset URL}"

[[ "$TRITONTERM_ANDROID_VERSION_CODE" =~ ^[1-9][0-9]{0,8}$ ]] || {
    printf 'Invalid Android version code.\n' >&2
    exit 2
}
[[ "$TRITONTERM_ANDROID_EXPERIMENTAL_BUILD" =~ ^[1-9][0-9]{0,5}$ ]] || {
    printf 'Invalid experimental build number.\n' >&2
    exit 2
}
[[ "$TRITONTERM_ARCH_ROOTFS_RELEASE_TAG" == *-rc.* ]] || {
    printf 'Development APKs must pin a tested Arch rootfs RC release.\n' >&2
    exit 2
}

version_name="${TRITONTERM_ARCH_ROOTFS_VERSION}-exp.${TRITONTERM_ANDROID_EXPERIMENTAL_BUILD}"
export TRITONTERM_ANDROID_VERSION_NAME="$version_name"
exec "$(dirname "${BASH_SOURCE[0]}")/build-signed-pinned-apk.sh"
