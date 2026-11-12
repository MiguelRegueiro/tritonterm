#!/usr/bin/env bash
set -Eeuo pipefail

: "${TRITONTERM_ARCH_ROOTFS_VERSION:?Set the immutable rootfs version to pin into this APK}"
: "${TRITONTERM_ARCH_ROOTFS_RELEASE_TAG:?Set the GitHub Release tag containing the matching rootfs assets}"
: "${TRITONTERM_ARCH_ROOTFS_RELEASE_BASE_URL:?Set the HTTPS release asset base URL}"
: "${TRITONTERM_ANDROID_KEYSTORE:?Set the path to the protected Android release keystore}"
: "${TRITONTERM_ANDROID_STORE_PASSWORD:?Set the Android keystore password}"
: "${TRITONTERM_ANDROID_KEY_ALIAS:?Set the Android release key alias}"
: "${TRITONTERM_ANDROID_KEY_PASSWORD:?Set the Android release key password}"
: "${TRITONTERM_ANDROID_VERSION_CODE:?Set a monotonically increasing Android version code}"
: "${TRITONTERM_ANDROID_VERSION_NAME:?Set the user-visible Android version name}"

[[ -s "$TRITONTERM_ANDROID_KEYSTORE" ]] || { printf 'Android keystore is missing or empty.\n' >&2; exit 2; }
[[ "$TRITONTERM_ANDROID_VERSION_CODE" =~ ^[1-9][0-9]{0,8}$ ]] || { printf 'Invalid Android version code.\n' >&2; exit 2; }
[[ "$TRITONTERM_ARCH_ROOTFS_VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || { printf 'Invalid rootfs version.\n' >&2; exit 2; }
[[ "$TRITONTERM_ARCH_ROOTFS_RELEASE_TAG" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || { printf 'Invalid release tag.\n' >&2; exit 2; }
[[ "$TRITONTERM_ARCH_ROOTFS_RELEASE_BASE_URL" == https://*"/$TRITONTERM_ARCH_ROOTFS_RELEASE_TAG" ]] || {
    printf 'Release base URL must be HTTPS and end in /%s.\n' "$TRITONTERM_ARCH_ROOTFS_RELEASE_TAG" >&2
    exit 2
}

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
apksigner="$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -print | sort -V | tail -n 1)"
[[ -x "$apksigner" ]] || { printf 'Android SDK apksigner is missing under %s.\n' "$sdk_root" >&2; exit 2; }

gradle_version=8.10.2
gradle="$project_root/.tools/gradle-$gradle_version/bin/gradle"
[[ -x "$gradle" ]] || { printf 'Project Gradle %s is missing; run scripts/build-apk.sh once first.\n' "$gradle_version" >&2; exit 2; }
cd "$project_root"
"$gradle" --no-daemon \
    -Ptritonterm.archRootfsVersion="$TRITONTERM_ARCH_ROOTFS_VERSION" \
    -Ptritonterm.archRootfsReleaseTag="$TRITONTERM_ARCH_ROOTFS_RELEASE_TAG" \
    -Ptritonterm.archRootfsReleaseBaseUrl="$TRITONTERM_ARCH_ROOTFS_RELEASE_BASE_URL" \
    -Ptritonterm.androidVersionCode="$TRITONTERM_ANDROID_VERSION_CODE" \
    -Ptritonterm.androidVersionName="$TRITONTERM_ANDROID_VERSION_NAME" \
    :app:assembleRelease

apk="$project_root/app/build/outputs/apk/release/app-release.apk"
[[ -s "$apk" ]] || { printf 'Gradle did not produce the signed release APK.\n' >&2; exit 1; }
"$apksigner" verify --verbose "$apk"
"$project_root/scripts/verify-packaged-native-libs.sh" "$apk"
dist="$project_root/dist/tritonterm-${TRITONTERM_ANDROID_VERSION_NAME}.apk"
mkdir -p "$(dirname "$dist")"
cp "$apk" "$dist"
sha256sum "$dist"
printf 'Signed APK ready: %s\n' "$dist"
