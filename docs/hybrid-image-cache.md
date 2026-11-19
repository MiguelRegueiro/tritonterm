# Hybrid executable-image cache (exp.40–41)

The exp.39 phone probe permits direct sealed RX mappings and hybrid shared
code with anonymous patch pages, while rejecting the private-write/RX path.
Exp.40 implements that hybrid path for eligible main programs and ELF
interpreters. Shared-library mapping retains the established execmap behavior.

## Loading and cache ownership

The existing IPC broker owns sealed virtual ELF images and raw syscall-site
offsets. Each image has the same zero-filled PT_LOAD layout as the anonymous
loader. The broker copies segments once, indexes executable bytes, establishes
instruction coherence, drops its writable mapping, seals the object, and
keeps the original sealed descriptor plus the opened source descriptor. It does
not cache guest paths, fake credentials or relocated trampoline addresses.

Clients map unchanged text RX. Only pages containing syscall candidates are
replaced by anonymous RW copies before rewriting and changing them to RX.
The indexed rewriter uses the same context/section filters and trampoline
builder as the ordinary scan, retaining signal-return and stack-teardown
exclusions. Instruction synchronization covers patch pages and trampolines;
the broker synchronizes original code once before publishing its sealed image.
Data mappings remain private and retain zero-filled BSS. Trampoline addresses
are generated for each process mapping, preserving ASLR.

The cache has eight LRU slots, a 128 MiB total virtual-image byte budget, a
64 MiB per-image limit, and at most 8,192 indexed candidates. These limits
apply per existing broker namespace. The existing namespace rules and
10-second unanchored idle shutdown remain unchanged: cached images alone
do not keep a broker alive indefinitely. Eviction closes broker descriptors;
already mapped guest images remain valid through their kernel references.
The IPC protocol namespace advanced from v6 to v7 for the new request;
exp.41 advances it to v8 so clients cannot join an older cache daemon.

## Freshness and compatibility

Every lookup passes an already opened source descriptor, not a path key. The
broker validates kernel identity, size, mode and mtime/ctime including
nanoseconds. Holding a source descriptor prevents inode recycling from
matching an old entry. The client compares the cached ELF header and complete
program-header table against its own validated plan, and rechecks source
identity after transfer. Descriptor stat uses the existing Android-safe
newfstatat/AT_EMPTY_PATH loader mechanism.

Timestamp validation is supplemented by kernel inode watches, installed
before header reads. Modification, attribute, close-write, move, deletion
and unmount events evict entries. Watch loss, queue overflow or excessive
queued events discard cache state and return to anonymous loading. No
reusable entry is retained without a live watch. Source changes during image
construction prevent publication. Sequential in-place writes and atomic
replacement are observed; modifications racing an execution can select a
validated snapshot as with the existing copying loader.

Overlapping PT_LOAD pages, writable-executable segments, text relocations,
large/unsupported layouts and any cache/mapping failure retain the original
loader. A failed hybrid reservation is unmapped and its descriptors closed
before fallback. Metadata sidecars, sudo/set-ID handling, package installation,
rootfs layout, PRoot availability and package-distribution pins are unchanged.

Deliberate runtime modification of previously shared executable pages is a
remaining compatibility limit: Android can deny a later RX transition after
private writes, as the phone probe demonstrated. Declared text relocations
and writable-executable ELF segments are excluded up front; unusual programs
that modify otherwise read-only text can use the command-scoped cache bypass.
Anonymous JIT allocations retain the existing path. Loading-time fallback
does not claim to repair every later protection change inside a program.

The launcher opts in with `CNG_IMAGE_CACHE=1`. A guest command can disable it
for itself and its descendants with `TRITONTERM_CNG_IMAGE_CACHE=0`; a guest
cannot enable a cache disabled by the launcher. Both variables are documented
in built-in help. Normal app startup does not run capability probes.

## Observability and measurements

