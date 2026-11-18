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

## Validation and limits

Local `/tmp` log paths below identify historical investigation artifacts; they
may no longer exist after a host restart. The checked-in scripts reproduce the
relevant checks.

The upstream test suite runs on the laptop through qemu-user. This exercises
ARM64 instruction rewriting/ELF loading, not Android SELinux or live guest
seccomp traps. Hardware/app-policy behavior must be validated from the app;
`run-as` is not an equivalent test environment.

The raw GNU-libc QEMU run reports 1409 passes, two failures and 38 skips. Both
failures are the same reference discrepancy: QEMU 10.2 accepts bit 40 in
openat2 flags while the native host kernel and chroot-ng's emulation reject it
with EINVAL. An independent native build of the upstream reference confirms
the real-kernel result. The upstream tests remain unchanged. With openat2
made unavailable in the QEMU host process, the upstream suite's existing
unavailable-kernel branch tests the emulated behavior; its 50 openat2 checks
pass. The full controlled run reports 1370 passes, four failures and 37 skips:
its four failures assume inherited NoNewPrivs=0, whereas the host filter needs
NoNewPrivs=1. These environment-dependent failures remain visible; neither
full-suite run is reported as entirely passing.

Reproduce the raw run with an ARM64 GNU/Linux compiler and its sysroot:

```sh
GUESTCC=/path/to/aarch64-linux-gcc CNG_SYSROOT=/path/to/sysroot \
    ./scripts/test-chroot-ng-upstream.sh
python3 scripts/test-chroot-ng-runtime.py --guest-cc /path/to/aarch64-linux-gcc
```

The second test uses the exact packaged binary, verifies archive metadata,
new-file mutations and directory-tree moves, verifies explicit unsupported
operations, and checks unchanged upstream behavior with the adapter disabled.
No additional rootfs download occurs during these tests by default. Set
`TRITONTERM_TEST_OPENAT2_UNAVAILABLE=1` for the controlled host-filter run
(with the NoNewPrivs assertion limitations described above).

A separate ARM64 Rust 1.98/Cargo smoke fixture compiled an offline,
dependency-free program and executed it successfully, including a thread and
a source-change rebuild. However, repeated rebuilds were **not reliable**:
rust-lld sometimes reported guest paths (including existing CRT files) missing.
Serial linker operation did not eliminate the failure. This is an unresolved
compatibility result, not a passing end-to-end Cargo test. QEMU reported that
the seccomp monitor could not be installed; forced anonymous executable mappings
were used for this fixture, but rewriting can still leave sites for the missing
SIGSYS fallback. Whether the same failure occurs with Android's actual seccomp
monitor remains unverified. Do not treat this APK as a validated replacement
for PRoot yet. Local logs: `/tmp/tritonterm-cng-rust-smoke.log`,
`/tmp/tritonterm-cng-rust-smoke-retry.log` and
`/tmp/tritonterm-cng-rust-smoke-serial.log`.

App unit tests succeeded (71 tests, one existing skip), and pr-cli tests
succeeded (348 passed, one ignored). The metadata-adapter tests above passed.
APK signing and all packaged native binary hashes were verified. No physical
Android device was available; no phone performance improvement is asserted.

## exp.31 filesystem optimizations

Both backends remain selectable. This build changes only the experimental
chroot-ng runtime; the PRoot binary, Arch image and metadata format stay the
same. It can update exp.30 using the same persistent local development signer,
without reinstalling Arch. An existing `arch_runtime` selection is preserved.

Ordinary openat operations now pass their already-translated host path to the
metadata permission/creation hook instead of resolving the guest path a second
time. openat2 retains its previous hook, including its existing scoped behavior.
No symlink/path cache is introduced, so rename, bind and symlink visibility
continues through the existing path resolver.

