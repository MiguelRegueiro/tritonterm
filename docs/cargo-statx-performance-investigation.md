# Cargo statx path investigation and optimization (exp.41–42)

## Phone evidence

The installed exp.41 command was run in `~/elio`, after an unprofiled warmup:

```fish
cargo build --offline
time env TRITONTERM_CNG_PROFILE=timing cargo build --offline
```

The warmup reported 3.75 seconds. The profiled no-op build reported 0.92
seconds, with 1.03 seconds external Fish wall time. The successful final
Cargo scope reported 967.130 ms wall, 316.868 ms user CPU and 597.926 ms
system CPU. It exited zero. Earlier short `exec_failed` records reported
ENOENT during command lookup; they must not be mistaken for the successful
Cargo execution's counters. An earlier same-PID `next_exec` scope lasted
40.351 ms and is separate from the final Cargo scope.

| Successful Cargo scope | Count | Accumulated time |
| --- | ---: | ---: |
| Intercepted dispatch | 15,652 | 810.748 ms |
| Full path resolver | 8,181 | 268.827 ms |
| Descriptor pinning | 15,348 | 165.087 ms |
| Metadata lookup | 8,862 | 109.963 ms |
| Path component probes | 56,068 | count only |
| Metadata open attempts | 7,714 | count only |
| Validated metadata cache hits | 1,147 | count only |
| ELF mapping | 2 | 0.970 ms |
| Image cache fetch | 2 | 0.625 ms |

Timers are nested and may overlap across threads; do not add these spans or
interpret their sum as exclusive wall time. `metadata_opens` is incremented
before `openat`, so it counts attempts, including errors; it does not prove
7,714 files were successfully opened and read.

The syscall histogram contains 6,984 statx calls (AArch64 number 291), 2,280
openat calls, 3,544 reads and 2,117 closes. The final Cargo loader accepted
two cached images with zero fallbacks, copied 81,920 bytes, and scanned zero
executable bytes. Executable loading is no longer the demonstrated large
cost for this no-op build.

## Code finding

`src/monitor/dispatch.c:stat_plain_absolute` already handles ordinary
canonical absolute rootfs paths for newfstatat. It translates the filesystem
view and uses a pinned kernel lstat. A nonsymlink result is reused for the
ordinary stat answer, preserving ownership/mode fixups and guest copyout.

The statx branch does not use that shortcut. It first fully resolves the
path and calls `cng_l2s_statx_probe`. The probe's `l2s_target` performs a pinned
lstat to classify the object. For an ordinary file the dispatcher then issues
the real statx, pinning its parent again. This duplicates traversal/pinning
for normal paths and runs on a syscall that dominates Cargo's histogram.
The intercepted histogram alone does not distinguish absolute from relative
path arguments, so the phone's precise eligible-call fraction is unknown.

## Controlled operation-count comparison

A standalone ARM64 C fixture issues three passes over 512 ordinary files.
It compares direct newfstatat and statx calls with absolute and relative
paths, each with metadata enabled and disabled. Every case validates regular
file type and size; metadata-enabled cases also assert virtual uid 123,
gid 456 and mode 0640. Statx requests BASIC_STATS and BTIME and checks the
required returned mask bits. No runtime or APK code is modified.

The deeper fixture uses Cargo-like paths:
`/home/triton/.cargo/registry/src/index.fixture/crateN/src/fileN`.

| 1,536 requests, metadata enabled | Full resolves | Pins | Component probes |
| --- | ---: | ---: | ---: |
| Absolute newfstatat | 2 | 1,539 | 8 |
| Absolute statx | 1,538 | 3,075 | 12,296 |
| Relative newfstatat | 1,538 | 1,539 | 3,080 |
| Relative statx | 1,538 | 3,075 | 3,080 |

The small extra counts include fixture startup and chdir. Disabling metadata
retains those resolver/pin/probe counts, establishing that this duplicated
path work is independent of the ownership sidecar cache. Metadata cache hit
counts depend on the direct-mapped table's collisions for the temporary
absolute paths and are not used to claim a stable performance delta.

All eight deeper cases and eight shallower cases completed with status zero
and unchanged checked attributes. These are current-runtime comparisons of
two syscall paths, not before/after measurements of an implemented change.
QEMU elapsed time is deliberately not reported as a phone speedup estimate.

Saved reproducible fixtures, detailed logs and JSON summaries are under
`.tools/cargo-statx-investigation/` (local investigation artifacts). Run the
saved `tritonterm-investigate-statx-deep.py` with Python to regenerate the
comparison using the existing ARM64 compiler and packaged exp.41 runtime.
The fixture needs no downloads, Android device, root access or new APK.

An existing ARM64 Rust 1.98 smoke project was also inspected. Its attempted
rebuild failed because the disposable fixture has no `cc` linker configured;
that failed build is not used as a no-op Cargo benchmark or performance
comparison. The installed phone result above is the actual Cargo evidence.

