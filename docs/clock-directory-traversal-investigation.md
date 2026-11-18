# Starship clock directory traversal

Investigation after the exp.35 checkpoint commits: `66cd7ab`, `b9d6f6c`,
`4e4bb1c` and `163b6e1`. This investigation changes no app/runtime behavior.

## Cache scope and the actual discovery work

The local ARM64 Arch Starship binary contains source paths for Jiff 0.2.38.
The matching source is available in the host Cargo registry. Its timezone
database uses a process-local OnceLock in `src/tz/db/mod.rs`. On Linux,
`TimeZoneDatabase::from_env` chooses the zoneinfo backend first. Constructing
that backend calls `ZoneInfoNames::new`, which calls `walk(dir)` immediately
to build a case-insensitive name index. `TimeZone::try_system` initializes
this database before consulting the system timezone cache/environment.

Thus the fixture performs a zoneinfo directory walk even with `TZ=UTC` or
`TZ=:/usr/share/zoneinfo/Europe/Madrid`. These cases were run with the actual
ARM64 binary and validated clock output. An incorrect QEMU output was rejected
and excluded from the syscall summaries. Cache lifetime is one Starship
process, and the shell launches a fresh process for each prompt.

The app already obtains Android's timezone ID on shell launch and passes TZ
through pr-cli. Caching that ID across app sessions would not preserve Jiff's
in-process index across independent Starship launches. Correct handling must
also account for Android timezone changes and guest tzdata package updates;
caching a fixed UTC offset would lose transition rules.

## The compatibility layer amplifies directory enumeration

The first local fixture omitted archive hardlink entries. A second fixture
materialized 760 hardlink aliases from the existing Arch archive in the same
PRoot-compatible indirection/count format recognized by chroot-ng. A control
copy then replaced all 1,093 resulting emulated members with ordinary files,
preserving the timezone names, file contents and ownership/mode records. This
control changes hardlink identity semantics and is suitable only for this
read-only timezone workload, not as a proposed rootfs migration.

Validated clock-only runs of the same runtime/binary/config gave:

| Zoneinfo representation | Filesystem calls in QEMU trace | Profile pin calls | Guest getdents64 calls |
| --- | ---: | ---: | ---: |
| Complete names/data as ordinary files | 1,049 | 71 | 44 |
| Complete names/data as emulated hardlinks | 12,000 | 2,989 | 44 |

The phone reported about 2,987 pin calls for clock-only Starship, a close
match to the emulated-hardlink fixture. This is strong evidence for the same
mechanism, though the currently installed phone binary/files were not fetched
or independently compared byte-for-byte. QEMU timing is not a phone speedup
estimate. Small unrelated counts vary with config/temp paths and runtime work.

`cng_l2s_dirent` in `src/monitor/l2s.c` reads each candidate symlink target,
then probes it. A PRoot member targets an indirection symlink rather than the
regular backing inode, so that probe falls into path-based `cng_l2s_stat`.
That path repeats member stat/readlink and group-chain validation to synthesize
the inode/type used by readdir. Jiff itself intends its walk to use directory
entry types rather than issue a stat for every file; the compatibility work
adds those repeated host operations below the guest syscall boundary.

## Recommended next implementation target

Optimize the PRoot-format hardlink enumeration path before adding persistent
timezone caches. Reuse the already-read member target, avoid requesting full
stat/link-count information when readdir only needs inode/type, and evaluate
bounded reuse within one directory-read operation for members of a group.
Preserve containment checks, ordinary-symlink behavior, stale/moved groups,
concurrent link/unlink/rename visibility and the PRoot metadata format. Do not
persist descriptor/path results across operations without a validation model.

A future Jiff improvement could make exact-name lookup lazy and retain full
indexing for case-insensitive lookup/enumeration. That is a library/package
change with a wider maintenance scope. The immediate chroot-ng target also
benefits other directory-scanning programs using emulated hardlinks.

Investigation logs and disposable fixtures:

* `/tmp/tritonterm-clock-utc` and `/tmp/tritonterm-clock-absolute`
* `/tmp/tritonterm-clock-linked/summary.json`
* `/tmp/tritonterm-clock-regular/summary.json`
* `/tmp/tritonterm-clock-counts.log`
* `/tmp/tritonterm-clock-regular-counts.log`
* `.tools/chroot-ng-clock-links` and `.tools/chroot-ng-clock-regular`

Only investigation notes are left uncommitted after the checkpoint. The
pre-existing nested oonid-pr worktree remains untouched.

## exp.36 implementation and validation