Parsed metadata records use a bounded 256-slot process-local cache. Every hit
validates the record pathname's device/inode, permissions, size, mtime and ctime
(including nanoseconds) against the kernel. Atomic replacement, in-place edits,
removal and updates by PRoot remain visible without a new metadata format or
cross-backend generation protocol. Records are admitted only after a second
lookup; first-time reads keep the original open/read/close syscall count. Cache
population checks the descriptor before and after reading so a concurrent edit
cannot seed a persistent mixed record. Failed/missing records are not cached.
Contention uses a nonblocking try-lock and falls back to an ordinary read.

The native-runtime test override `TRITONTERM_METADATA_CACHE=0` disables this
cache for A/B checks. It is not an app config setting. The launcher does not
forward arbitrary host variables to the runtime.

The ARM64 QEMU tests verify live external replacement (with preserved mtime),
in-place edits (also with preserved mtime), deletion/recreation, rejected
metadata symlinks, colliding hash slots, concurrent threads and fork, with the
cache enabled and disabled. Existing creation/chmod/rename/stat/descriptor and
unsupported-operation tests also remain enabled.

Operation counts, not phone timings:

* 500 repeated stat requests opened the metadata record 500 times with the
  cache disabled and twice with it enabled; subsequent hits still validate it.
* Against the exact exp.30 native binary, 100 repeated opens issued 1159
  filesystem syscalls in the measured categories (openat/read/close/fstat/
  newfstatat/readlinkat). Path reuse alone reduced this to 959; adding the cache
  reduced it to 765, about 34% fewer. This fixture enables link2symlink, matching
  the app's launcher. It does not predict the speedup of a full Cargo project.

Validation of exp.31: the app unit-test run reports 71 tests, zero failures or
errors, and one existing skip. The full upstream QEMU suite matches exp.30:
1409 passed, the same two openat2 reference discrepancies, and 38 skipped.
All new metadata correctness, collision/thread/fork and syscall-count tests
passed. A real ARM64 Cargo 1.98 fixture completed a build, two unchanged builds
and execution of its threaded binary. Earlier QEMU linker failures are not
claimed fixed by this change; Android Elio testing remains the relevant
end-to-end comparison. Local logs are `/tmp/tritonterm-cng-opt-tests.log`,
`/tmp/tritonterm-cng-opt-upstream.log`, `/tmp/tritonterm-cng-opt-jvm.log` and
`/tmp/tritonterm-cng-opt-cargo.log`.

To reproduce the operation-count comparison, retain the previous native binary
and pass `--baseline-runtime /path/to/exp30-libchroot-ng.so` to
`scripts/test-chroot-ng-runtime.py` alongside `--guest-cc`. Without the optional
baseline, all correctness and repeated-record count checks still run.

## Phone comparison

After setup, run `time cargo build --offline` twice in the existing test project
once dependencies have been obtained. Compare the same project/build state
under both `arch_runtime` settings; changing source or dependencies changes the
workload. Also try a real rebuild and the interactive programs/prompt. The old
`measure-proot.py` helper measures a PRoot tracer and cannot profile this
in-process engine. Report startup errors rather than treating an engine that
did not start as a performance result.

## exp.32 startup and stat reuse

The phone's exp.31 prompt comparison isolated the configured prompt: a literal
Fish prompt and clean Fish/Bash stayed responsive. Minimal Starship took about
23–25 ms warmed, the configured prompt 53–57 ms at home and 118–130 ms in Elio.
The laptop comparison in `/home/regueiro/elio-fm/elio` was about 2.2 ms minimal
and 21–27 ms configured. These are different machines/builds, not a controlled
estimate of compatibility-layer overhead.

This iteration keeps the Starship configuration and both backends. It removes
more duplicated work from chroot-ng:

* Ordinary-object stat requests reuse the pinned kernel lstat already performed
  by hardlink compatibility checking. Actual symlinks and emulated-hardlink
  backing files retain their existing paths. Unknown fstatat flags still reach
  the kernel for validation. statx reuses only the resolved path and still
  issues the requested kernel statx with its original mask/sync flags.
