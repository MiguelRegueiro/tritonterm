#!/usr/bin/env bash
# Build TritonTerm's debug APK with a project-local Gradle installation.
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
gradle_version="8.10.2"
gradle_root="$project_root/.tools/gradle-$gradle_version"
gradle_bin="$gradle_root/bin/gradle"

# Android Gradle Plugin 8.x needs a supported JDK; prefer 17 when the system
# default is a newer preview/runtime version.
java_major=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    java_major="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
fi
if [[ -z "$java_major" || "$java_major" -gt 23 ]]; then
    for candidate in /usr/lib/jvm/java-17-temurin-jdk /usr/lib/jvm/java-17-openjdk "$HOME/.jdks/temurin-17"; do
        if [[ -x "$candidate/bin/java" ]]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
fi
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

if [[ ! -d "$sdk_root/platforms/android-36" ]]; then
    echo "Android SDK platform 36 is required; expected $sdk_root/platforms/android-36" >&2
    exit 1
fi

if ! command -v fish >/dev/null 2>&1; then
    echo "Fish is required to syntax-check the packaged Arch guest smoke script." >&2
    exit 1
fi
for fish_script in "$project_root"/app/src/main/assets/*.fish; do
    fish --no-execute "$fish_script"
done

if [[ ! -x "$gradle_bin" ]]; then
    mkdir -p "$project_root/.tools"
    archive="$(mktemp "${TMPDIR:-/tmp}/tritonterm-gradle.XXXXXX.zip")"
    trap 'rm -f "$archive"' EXIT
    echo "Downloading Gradle $gradle_version (one-time project-local setup)…"
    curl --fail --location --silent --show-error \
        "https://services.gradle.org/distributions/gradle-$gradle_version-bin.zip" \
        --output "$archive"
    unzip -q "$archive" -d "$project_root/.tools"
fi

export ANDROID_HOME="$sdk_root"
export ANDROID_SDK_ROOT="$sdk_root"
cd "$project_root"
gradle_properties=()
if [[ -n "${TRITONTERM_ARCH_ROOTFS_VERSION:-}" ]]; then
    gradle_properties+=("-Ptritonterm.archRootfsVersion=${TRITONTERM_ARCH_ROOTFS_VERSION}")
fi
if [[ -n "${TRITONTERM_ARCH_ROOTFS_RELEASE_TAG:-}" ]]; then
    gradle_properties+=("-Ptritonterm.archRootfsReleaseTag=${TRITONTERM_ARCH_ROOTFS_RELEASE_TAG}")
fi
if [[ -n "${TRITONTERM_ARCH_ROOTFS_RELEASE_BASE_URL:-}" ]]; then
    gradle_properties+=("-Ptritonterm.archRootfsReleaseBaseUrl=${TRITONTERM_ARCH_ROOTFS_RELEASE_BASE_URL}")
fi
if [[ -n "${TRITONTERM_ANDROID_VERSION_CODE:-}" ]]; then
    gradle_properties+=("-Ptritonterm.androidVersionCode=${TRITONTERM_ANDROID_VERSION_CODE}")
fi
if [[ -n "${TRITONTERM_ANDROID_VERSION_NAME:-}" ]]; then
    gradle_properties+=("-Ptritonterm.androidVersionName=${TRITONTERM_ANDROID_VERSION_NAME}")
fi
"$gradle_bin" --no-daemon "${gradle_properties[@]}" :app:testDebugUnitTest :app:assembleDebug

apk="$project_root/app/build/outputs/apk/debug/app-debug.apk"
dist_apk="$project_root/dist/tritonterm-debug.apk"
if [[ ! -f "$apk" ]]; then
    echo "Gradle completed, but the expected debug APK was not created: $apk" >&2
    exit 1
fi

"$project_root/scripts/verify-packaged-native-libs.sh" "$apk"

mkdir -p "$project_root/dist"
cp "$apk" "$dist_apk"
echo "APK ready to install: $dist_apk"
