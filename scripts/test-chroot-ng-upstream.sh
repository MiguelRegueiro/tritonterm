#!/usr/bin/env bash
set -Eeuo pipefail
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${GUESTCC:?Set GUESTCC to an ARM64 GNU/Linux guest compiler}"
qemu="${QEMU:-$(command -v qemu-aarch64-static)}"
if [[ "${TRITONTERM_TEST_OPENAT2_UNAVAILABLE:-0}" == 1 ]]; then
    runner="$project_root/.tools/chroot-ng-tests"
    mkdir -p "$runner"
    "${HOSTCC:-cc}" -O2 -Wall -Wextra -Werror \
        "$project_root/tests/chroot_ng_no_openat2.c" -o "$runner/no-openat2"
    printf '#!/usr/bin/env bash\nexec %q %q "$@"\n' \
        "$runner/no-openat2" "$qemu" > "$runner/qemu"
    chmod 755 "$runner/qemu"
    export QEMU="$runner/qemu"
    echo 'Testing openat2-unavailable host capability: QEMU reference truncates high flags on its native path.'
fi
export CNG_ROOTFS_FETCH="${CNG_ROOTFS_FETCH:-0}"
export BIN="$project_root/app/src/main/jniLibs/arm64-v8a/libchroot-ng.so"
cd "$project_root/third_party/fake-chroot-ng"
exec sh tests/run.sh