New profile metrics are `image_hits`, `image_misses`, `image_fallbacks`,
`image_objects`, `image_patch_bytes` and the timed `image_fetch` span.
`rewrite_bytes` excludes indexed candidate processing; `rewrite` still times
that work and `rewrite_sites` counts actual patches. `copied_bytes` and
`image_patch_bytes` measure client copies, not the broker's cold image fill.
The enclosing loader timer includes request latency. Ineligible layouts skip
the cache without a fetch; fetch/mapping refusals count as fallbacks.

A validated real ARM64 Starship/Git repository fixture gave:

| Measurement | exp.39 | exp.40 cold | exp.40 warm |
| --- | ---: | ---: | ---: |
| Prompt output | `main\|M1U1` | `main\|M1U1` | `main\|M1U1` |
| Client copied bytes | 11,100,571 | 2,907,120 | 2,907,120 |
| Main/interpreter patch bytes | full executable segments | 131,072 | 131,072 |
| Rewriter scan bytes | 10,980,907 | 2,656,384 | 2,656,384 |
| Rewritten sites | 544 | 544 | 544 |
| Loader synchronization bytes | 8,366,384 | 170,288 | 170,288 |
| Image hits / misses | unavailable | 0 / 2 | 2 / 0 |

The fixture forces shared-library anonymous fallback for QEMU, adding the
same library copies/scans to all three rows. Main/interpreter executable
bytes are 8,324,523 in exp.39 versus 131,072 copied patch bytes in exp.40.
Cold misses still populate full immutable images in the broker; this is not
a claim of eliminating cold source reads. The comparison uses --shared-proc
to retain one existing namespace across separate host invocations. Normal
phone commands inherit their shell's session namespace. QEMU elapsed times
and scheduling-sensitive pipe-poll counts are not phone speedup estimates.

## Tests and phone comparison

`scripts/test-chroot-ng-image-cache.py` checks warm hits, command-scoped off,
matching rewrite/output, in-place same-size overwrite with restored mtime,
atomic replacement, actual inotify queue overflow and recovery, slot/byte
budget eviction and sparse BSS. A separate fixture compiles the real loader
with a test-only syscall gate to force file-mmap EACCES, checking descriptor
closure, reservation cleanup and successful anonymous execution. Host filters
also deny memfd creation, kill a cache broker with SIGSYS and deny watches;
commands retain their stdout/exit through fallback. None of those fault
hooks is compiled into the APK.

```sh
python3 -B scripts/test-chroot-ng-image-cache.py --guest-cc /path/to/aarch64-linux-gcc
```

Install exp.40 over exp.39, reopen the app and use a new chroot-ng shell. No
Arch rebuild, data migration or reinstallation is required. Run the unchanged
`fish /tmp/tritonterm-profile.fish` twice and compare image hits/misses,
copy/synchronization bytes and external wall times. Both cached and uncached
loads need warm measurements; package and source updates must still work.
For a command-scoped control:

```fish
env TRITONTERM_CNG_IMAGE_CACHE=0 cargo build --offline
env TRITONTERM_CNG_IMAGE_CACHE=1 cargo build --offline
```

Cold construction, broker eviction/restart and existing filesystem/metadata
work remain costs. The phone result is still needed before claiming a
latency improvement from this architecture.

The final cache-enabled broad run completed with 1,409 passes, two known
openat2 high-bit QEMU reference discrepancies and 38 skips. Focused loader,
profile, metadata, startup, collector and real sudo/pacman install/upgrade/
remove checks passed. The image fixture also verifies concurrent cold
requests publish one image and then reuse it. Android JVM unit tests completed
71 cases with no failures/errors and one skip. No test assertions were relaxed.

Relevant logs: `/tmp/tritonterm-exp40-image-cache-final.log`,
`/tmp/tritonterm-exp40-upstream-final.log`,
`/tmp/tritonterm-exp40-{profile,runtime,startup,packages}-final.log`,
`/tmp/tritonterm-exp40-helper.log` and `/tmp/tritonterm-exp40-jvm.log`.
Validated Starship profiles are in
`/tmp/tritonterm-repository-hybrid-{baseline,cold,warm}/profile.json`.

