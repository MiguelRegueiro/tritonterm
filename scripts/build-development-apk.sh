#!/usr/bin/env bash
set -Eeuo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_config="${TRITONTERM_DEVELOPMENT_CONFIG:-${XDG_CONFIG_HOME:-$HOME/.config}/tritonterm/development.env}"
if [[ -e "$local_config" ]]; then
    [[ -f "$local_config" && ! -L "$local_config" && -O "$local_config" && "$(stat -c '%a' "$local_config")" == 600 ]] || {
        printf 'Local build config must be a regular file owned by you with mode 600: %s\n' "$local_config" >&2
        exit 2
    }
    set -a
    source "$local_config"
    set +a
fi

# Local development builds keep one private signing key across rebuilds.
export TRITONTERM_ARCH_ROOTFS_VERSION="${TRITONTERM_ARCH_ROOTFS_VERSION:-2026.11.15.1}"
export TRITONTERM_ARCH_ROOTFS_RELEASE_TAG="${TRITONTERM_ARCH_ROOTFS_RELEASE_TAG:-arch-rootfs-2026.11.15.1-rc.56}"
export TRITONTERM_ARCH_ROOTFS_RELEASE_BASE_URL="${TRITONTERM_ARCH_ROOTFS_RELEASE_BASE_URL:-https://github.com/MiguelRegueiro/tritonterm-releases/releases/download/$TRITONTERM_ARCH_ROOTFS_RELEASE_TAG}"

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
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

state_dir="${XDG_CONFIG_HOME:-$HOME/.config}/tritonterm"
umask 077
mkdir -p "$state_dir"
exec 9>"$state_dir/local-build.lock"
flock 9

if [[ -z "${TRITONTERM_ANDROID_KEYSTORE:-}" ]]; then
    export TRITONTERM_ANDROID_KEYSTORE="$state_dir/development.jks"
    export TRITONTERM_ANDROID_STORE_PASSWORD=android
    export TRITONTERM_ANDROID_KEY_ALIAS=tritonterm-local
    export TRITONTERM_ANDROID_KEY_PASSWORD=android
    if [[ ! -e "$TRITONTERM_ANDROID_KEYSTORE" ]]; then
        keytool -genkeypair -noprompt -storetype PKCS12 \
            -keystore "$TRITONTERM_ANDROID_KEYSTORE" \
            -storepass:env TRITONTERM_ANDROID_STORE_PASSWORD \
            -keypass:env TRITONTERM_ANDROID_KEY_PASSWORD \
            -alias "$TRITONTERM_ANDROID_KEY_ALIAS" -keyalg RSA -keysize 3072 \
            -validity 10000 -dname 'CN=TritonTerm local development'
        chmod 600 "$TRITONTERM_ANDROID_KEYSTORE"
        printf 'Created persistent local development key: %s\n' "$TRITONTERM_ANDROID_KEYSTORE"
    fi
fi

last_build=27
if [[ -f "$state_dir/local-build-number" ]]; then
    last_build="$(<"$state_dir/local-build-number")"
fi
[[ "$last_build" =~ ^[1-9][0-9]{0,5}$ ]] || {
    printf 'Invalid local build counter in %s/local-build-number.\n' "$state_dir" >&2
    exit 2
}
export TRITONTERM_ANDROID_EXPERIMENTAL_BUILD="${TRITONTERM_ANDROID_EXPERIMENTAL_BUILD:-$((last_build + 1))}"
[[ "$TRITONTERM_ANDROID_EXPERIMENTAL_BUILD" =~ ^[1-9][0-9]{0,5}$ ]] || {
    printf 'Invalid experimental build number.\n' >&2
    exit 2
}
export TRITONTERM_ANDROID_VERSION_CODE="${TRITONTERM_ANDROID_VERSION_CODE:-$((202612000 + TRITONTERM_ANDROID_EXPERIMENTAL_BUILD))}"
[[ "$TRITONTERM_ANDROID_VERSION_CODE" =~ ^[1-9][0-9]{0,8}$ ]] || {
    printf 'Invalid Android version code.\n' >&2
    exit 2
}
[[ "$TRITONTERM_ARCH_ROOTFS_RELEASE_TAG" == *-rc.* ]] || {
    printf 'Development APKs must pin a tested Arch rootfs RC release.\n' >&2
    exit 2
}
if (( TRITONTERM_ANDROID_EXPERIMENTAL_BUILD > last_build )); then
    printf '%s\n' "$TRITONTERM_ANDROID_EXPERIMENTAL_BUILD" > "$state_dir/local-build-number"
fi
export TRITONTERM_ANDROID_VERSION_NAME="${TRITONTERM_ARCH_ROOTFS_VERSION}-exp.${TRITONTERM_ANDROID_EXPERIMENTAL_BUILD}"
exec "$project_root/scripts/build-signed-pinned-apk.sh"
