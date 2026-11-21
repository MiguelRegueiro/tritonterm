# Cargo filesystem follow-up after exp.43

## Scope and existing phone evidence

Exp.43 fixes repeated-Enter responsiveness by moving Starship rendering off
Fish's input path. Its native libraries are identical to exp.42. That phone
confirmation is not a further Cargo speed measurement.

The latest successful Cargo scope with these native libraries is the exp.42
sample: 782.739 ms wall, 6,984 intercepted statx calls, 4,071 resolver calls,
12,016 pins, 30,850 component probes and 8,862 metadata lookups. Metadata
reported 7,714 open attempts and 1,147 validated cache hits. Image loading
accepted both cached objects without fallback.

The 623.016 ms accumulated dispatch span includes nested resolver, pinning,
metadata and other work. It is not an exclusive time partition; these spans
must not be added together. Existing counters do not identify statx pathname
shapes or distinguish successful metadata opens from ENOENT and other errors.

## Code inspection

`stat_plain_absolute` in `src/monitor/dispatch.c` accepts canonical absolute
ordinary rootfs names. It obtains the real pinned statx result, verifies TYPE
and the filesystem view, and reuses that result. Relative names do not enter
this shortcut.

The remaining statx path resolves the name and calls `cng_l2s_statx_probe`.
That probe reaches `l2s_target`, which performs a pinned ordinary stat to
classify the leaf. For an ordinary file the dispatcher subsequently reissues
the real statx and pins the parent again. This is duplicated work within one
operation, independent of metadata caching.

For a directory-fd-relative name, `cng_resolve_at` also obtains the directory's
host/guest spelling and resolves the resulting path. Even a single leaf can
therefore walk considerably more components than the same leaf relative to
the current working directory. This resolution has correctness duties:
binds, proc/dev overlays, symlinks, directory replacement and containment.
Replacing it with an unchecked path join or permanently cached directory name
would not preserve those duties.

The ownership adapter in `src/monitor/tritonterm.c` uses a 256-slot,
direct-mapped parsed-record cache. A positive hit validates the record's
inode, mode, size and nanosecond timestamps. Admission waits for a second
lookup. Failed opens are not admitted. The cache is process-local; a fresh
Cargo invocation does not inherit the previous invocation's parsed records.
Filesystem page/dentry caching can still warm up between invocations.

## Controlled measurements

A static ARM64 guest performs three passes of direct statx calls over 32 or
512 ordinary files in a Cargo-like directory. It checks type and size for
every answer and uid/gid/mode when a sidecar exists. Four spelling forms are
compared: canonical absolute, cwd-relative leaf, directory-fd-relative leaf,
and cwd-relative `crate/src/fileN`. All use the same packaged exp.42/43
runtime, with metadata enabled and link2symlink support enabled.

The fixture brackets its query loop with markers. Single-threaded QEMU syscall
traces inside those markers distinguish successful sidecar opens from errors;
profile totals below also include the small startup/chdir/open overhead.
Eighteen cases passed, including absent records and cache-disabled controls.

| 1,536 requests with existing records | Absolute | Cwd leaf | Dirfd leaf | Cwd nested |
| --- | ---: | ---: | ---: | ---: |
| Resolver calls, including startup | 3 | 1,539 | 1,539 | 1,539 |
| Pins, including startup | 1,540 | 3,076 | 3,076 | 3,076 |
| Component probes, including startup | 10 | 10 | 12,298 | 3,080 |
| Successful sidecar opens in query loop | 1,458 | 1,458 | 1,458 | 1,458 |
| Validated metadata hits | 78 | 78 | 78 | 78 |

There are exactly 1,536 additional pins in each relative case compared with
the absolute control. The 512-record working set also yields few cache hits.
The precise hit count depends on the temporary pathname's hash: an earlier
run produced 70 hits instead of 78. This is evidence of the cache's limited
fit for a sequential working set, not a stable phone cache-hit prediction.