* Descriptor stat handling shares one live `/proc/self/fd/N` target between the
  metadata and hardlink adapters. Descriptor numbers/targets are not cached
  across operations, and deleted/unreadable targets retain the fallback.
* The executable-library mapping fallback reads its bounded ELF program-header
  table once, then uses it in both passes. It replaces per-header reads and
  adds a table-offset overflow check; it does not persist or cache ELF contents.

The expanded metadata test checks statx by fd, symlink nofollow, invalid stat
flags/masks, trailing-slash errors, metadata changes, thread/fork collisions
and normal file/directory mutations. With the exp.31 binary supplied through
`--stat-baseline-runtime`, the measured filesystem syscall categories fell from
6065 to 3565 for 500 path-stat requests, and from 2572 to 2072 for 500 fd-stat
requests. These fixture counts do not establish a phone timing improvement.

For startup investigation, an unmodified ARM64 Starship and its libraries were
extracted from the existing local Arch image 2026.11.14.16. The fixture retains
archive ownership records and uses the phone's Europe/Madrid TZ setting. It
shows timezone-directory traversal during clock initialization. Starship's
[time module](https://github.com/starship/starship/blob/master/src/modules/time.rs)
uses local timezone initialization. That is a lead for the phone's expensive
clock, not a complete profile of its currently installed package.

Reproduce a validated QEMU syscall-count comparison with:

```sh
python3 scripts/profile-chroot-ng-starship.py \
    --rootfs /path/to/provisioned-arm64-rootfs \
    --baseline-runtime /path/to/exp31-libchroot-ng.so \
    --logs /tmp/starship-profile
```

This downloads nothing. It checks the actual prompt output and rejects QEMU
runs whose missing seccomp fallback left config-path accesses untranslated.
In validated runs, minimal/clock prompts each dropped from 58 individual
program-header reads to zero; three bulk reads replace them. Total pread64
counts were 156 to 101 (minimal), and 224 to 169 (clock). QEMU elapsed times
are intentionally not used to predict phone performance.

Final exp.32 validation: all expanded metadata/collision/thread/fork tests
passed, and three unchanged ARM64 Cargo builds plus the threaded test program
passed in the final run. A separate rebuild attempt encountered the previously
observed QEMU linker path failure; this is still an incomplete replacement for
a native Android Cargo regression run. The full upstream suite reports 1409
passes, the same two openat2 reference discrepancies, and 38 skips. App unit
checks remain successful (71 tests, one existing skip). The APK uses the same
local development certificate as exp.31, retains its rootfs pin and backend
selection, and all packaged runtime hashes were verified. PRoot/pr-cli/loader
bytes are unchanged from exp.31. No phone speedup or Enter fix is asserted.
Logs: `/tmp/tritonterm-cng-startup-tests.log`,
`/tmp/tritonterm-cng-startup-upstream.log`,
`/tmp/tritonterm-cng-startup-cargo-final.log`,
`/tmp/tritonterm-cng-startup-cargo.log`, and
`/tmp/tritonterm-cng-startup-jvm.log`.

## exp.32 phone follow-up (2026-11-18)

The user reported no meaningful perceived improvement over exp.31 and elected
to retain the changes. A subsequent unchanged Cargo build sequence reported
3.84 s, 749.16 ms and 752.02 ms wall time (Cargo: 3.64 s, 0.64 s and 0.64 s).
These sequential runs do not establish a controlled speedup. The prompt
backlog remains unresolved.

Bare `time elio` measurements ranged from about 0.88 to 1.65 s on the phone
and 0.16 to 0.25 s on the laptop. They include the interactive application's
entire lifetime and do not isolate startup or first-frame latency. Future
comparison should verify the resolved executable/build and distinguish a
noninteractive version query from instrumented initialization to first frame.