## Recommended next change

Prioritize a statx path for canonical absolute ordinary rootfs files that
obtains and reuses the real kernel statx answer under the existing pinning
rules. Keep the requested mask, synchronization flags, timestamps, filesystem
view validation and metadata/credential fixups. A returned TYPE field must
establish that the leaf is ordinary before publishing the shortcut result.
Missing type information and unsupported candidate conditions use the
established resolver. Avoid cross-operation path caches for this first step.

Retain existing resolution for symlinks, emulated hardlinks, proc/dev paths,
binds, noncanonical paths, empty-path descriptor calls and invalid arguments.
Check parent/symlink replacement races, chroot view changes, metadata changes
from another process, returned masks, invalid flags/buffers and statx refusal
with libc fallback. Confirm actual operation-count reductions, then rerun
package/metadata compatibility checks and repeat the same phone Cargo build.
Relative-path duplication can be addressed separately after its invariants
are established.

A broader ownership cache remains a later candidate. The phone open-attempt
counter does not distinguish unique records, repeated misses and successful
reads. Caching absent sidecars by time alone would risk hiding new ownership
or permission records from PRoot/pacman. The narrower statx change has a
clearer demonstrated source of redundant work and retains the current
metadata freshness model.

No phone action beyond the supplied Cargo profile is needed to select this
next implementation. No speedup magnitude or native-equivalent Cargo timing
is established yet. The initial investigation used the unchanged exp.41 runtime. The exp.42
implementation and validation follow below.


## Exp.42 implementation

The existing `stat_plain_absolute` helper now handles real statx as well as
newfstatat answers. It uses the same canonical-rootfs eligibility rules and
pinned-parent validation. Statx passes its requested mask and synchronization
flags to `cng_pin_statx`, adding NOFOLLOW for safe leaf classification. Only
a successful kernel answer that includes TYPE and identifies a nonsymlink
can be published after filesystem-view sequence validation. It retains the
kernel's full statx result, then applies existing proc/identity/metadata
fixups and guarded guest-buffer copyout. No statx answer is fabricated from
an older stat structure.

An accepted ordinary candidate avoids both full component resolution and the
second parent pin/reissue. The original newfstatat shortcut retains its
behavior. Noneligible names, binds, symlinks/hardlink members, empty paths,
unknown flags and unsuccessful candidates retain established resolution.
No path or metadata state is cached across operations, no new syscall is
introduced, and neither sidecar format nor Arch image changes.

### Targeted validation

New fixtures are `scripts/test-chroot-ng-statx-path.py`,
`tests/chroot_ng_statx_guest.c`, `tests/chroot_ng_statx_candidate.c` and
`tests/chroot_ng_statx_filter.c`. The end-to-end guest checks native-oracle
inode, size, blocks, block size, device IDs, attributes and timestamps,
including birth time when returned, across five masks and six flag
combinations. Ownership/mode overlays, symlinks, trailing/noncanonical paths,
binds, hardlink identity/count, proc/dev nodes, descriptor-relative and
empty-path calls, invalid flags/masks/descriptors and bad output buffers
retain their expected behavior. The same correctness workload passes with
the unchanged exp.41 runtime.

