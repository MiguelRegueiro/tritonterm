# Experimental chroot-ng backend

This local experiment adds fake-chroot-ng alongside PRoot. Source changes are
tracked locally; test APKs use the persistent local development signer. It is a compatibility/performance candidate, not a claim of a
speedup on Android. Upstream source is pinned in
`third_party/fake-chroot-ng/TRITONTERM_UPSTREAM`; local changes add the
TritonTerm metadata adapter. The engine is built freestanding with NDK r27c,
16 KiB load alignment, and is packaged as `libchroot-ng.so`.

## Build and select

```sh
./scripts/build-chroot-ng-runtime.sh
TRITONTERM_ANDROID_DEFAULT_ARCH_RUNTIME=chroot-ng ./scripts/build-development-apk.sh
```

The default property affects newly created configurations. In the app's config
editor, `arch_runtime chroot-ng` selects the experimental engine;
`arch_runtime proot` restores the existing engine. Force stop the app in Android
settings and reopen after changing it. This selection does not reinstall Arch.
The config editor remains accessible if a guest fails to start. No automatic
retry of a failed guest command is performed under another engine.

Arch download/extraction/preparation and setup checks continue through PRoot.
Only explicitly selected metadata-backed Arch login sessions use chroot-ng.
The launcher uses syscall rewriting (`-R`), the existing guest identity/home,
bind mappings and explicit guest environment. A missing native engine fails
clearly. Other distributions retain their existing PRoot path.

The APK uses the persistent local development signer. It updates other APKs
signed with that local key; replacing a release-key installation requires
uninstalling first, which deletes app-private files. Back up anything needed.
The Arch image pin remains `2026.11.15.1-rc.56`.

## Experimental compatibility boundary

The adapter reads the same archive UID/GID/mode records as PRoot, including
stat/fstat/statx and descriptor-relative queries. It maintains ordinary
user-created files/directories, chmod, file/directory renames (including
RENAME_NOREPLACE), and deletion. Directory renames move the corresponding
metadata subtree, needed by Cargo registry/incremental state.

Set-id execution, ownership changes, hardlinks and unsupported rename flags
are refused explicitly. Use PRoot for sudo/pacman and other privileged or
unsupported operations. Cargo can use its copy fallback when hardlinking is
unavailable; this emits Rust incremental-cache warnings and can add copying
overhead during actual rebuilds. This is not yet complete permission/identity emulation parity:
metadata adapter behavior is deliberately bounded. The initial comparison
included no metadata cache; the exp.31 and exp.32 sections below describe the
subsequent filesystem optimizations.

## Validation

Run `python3 scripts/test-chroot-ng-runtime.py --guest-cc /path/to/aarch64-linux-gcc`
for the packaged ARM64 metadata and unsupported-operation checks. QEMU does
not validate the Android seccomp/SELinux execution context; phone testing is
still required. The upstream runner preserves environment-dependent failures.