The implementation optimizes the existing PRoot-group dirent path in
`third_party/fake-chroot-ng/src/monitor/l2s.c`. It reuses the already-read member
symlink target, pins the indirection's parent once, validates the second-level
name with the existing group grammar, and obtains the backing inode/type
relative to that same descriptor with AT_SYMLINK_NOFOLLOW. In the common case,
readlink supplies the indirection type check without a separate lstat. Numeric
names retain the disambiguation required by the older one-level CNG format.
Malformed, missing, moved and interrupted groups retain the previous full
resolution/healing path. The pinned-parent requirement is explicit.

No cache survives an entry or directory operation. There is no app timezone
cache, narrower timezone database, changed timezone offset, new syscall, or
metadata-format migration in this change. The optimization removes duplicated
work while retaining the established directory-pinning primitives, including
the exp.35 startup repair.

The same validated full timezone fixture gave:

| Measurement | exp.35 | exp.36 |
| --- | ---: | ---: |
| Clock filesystem calls in QEMU trace | 12,000 | 3,239 |
| Clock profile pin calls | 2,989 | 436 |
| Guest directory-read calls | 44 | 44 |
| Minimal prompt filesystem calls | 411 | 413 |

These are approximately 73% fewer filesystem calls and 85% fewer pins for this
clock workload. The small difference in minimal-prompt counts is outside the
optimized hardlink path. QEMU elapsed times are not used to predict phone
latency; executable loading and repository metadata costs remain.

`scripts/test-chroot-ng-dirent.py` constructs its own fixtures without a rootfs
archive or downloads. Its comparison of 256 aliases across three listings
reduced pins from 6,150 to 774. It validates names, inode/type, both hardlink
formats, numeric names, ordinary symlinks, FIFO backing, malformed/missing/
outside targets, stale store/member paths, small buffers, rewind and descriptor
reuse. The same guest and directory descriptor remain alive across external
inode replacement, count change/unlink, and poisoned-indirection changes.
A concurrent parent-directory/symlink replacement test rejects outside inodes.
The checks also passed with openat2 forced to ENOSYS (6,151 to 775 pins).

```sh
python3 scripts/test-chroot-ng-dirent.py \
  --guest-cc /path/to/aarch64-linux-gcc \
  --baseline-runtime /path/to/exp35-libchroot-ng.so
```

Native profiling, metadata and real sudo/pacman install–upgrade–remove checks
passed. The startup-filter check still reproduces exp.34's SIGSYS and passes
for the new runtime. Applying the host SIGSYS filter to the entire listing
fixture stalls under QEMU in both unchanged exp.35 and exp.36, including a
small two-alias comparison with the same timeout. That is an unresolved host
filter/emulator limitation, not a passing listing check. Android validation
still requires the phone.

Relevant logs are `/tmp/tritonterm-exp36-dirent.log`,
`/tmp/tritonterm-exp36-dirent-errno.log`,
`/tmp/tritonterm-exp36-clock-traces/summary.json`,
`/tmp/tritonterm-exp36-clock-counts.log`,
`/tmp/tritonterm-exp36-metadata.log`, `/tmp/tritonterm-exp36-profile.log`,
`/tmp/tritonterm-exp36-packages.log`, `/tmp/tritonterm-exp36-startup.log` and
`/tmp/tritonterm-exp36-upstream.log`.

The final exp.36 broad runtime run completed with 1,409 passes, the same two
known high-bit openat2 QEMU reference discrepancies, and 38 skips. No tests
were weakened or skipped to produce that result. The installed phone should
be compared using the unchanged `fish /tmp/tritonterm-profile.fish` helper;
clock pin counts, clock/home wall times and repository timings remain the
useful measures. Both backends and the installed Arch rootfs are preserved.

## Phone confirmation for exp.36

Two phone collections completed with live seccomp and rewriting. Clock-only
Starship made 432 pin calls in both, compared with about 2,987 in exp.35.
The saved pin timers fell from about 22.6–22.7 ms to 4.52–4.68 ms. The second
collection's unprofiled clock runs were 39.80, 37.96 and 36.97 ms, versus the
previous second collection's 65.28, 63.31 and 68.01 ms. These are sequential,
uncontrolled phone observations; the operation counts provide stronger
attribution than a precise speedup percentage from wall times.

The second collection's configured home prompt took 59.71, 35.06 and 33.70 ms.
The repository prompt still took 132.58, 126.38 and 112.32 ms, with roughly
2,500 metadata-file opens and 15,500 path probes. Repository timers overlap
across threads and are not an elapsed-time partition. Elio loading remains a
separate bottleneck. These reports confirm the directory-listing improvement,
not complete elimination of prompt or launch overhead.
