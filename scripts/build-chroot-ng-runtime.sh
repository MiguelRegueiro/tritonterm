#!/usr/bin/env bash
set -Eeuo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="$project_root/third_party/fake-chroot-ng"
ndk="${TRITONTERM_NDK_ROOT:-$project_root/third_party/oonid-pr/build/ndk/android-ndk-r27c}"
compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang"
stripper="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
[[ -x "$compiler" ]] || { echo "NDK r27c compiler missing: $compiler" >&2; exit 1; }
grep -qx '869c42b705e889a487a9503e219fedfab71f4d88' "$source_dir/TRITONTERM_UPSTREAM"
export SOURCE_DATE_EPOCH=1793319714
export CFLAGS="-O2 -g -Werror -ffile-prefix-map=$project_root=/source"
export LDFLAGS='-Wl,-z,max-page-size=16384'
make -C "$source_dir" -j4 CC="$compiler" BUILD="$project_root/.tools/chroot-ng-android"
mkdir -p "$project_root/app/src/main/jniLibs/arm64-v8a"
"$stripper" --strip-debug "$project_root/.tools/chroot-ng-android/chroot-ng"
install -m 0755 "$project_root/.tools/chroot-ng-android/chroot-ng" \
    "$project_root/app/src/main/jniLibs/arm64-v8a/libchroot-ng.so"
sha256sum "$project_root/app/src/main/jniLibs/arm64-v8a/libchroot-ng.so"