A kernel EPERM filter verifies real refusal and a working ordinary-stat
fallback. The standalone fixture compiles the actual candidate helper with
test-only pin/view substitutes to check ENOSYS, EACCES, omitted TYPE,
symlink results and view changes. QEMU synthesizes a statx response through
fstatat on host ENOSYS, so a host ENOSYS filter cannot prove guest ENOSYS
propagation; this was observed with the baseline too. See the
[QEMU statx implementation](https://github.com/qemu/qemu/blob/master/linux-user/syscall.c).
These test substitutes are not included in the runtime or APK.

A same-process request sees an externally replaced inode and changed metadata.
A chroot change switches to the new root. A concurrent parent directory /
absolute outside-symlink swap exposes no outside inode during 2,500 statx
requests. Existing legacy-stat containment checks also pass.

| 1,536 deep absolute statx requests | Exp.41 | Exp.42 |
| --- | ---: | ---: |
| Component probes | 12,290 | 2 |
| Pins | 3,074 | 1,538 |
| Intercepted guest statx calls | 1,536 | 1,536 |
| Checked kernel fields and metadata | correct | correct |

Counter differences from the initial standalone investigation reflect fixture
startup/chdir differences. QEMU wall times are not used as phone latency
estimates. The implementation's operation counts improve for eligible calls;
the eligible fraction of the phone Cargo workload still needs measurement.

```sh
python3 -B scripts/test-chroot-ng-statx-path.py \
  --guest-cc /path/to/aarch64-linux-gcc \
  --baseline-runtime /path/to/exp41-libchroot-ng.so
```

The baseline argument is optional. Detailed result:
`/tmp/tritonterm-exp42-statx-final.log`. Existing stat, metadata, profile and
startup checks passed; broader cache/package/compatibility results are
recorded below after completion.

### Phone comparison

Install exp.42 over exp.41, restart the app and open a new chroot-ng shell.
The same signing certificate, application ID, Arch pin and both backends are
retained. No Arch rebuild or data migration is needed. Run:

```fish
cd ~/elio
time cargo build --offline
time cargo build --offline
time env TRITONTERM_CNG_PROFILE=timing cargo build --offline
```

Compare the successful final Cargo scope with the exp.41 scope above:
component probes, resolver calls, pins, statx count, metadata attempts and
external wall times. Keep loader image hits and fallbacks visible to avoid
mistaking a cache miss for a filesystem regression. An unchanged no-op build
must remain fresh, and source edits must still trigger compilation. The
existing Fish profile collector can also check repository-prompt effects.


### Packaged artifact and focused checks

Exp.42 uses application ID `com.tritonterm.android`, version code 202612042
and the same local development certificate as exp.41 (SHA-256
`00c98cf2d371f58e55cb6d0ce269ab5fdf4f4d03f2f42eb84eda16458385bf1d`).
The verified APK contains the tested runtime SHA-256
`d417d51a7faa2a814b4dd9530add9a995e56280e629e21bb13bc6da571db58f3`.
Both native hash manifests match. PRoot, pr-cli, the PRoot loader and Fish
collector match exp.41 byte-for-byte; the launcher still opts into image
caching. APK SHA-256:
`fb80afd78362e8d47f9f2e19650d3eced8f681704dd35f457cf0430a5f637a9c`.

Focused statx, legacy stat, metadata, profile and startup tests passed.
The complete executable-image cache regression passed, including mutation,
concurrency, bounded eviction, kernel refusal and failure suppression.
Actual Arch sudo/pacman install, upgrade and removal passed with metadata,
links and package hooks intact. No test fault hooks enter the APK.

Logs:
`/tmp/tritonterm-exp42-{statx-final,stat-path,runtime,profile,startup-filter,image-cache,packages}.log`.
The build/signature/native packaging log is
`/tmp/tritonterm-exp42-build.log`.


### Broad compatibility result

The cache-enabled broad run completed with **1,407 passes, four failures and
38 skips** (`/tmp/tritonterm-exp42-upstream.log`). Two assertions are the
previously documented 60-mapping lazy-rewriter failure, independently
reproduced on unchanged exp.36/37 in
[the earlier investigation](repository-stat-performance-investigation.md#validation).
Two are the known QEMU openat2 high-bit reference discrepancies. These
remain failures; the suite is not reported as entirely passing. No assertion
was weakened or skipped to make this optimization pass.

The concurrent fork/link stress check passed in this run. That result does
not resolve the intermittent exp.41 timeout recorded in
[the executable-cache validation](hybrid-image-cache.md). The subsequent
phone comparison is recorded below.


## Exp.42 phone result

The phone completed two unprofiled no-op builds followed by the same timed
Cargo profile on exp.42. Cargo reported 0.76, 0.70 and 0.74 seconds; external
Fish wall times were 896.44, 808.47 and 847.37 ms respectively.

| Final successful Cargo scope | Exp.41 | Exp.42 |
| --- | ---: | ---: |
| Profiled external wall | 1,030 ms (rounded) | 847.37 ms |
| Scope wall | 967.130 ms | 782.739 ms |
| User / system CPU | 316.868 / 597.926 ms | 291.102 / 486.730 ms |
| Intercepted statx calls | 6,984 | 6,984 |
| Component probes | 56,068 | 30,850 |
| Full resolver calls | 8,181 | 4,071 |
| Pins | 15,348 | 12,016 |
| Metadata lookups | 8,862 | 8,862 |
| Metadata open attempts / cache hits | 7,714 / 1,147 | 7,714 / 1,147 |
| Metadata accumulated time | 109.963 ms | 111.266 ms |
| Dispatch accumulated time | 810.748 ms | 623.016 ms |
| Image hits / fallbacks | 2 / 0 | 2 / 0 |

The syscall workload and metadata counters remain essentially unchanged,
while resolver calls fall about 50%, probes 45%, and pins 22%. These counts
confirm the shortcut is used on the phone. The compared samples show about
18% less external wall time, but were collected sequentially, not as a
randomized controlled benchmark. The result does not establish a stable
speedup across all workloads or native-equivalent performance. Timers remain
nested; their accumulated durations must not be added into wall time.

Source edits and normal package use should continue to be exercised during
manual use. Remaining resolution and metadata work are further targets;
metadata open attempts include failures and cannot be equated to successful
sidecar reads without more detailed measurement.
