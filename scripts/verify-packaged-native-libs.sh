#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-${PROJECT_ROOT}/app/build/outputs/apk/debug/app-debug.apk}"
MANIFEST="${PROJECT_ROOT}/docs/native-binaries.sha256"

test -f "${APK}" || { echo "APK not found: ${APK}" >&2; exit 1; }

expected_paths="$(awk 'NF == 2 { print $2 }' "${MANIFEST}" | sort)"
actual_paths="$(unzip -Z1 "${APK}" | sed -n 's#^lib/##p' | grep '\.so$' | sort)"
if [[ "${expected_paths}" != "${actual_paths}" ]]; then
    echo "APK native library set differs from ${MANIFEST}" >&2
    diff -u <(printf '%s\n' "${expected_paths}") <(printf '%s\n' "${actual_paths}") || true
    exit 1
fi

while read -r expected path; do
    [[ -n "${expected}" ]] || continue
    actual="$(unzip -p "${APK}" "lib/${path}" | sha256sum | awk '{print $1}')"
    if [[ "${expected}" != "${actual}" ]]; then
        echo "SHA-256 mismatch for lib/${path}: expected ${expected}, got ${actual}" >&2
        exit 1
    fi
    echo "OK lib/${path} ${actual}"
done < "${MANIFEST}"