The verified local exp.40 APK uses version code 202612040 and the same
development certificate as exp.39. Its native engine matches the tested
SHA-256 `bee6a9213e6215142ce991d893a61dd19542c347b7d983954242ee340e590c51`;
the packaged launcher includes the cache opt-in. PRoot, its loader and pr-cli
match exp.39 byte-for-byte. APK SHA-256:
`00a791f7d24dbbe3a6c715d03f6d6228ae944853b10a30101bd0b813b1bc9d94`.


## Exp.40 phone failure and exp.41 correction

The phone collection `runtime-profile.RuyOrj` did not use cached images:
every sample recorded zero hits, misses and image objects, two fallbacks,
and unchanged executable copying. Failed image fetches consumed 14–29 ms
in the Starship samples and 24 ms in the Elio sample. These overlapping
spans are not whole-command deltas. The independent direct/read/hybrid probes
still succeeded; private-patch returned EACCES at stage 6. The host reductions
above therefore did not establish a phone improvement.

Exp.40 reopened its sealed memfd through `/proc/self/fd` as O_RDONLY before
publishing it. This extra open was absent from the successful phone probe.
Android can authorize use, reading and mapping of an existing appdomain tmpfs
file without authorizing reopening it: the AOSP `app_domain` macro grants
`execute getattr map read write` without `open` for `appdomain_tmpfs`.
[Primary policy source](https://android.googlesource.com/platform/system/sepolicy/+/d6924e587e45f64658448c8575bb45e95cf0bf72/public/te_macros).
This is a plausible phone failure mechanism, not confirmation of the phone's
specific policy decision.

Exp.41 retains the original memfd after installing WRITE/GROW/SHRINK/SEAL
seals and transfers that descriptor. O_RDWR does not permit writes or writable
shared aliases after sealing. The received source descriptor is retained on
a successful miss; hits and failures leave it for the broker caller to close.
A deterministic ARM64 regression denies descriptor reopening: the exp.40
source fails publication, while exp.41 publishes/reuses an image, executes its
RX code and rejects pwrite, truncation and a writable shared mapping. Test
fault injection is not included in the production runtime.

Profiles now include `image_cache` with `enabled`, `blocked`, `stage` and
negative `error` (Linux errno). Persistent platform refusals disable attempts
for the current process lineage; this state survives emulated exec and fork.
Initial-shell failures therefore avoid repeated fetch overhead in ordinary
commands. Per-file layout changes, watch refusal and resource pressure do not
latch a global failure. Explicit guest `TRITONTERM_CNG_IMAGE_CACHE=1` resets
the latch and retries when the launcher opted in. Guest `=0` remains a bypass.
No persistent app-wide failure cache is written.

Failure stages:

| Stage | Operation |
| --- | --- |
| 1 | Broker transport, missing response/descriptor |
| 2 | Client source stat |
| 4–9 | Response length, metadata read/content/header/index, final source stat |
| 10–13 | Broker source stat, watch queue, inotify initialization, watch creation |
| 14–18 | ELF plan, bounds, memfd creation, length, writable image mapping |
| 19–23 | Source fill, index bounds, concurrent mutation, coherence fault, seals |
| 26 | Cache budget allocation |
| 30–35 | Reservation, file map, anonymous patch map, patch read, pool RX, segment protection |

A coherence fault at stage 22 terminates the broker and is reported to the
client as transport stage 1. Unused legacy stages 24–25 represented the
removed reopen and source duplication. Platform errors EPERM/EACCES/ENOSYS
at stages 12/16/18/23/31 latch failure; transport stage 1 also latches.
Diagnostic fields report the last failure, so a later successful object in
the same exec does not erase evidence of an earlier per-file fallback.

Install exp.41 over the existing local build, restart the app and create a
new chroot-ng shell. Existing Arch data and PRoot remain available. First run:

```fish
env TRITONTERM_CNG_IMAGE_CACHE=1 TRITONTERM_CNG_PROFILE=timing /usr/bin/true
```

Then run `fish /tmp/tritonterm-profile.fish` twice. Actual success requires
nonzero `image_objects` and subsequent `image_hits`, plus reduced client
copies. If unavailable, the stage/error now identifies the failed operation;
zero hits alone must not be treated as a successful cache optimization.


Exp.41 focused checks passed for cache reuse/invalidation/concurrency/bounds,
sealed-descriptor publication under denied reopen, mmap fallback cleanup,
loader probes, profile JSON in cached and uncached modes, metadata, startup
under openat2 denial, the Fish collector, and actual Arch sudo/pacman install,
upgrade and removal. The filter fixture distinguishes TRAP (translated to
ENOSYS by the inherited handler) from KILL_PROCESS (actual broker transport
loss). Both preserve guest execution; the latter also exercises stage 1.
Platform-failure tests verify inherited commands make zero cache fetches and
explicit opt-in retries them.

Focused logs: `/tmp/tritonterm-exp41-image-cache-final.log`,
`/tmp/tritonterm-exp41-descriptor-baseline.log`,
`/tmp/tritonterm-exp41-{loader,profile,profile-uncached,runtime,startup-filter,packages,helper}.log`.
The signed APK's application ID and certificate match exp.40; version code
is 202612041. The runtime matches SHA-256
`2688cb14d73cb070ec8167f5b878cf77246f7aedf100a9cf342fdbefed27b8dd`.
PRoot, pr-cli and the PRoot loader are byte-identical to exp.40. APK SHA-256:
`90f11b06a3641daf914601f8fb003e909213e1daabc92f9e8fd310219693bf02`.


The exp.41 broad cache-enabled run finished with **1,408 passes, three failures
and 38 skips** (`/tmp/tritonterm-exp41-upstream.log`). Two failures are the
previously observed QEMU openat2 high-bit reference discrepancies. The third
is an M10 concurrent fork/link stress timeout: a child remained orphaned with
an output pipe open after the test timeout. Its exact PID/command was checked
before cleanup, allowing the suite to continue. No assertion was weakened.
The isolated fixture subsequently passed with exp.40 caching and exp.41 both
cached and uncached (`/tmp/tritonterm-exp41-l2sfork-comparison-final.log`).
A passing rerun does not erase the broad-run timeout or establish its cause.

Eight additional isolated exp.40 runs also passed
(`/tmp/tritonterm-exp41-l2sfork-baseline-repeat.log`). The full-suite timeout
could not be reproduced in isolation and remains unresolved; it is not
classified as a proven baseline or QEMU-only failure. Exp.41 is a phone test
build with this validation limitation disclosed, not a claim of a fully
clean broad suite or a demonstrated phone speedup.


## Exp.41 phone validation

The phone collection `runtime-profile.yxxyII` confirms the hybrid cache is
active for every sampled workload: two image hits, two mapped objects,
zero misses/fallbacks, and diagnostic stage/error zero. The same 120 Elio
and 129 Starship syscall sites are rewritten without rescanning executable
bytes (`rewrite_bytes=0`). Starship client copies fall from 8,324,523 to
131,072 bytes; Elio copies fall from 15,435,071 to 208,896 bytes.

| External wall time | Exp.40 warmed samples | Exp.41 warmed samples |
| --- | --- | --- |
| Elio version | 51.05–51.18 ms | 7.19–7.43 ms |
| Minimal Starship | 41.32–51.51 ms | 13.29–17.75 ms |
| Repository prompt | 148.16–151.20 ms | 92.71–94.06 ms |

These are separate phone collections, not randomized paired benchmarks.
The actual hits, copy reductions and zero fallbacks establish that the cache
works on this phone; they do not identify an SELinux audit denial by themselves.
Cold/outlier samples remain, including a 383.43 ms repository prompt.

Cargo's subsequent offline no-op builds took 3.73 seconds initially and
0.89/0.90 seconds warmed (external wall 3.95/0.994/1.03 seconds). Earlier best
warmed Cargo measurements were around 0.64 seconds, so no Cargo improvement
is established by this collection. The repository prompt still records
2,721 metadata lookups, 2,492 metadata opens and 10,764 path probes. Profile
Cargo directly before selecting the next filesystem/metadata optimization;
threaded timing spans overlap and must not be added into wall time.
