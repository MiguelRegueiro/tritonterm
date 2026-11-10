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
"$gradle_bin" --no-daemon :app:assembleDebug

apk="$project_root/app/build/outputs/apk/debug/app-debug.apk"
dist_apk="$project_root/dist/tritonterm-debug.apk"
if [[ ! -f "$apk" ]]; then
    echo "Gradle completed, but the expected debug APK was not created: $apk" >&2
    exit 1
fi

mkdir -p "$project_root/dist"
cp "$apk" "$dist_apk"
echo "APK ready to install: $dist_apk"