For 32 records and 96 requests, the cache-enabled absolute run successfully
opens sidecars 64 times and validates 32 hits; disabling the cache opens them
96 times. For absent records, all 96 attempts fail in both modes. At 512
absent records, all 1,536 attempts fail. The same aggregate open-attempt count
can consequently represent very different workloads.

Raw trace totals contain QEMU's guest-memory-copy fallback through a scratch
memfd. Those extra pread/pwrite/fstat calls must not be attributed to Android:
`uaccess.c` selects process_vm or memfd according to runtime availability.
No QEMU elapsed time or total syscall count is presented as phone overhead.

Reproduction script, summaries and traces are ignored local artifacts under
`.tools/cargo-followup-investigation/`. Run:

```sh
python3 -B .tools/cargo-followup-investigation/investigate.py
```

The script uses the existing ARM64 compiler and QEMU; it needs no download,
device or root access. It creates and removes its own rootfs fixture. The
current runtime source and packaged binary are unchanged.

## Recommended implementation order

First remove the duplicate ordinary-leaf classification from the remaining
statx path. After established path resolution, obtain and reuse the real
pinned statx answer for eligible ordinary objects instead of pinned lstat
followed by another pinned statx. Retain the original mask/synchronization
semantics, TYPE requirement, filesystem-view checks, identity/metadata fixups,
invalid-pointer behavior and special-name/link fallbacks. This narrower change
can eliminate the second pin without also redesigning relative resolution.
It does not by itself remove the component probes in the table.

Next investigate avoiding unnecessary ancestor walks for descriptor-relative
ordinary files, anchored to the real directory descriptor/current directory.
This needs independent checks for directory renames/replacement, deleted
working directories, concurrent chdir/chroot, overlays and symlink races.
A path cache or lexical join alone is not a substitute for those guarantees.

Add aggregate opt-in profile counters alongside the next implementation:
absolute versus relative/dirfd statx paths, ordinary-result reuse, successful
metadata opens, absent records, other errors, cache admission/collision, and
chosen guest-memory-copy mechanism. Log no pathnames or contents. The profile
buffer/truncation contract must still accommodate the expanded record.
These counters determine how much of the actual Elio workload the proven
optimization covers, and whether metadata effort should focus on records,
missing-record checks or cache collisions.

Do not start with a much larger metadata cache or time-based negative cache.
Every cached absence must still observe a new record created by PRoot or a
package operation, and positive hits must observe in-place record edits.
Avoid introducing a shared-generation protocol or changing the metadata
format before establishing the phone workload and benefit. Existing stat
freshness, source-change rebuilds and sudo/pacman compatibility remain required.

## Phone action

No additional command on the current APK can reveal the missing internal
categories; repeating its existing profile would reproduce aggregate data.
No new phone action is needed to establish the duplicated pin identified here.
After implementing and host-testing the narrow statx reuse and counters, the
next APK should be checked with the same warmed Cargo command and optional
profiling. Wait for the completed prompt between runs so an in-flight async
Starship refresh is not an uncontrolled competing workload.

The current investigation adds this document and ignored local fixtures only.
It does not build an APK, change either backend, commit or push anything. No
claim is made that these changes will achieve the laptop's 0.1–0.2 second
Cargo time; that requires measurement on the phone.

## Exp.44 implementation and validation

The resolved-path candidate now reverse/forward-checks the canonical host
name against the same unchanged filesystem view, excludes binds, zones and
hidden link-store names, and uses one real pinned statx. A returned TYPE
classifies an ordinary leaf before its answer is reused. Missing/non-directory
errors can be reused under the same view checks too, avoiding a second probe
and, for missing names, the subsequent fallback path walk. Other refusals,
symlinks and omitted TYPE retain established handling. The original absolute
shortcut, metadata freshness model and Arch pin are preserved.

The explicit exp.43 baseline comparison covers 1,536 queries per workload:

| Workload | Exp.43 pins | Exp.44 pins | Exp.43 probes | Exp.44 probes |
| --- | ---: | ---: | ---: | ---: |
| Relative nested files | 3,075 | 1,539 | 3,080 | 3,080 |
| Directory-fd-relative files | 3,107 | 1,571 | 12,344 | 12,344 |
| Relative missing files | 3,075 | 1,539 | 7,688 | 3,080 |

Each comparison asserts the exact reduction and correct kernel fields,
metadata or ENOENT with the output buffer unchanged. These totals include
startup. No QEMU speedup is inferred.

Native-oracle comparisons exercise all five masks and six flag combinations
through absolute, cwd-relative and directory-fd-relative queries. The candidate
fixture checks refused syscalls, omitted TYPE, symlinks, excluded names and
filesystem view changes. Same-process external inode/metadata replacement and
concurrent parent-directory/symlink replacement checks cover all three path
forms. Existing metadata mutation/thread/fork, legacy stat and actual Arch
sudo/pacman install/upgrade/removal checks passed.

The expanded profile passed count/timing, scope reset, fork/nonleader exec,
failed exec and closed diagnostic-pipe checks. A standalone fixture fills all
49 metric pairs and the syscall histogram at maximum unsigned width; all
metrics survive and histogram truncation still produces valid JSON.
See [counter definitions](chroot-ng-profiling.md#filesystem-diagnostics-exp44).

The investigation's earlier no-implementation statement describes its initial
stage. Exp.44 changes the native runtime and adds the tests/counters described
here. The subsequent phone results and prompt-hook repair follow below.

### Broad result and packaged APK

The correctly configured cache-enabled QEMU run reports **1,409 passed,
two failed and 38 skipped**. The two failures are the existing openat2
high-bit reference discrepancies, previously documented in
[the backend validation](testing-chroot-ng.md#validation-and-limits). This is
not an entirely passing suite; the intermittent lazy-rewriter failures from
earlier runs did not occur here and are not claimed fixed.

The initial run without the explicit compiler sysroot reported 1,399 passed,
five failed and 39 skipped. Its three dynamic-execmap failures disappeared
when the ARM64 library sysroot was supplied; the two openat2 discrepancies
remained. No test assertion or skip condition was changed for that rerun.

Focused statx, legacy stat, metadata, profiling, executable-image cache,
startup-filter and actual sudo/pacman checks passed. App unit tests report
70 passed, one skipped, no failures/errors. Logs are
`/tmp/tritonterm-exp44-{statx,stat,metadata,profile,image-cache,startup-filter,packages,jvm}.log`
and `/tmp/tritonterm-exp44-upstream-sysroot.log`.

The signed artifact is `dist/tritonterm-2026.11.15.1-exp.44.apk`, version code
202612044, application ID `com.tritonterm.android`, pinned to the unchanged
Arch image 2026.11.15.1 / rc.56. The local signer SHA-256 remains
`00c98cf2d371f58e55cb6d0ce269ab5fdf4f4d03f2f42eb84eda16458385bf1d`.
Its engine exactly matches the tested runtime:
`f71eff9589d5281750987a1c60bbe6e0d01ff01af281159135798188f654d445`.
Every other native library and every source asset, including the async prompt
helper, matches exp.43. Generated Android dexopt assets change with the build.
APK SHA-256:
`4cc40fbf443d4bfb9d47ee4c2da382916f3b74ce5f7d78bf90316d72e84ab185`.

Install over exp.43 and start a new chroot-ng shell. In `~/elio`, run two
ordinary offline builds one at a time, waiting for the completed prompt
between them. Then collect:

```fish
time env TRITONTERM_CNG_PROFILE=timing cargo build --offline
```

Compare the final successful Cargo scope's new dispositions, pins, resolver
calls, metadata-open outcomes and image hits/fallbacks, plus unprofiled elapsed
times. A fresh executable cache miss must not be mistaken for a filesystem
regression. Exercise a normal source-change rebuild and package use as well.
The host operation reductions are established; the phone speedup is pending.

## Exp.44 phone evidence

The successful Elio scope at 10:55 (PID 5636) completed with status zero but
included an image-cache miss in both Cargo/Rustup stages. External wall was
5.19 seconds and the final scope's metadata span was 2.213 seconds. It is not
a warmed comparison with exp.42. A home-directory invocation that exited 101
because no Cargo.toml existed is not a build benchmark either.

At 11:03, after an ordinary build reported 3.22 seconds (3.41 external), the
next timed profile completed successfully with two image hits, no misses and
no fallbacks. Cargo reported 0.59 seconds; external Fish wall was 675.18 ms.

| Final successful warmed Cargo scope | Exp.42 | Exp.44 |
| --- | ---: | ---: |
| External wall | 847.37 ms | 675.18 ms |
| Scope wall | 782.739 ms | 620.278 ms |
| User / system CPU | 291.102 / 486.730 ms | 276.680 / 326.293 ms |
| Resolver calls | 4,071 | 3,386 |
| Component probes | 30,850 | 25,748 |
| Pins | 12,016 | 11,412 |
| Statx calls | 6,984 | 6,984 |
| Metadata open attempts / cache hits | 7,714 / 1,147 | 7,714 / 1,147 |
| Metadata accumulated time | 111.266 ms | 87.494 ms |
| Dispatch accumulated time | 623.016 ms | 473.510 ms |
| Image hits / misses / fallbacks | 2 / 0 / 0 | 2 / 0 / 0 |

The observed external wall reduction is about 20%. These samples were collected
at different times, not in randomized controlled A/B runs; do not promise a
stable 20% speedup or attribute every timing difference to the new code.
Operation counts establish less work. Nested timers are not additive.

The new categories refine the next target substantially: all 6,984 statx
requests are absolute (4,890) or empty-path descriptor queries (2,094), with
zero cwd-relative and directory-fd-relative nonempty names. The resolved
ordinary shortcut handles only six requests here. Reusing missing/non-directory
results handles 685, while 4,110 use the established absolute shortcut and
2,183 remain on fallback. Do not claim that optimizing relative-file queries
explains this Cargo workload's improvement. Error reuse is the important new
path in this sample.

Metadata outcomes are 7,332 successful opens, 382 ENOENTs and zero other open
errors. All 7,332 opened records parse. There are 5,136 collision observations
and 2,376 validated admissions; these are observations/publications, not unique
records or direct predictions of the gain from a larger table. Successful
record reads dominate missing-record probes. The actual phone uses process_vm
for 16,318 copy attempts and zero memfd attempts, confirming that QEMU's scratch
copy traffic is not the phone mechanism.

Investigate descriptor-stat fixups and positive-record cache behavior next.
Do not prioritize a time-based missing-record cache from this evidence.

## Exp.45 multiline prompt-hook repair

The same phone paste exposed a separate Fish postexec-hook bug. Command
substitution in `test -n (string trim -- "$argv")` splits a multiline command
into multiple arguments. Besides printing an error, it fails to advance the
prompt generation for that command. The fix uses a direct quiet regular-
expression check for nonwhitespace, without command substitution or flattening
the event contents.

A real-PTY bracketed-paste regression fails on the original helper and passes
on the repair. It checks that a multiline command advances generation exactly
once, retains its pipeline status, and emits no argument error. All 21 PTY
checks pass using both the installed Starship 1.26 initialization and the saved
1.27 initialization. Exp.45 retains the exact exp.44 native runtime; this is a
prompt-helper repair, not another native performance optimization.

App tests for exp.45 report 70 passed and one skipped. APK signature, package
identity and version code 202612045 were verified. All native libraries are
byte-identical to exp.44 and the repaired asset matches the tested source.
The signed in-place update is `dist/tritonterm-2026.11.15.1-exp.45.apk`, SHA-256
`18bd4c242d86b7c1b722a5fdbcf82ad09e0dac60b20dca5a0f0fcceacf781895`.
No repeat native compatibility run was needed for this Fish-only repair; the
exp.44 results above still describe its unchanged engine. The phone check is a multiline paste with a responsive
prompt and no postexec error; additional Cargo profiling is not required yet.
