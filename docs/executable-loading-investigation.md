# Executable loading after exp.37

The initial investigation below precedes the exp.38 implementation described
at the end. Scope is main executable and ELF
interpreter loading, separate from repository metadata and prompt modules.

## What the phone actually shows

The first exp.37 Elio version sample reports 36.91 ms in its measured scope,
33.96 ms to ELF/interpreter entry, and 32.44 ms in ELF mapping. Its rewrite
timer is 4.11 ms, nested within mapping. The loader copies 15,435,071 bytes;
both loaded objects use the anonymous strategy. This establishes that most
of this sample occurs before program entry. It does not establish that the
copy alone costs 32 ms: mapping includes copy/page allocation, scanning,
protection changes and instruction-cache synchronization.

Starship copies 8,324,523 bytes. Its version/minimal query is useful because
it avoids attributing Git status or timezone discovery to the loader. Warm
kernel file pages do not eliminate the anonymous destination allocations,
copying, scanning or cache maintenance repeated by this loader.

## Current source path

`src/loader/elf.c:map_anon` reserves anonymous RW memory, fills executable
segments with pread, scans code through `cng_rewrite_seg`, writes trampolines,
applies final protections and calls `cng_flush_icache` for executable ranges.
The data-mapping change from exp.34 already replaces eligible non-executable
segment copies with private file mappings. That optimization is active in
the saved phone profiles; simply applying it again cannot remove text copies.

`cng_g_loader_file` defaults to zero. `-F` selects a separate file-backed
strategy whose loader does not perform ahead-of-time rewriting. It is not
an equivalent fast toggle: it needs executable source-file mappings and can
move interception back to SIGSYS/lazy rewriting. AOSP policy denies execution
from app-data files for contemporary untrusted-app domains, including the
file execute permission used by executable mappings. Directly mapping the
Arch files RX is therefore not a generally available replacement for this
app's anonymous loading strategy.

Sources: [AOSP app-data execution restrictions](https://android.googlesource.com/platform/system/sepolicy/+/fa3eb773ce45c9c1a38a579f31799ffc00b85952/private/app_neverallows.te),
and local `src/loader/elf.c`, `src/monitor/execmap.c`, `rewrite.c` and `execve.c`.
The exact phone policy remains a device property; a host mapping result is
not proof of Android permission.

## Concrete cache-maintenance inefficiency

`src/rt/cache.S:cng_flush_icache` unconditionally issues DC CVAU on every data
cache line and IC IVAU on every instruction-cache line in the supplied range.
It reads CTR_EL0 for line sizes but ignores its IDC and DIC feature bits.
LLVM's AArch64 `__clear_cache` skips data-cache cleaning when IDC is advertised
and instruction-cache invalidation when DIC is advertised, retaining the
required barriers. Our routine can follow the same architectural conditions
without a new syscall, a loader cache or package-management changes.

Source: [LLVM compiler-rt clear_cache.c](https://github.com/llvm/llvm-project/blob/main/compiler-rt/lib/builtins/clear_cache.c).

For an 8.3 MB code range and hypothetical 64-byte cache lines, the current
two loops together issue about 260,000 cache-maintenance instructions, plus
loop instructions and barriers. Those are not memory-allocation syscalls.
Actual line sizes and advertised IDC/DIC on the phone have not been measured;
this is a concrete avoidable-work candidate, not a demonstrated phone gain.
Neither loop can simply be deleted on CPUs that require it.

## ELF layout and a larger architectural opportunity

Read-only ELF inspection of the local actual ARM64 binaries gives:

| Image | Executable bytes | Raw SVC words in code sections | Pages containing those words, 4 KB / 16 KB |
| --- | ---: | ---: | ---: |
| Starship | 8,149,744 | 73 | 20 / 13 |
| ld-linux-aarch64.so.1 | 174,779 | 56 | 12 / 6 |

Their executable bytes total exactly 8,324,523, matching the phone's copy
counter. Raw SVC words are candidates, not the number actually rewritten:
the selector excludes signal-return and stack-teardown sites. The phone
reports 129 rewritten sites for Starship; the local forced-anonymous profile
also rewrites libraries, so its site total is not a main/interpreter-only
comparison. Matched byte totals do not prove the phone binaries identical.
Page counts use segment virtual
addresses and include every raw candidate. Both inspected images have
file-offset/address geometry compatible with 4 KB and 16 KB mapping.

Most code pages do not need modification. An immutable, shared in-memory
image could be mapped privately, with only syscall-containing pages becoming
private copies. A validated site-offset index could avoid rescanning the full
code image. Trampoline addresses and branches must still be created for each
mapping; caching already-patched absolute process addresses is incorrect.

A bounded broker-owned memfd image cache is a plausible route: populate and
seal an original image once, hand out read-only descriptors, map privately,
patch private pages, synchronize modified code correctly, then enter it.
The runtime already has broker/SCM_RIGHTS and memfd-backed shared-memory
machinery, but no executable-image cache protocol or coherence model.

AOSP's app-domain rules grant executable tmpfs mappings, which makes this
worth a capability experiment. It does not guarantee that every phone allows
the exact sealed-memfd, private-write, RX-transition sequence. The initial
fill must also establish instruction coherence for shared untouched pages;
only later private modifications can use narrower synchronization. The
existing anonymous loader must remain the fallback.

Source: [AOSP app-domain tmpfs permissions](https://android.googlesource.com/platform/system/sepolicy/+/d3f08120bcc99f58d31c7f40f70086fea8a2295a/public/te_macros).

Correctness requirements include source identity and live replacement,
in-place modification during filling, package upgrades/downgrades, hardlink
and bind resolution, per-rootfs scope, runtime/rewriter versioning, ASLR,
4 KB/16 KB geometry, text relocation/self-modification, bounded memory use,
broker restart and unambiguous fallback. A path string or ELF build ID alone
is not an adequate cache key or freshness check. Avoid rehashing every byte
on every hit if that recreates the full-image read cost.

## Local experiments and their limits

Artifacts are under `/tmp/tritonterm-loader-investigation`:

* `elf-layout.json`: ELF segment sizes, raw SVC counts and virtual-page counts.
* `starship-version.trace`: successful forced-anonymous Starship 1.27.0 version
  output and individual main/interpreter preads of 8,149,744 and 174,779 bytes.
  The forced mode additionally copies shared libraries, so its total copy
  counter is not compared to the phone's total.
* `starship-native-library.trace`: correct version output with native library
  mappings but no complete exit profile, because QEMU has no live seccomp
  fallback. Rejected as an end-to-end profiling comparison.
* `memfd-probe.c`, its static PIE and `memfd-probe.log`: a standalone ARM64
  experiment seals an original image, confirms further writes fail, privately
  patches a mapping to return 43, and maps it again to return the original 42.
  RW-to-RX transitions and execution passed under QEMU. The reported QEMU CPU
  has IDC=0 and DIC=0. This tests basic mapping/private-copy semantics only;
  it neither implements the runtime cache nor validates Android policy or
  hardware cache coherence.

## Recommended order

First split opt-in loader diagnostics into copy/fill, rewrite, protection and
instruction-cache synchronization phases, and report the advertised cache
features. Implement feature-aware synchronization following the standard
sequence, with coverage for both required-maintenance and advertised-skip
paths. Retain the anonymous loader and all conservative behavior on CPUs
without those features. Measure on the phone before making a latency claim.

Next run the sealed-memfd/private-patch capability experiment under the app's
actual policy, isolated in a child so denial cannot kill the shell. A guest
PROT_EXEC mmap success is insufficient: execmap can silently provide an
anonymous fallback. Verify real backing and RX transition explicitly.
Only after that gate is supported should we implement a bounded shared-image
cache and validated site index, preserving package-update freshness.

This sequence targets the demonstrated loader cost while making the larger
architecture conditional on device evidence. Neither change requires an
Arch rebuild or a different package distribution model.

## exp.38 implementation

`src/rt/cache.S` now tests IDC/DIC before running the corresponding maintenance
loop, retaining the barriers used by the standard sequence. Empty/reversed
ranges do no work. The register is read on every call rather than cached
across possible CPU migration. The non-feature path retains both loops.

The loader has separate opt-in fill, protection and instruction-sync spans,
alongside existing rewrite timing. Profile reports include cache features and
line sizes. The phone helper collects a native sealed-memfd/private-patch
experiment before its timed workloads. It uses an isolated child and a
bounded wait; permission denial, SIGSYS and a stalled syscall are diagnostic
results rather than shell failures. Normal loading remains anonymous, with
the established file-backed fallback. No image cache is enabled yet.

`scripts/test-chroot-ng-loader.py` exercises all four synthetic IDC/DIC
combinations, unaligned line coverage, empty/reversed ranges, and 100 rounds
of modifying/executing code with real reported feature bits. Test-only loop
counters are compiled into a separate fixture, never into the runtime.
Both cortex-a53 and max QEMU CPU models pass. Actual Android cache coherence
and performance remain device validation requirements.

The native capability checks pass in control, errno denial, SIGSYS-trap denial,
and disabled-support cases, including activation through emulated exec. A
test-only host seccomp listener deliberately stalls the child's memfd call;
the parent times out, kills/reaps it, reports stage 14 and runs the guest
successfully. No fixture relaxes the guest's stdout/exit assertions.

Focused metadata, stat containment/freshness, hardlink enumeration, profiling,
Fish collection, startup regression and actual sudo/pacman install, upgrade
and removal checks passed. Logs are `/tmp/tritonterm-exp38-*.log`.
The phone should install exp.38 over exp.37, reopen the shell, select
chroot-ng and run the unchanged command `fish /tmp/tritonterm-profile.fish`
twice. The refreshed helper includes capability output and expanded timing
records. Compare timing/count/off overhead as before; no phone speedup is
claimed from QEMU results.

The broad suite finished with 1,407 passes, four failed assertions and 38
skips: the two known high-bit openat2 QEMU reference discrepancies and two
assertions in the intermittent lazy-rewriter check reproduced with previous
runtimes. No assertions were relaxed or failures presented as passes.
See `/tmp/tritonterm-exp38-upstream.log` and the exp.37 baseline reproduction
in `repository-stat-performance-investigation.md`.

The signed exp.38 APK contains the tested engine SHA-256
`6d36734f187554ed53f939943b92d5e9529529a7373e46ee23a95ee0bbc91eab`
and the updated Fish helper. PRoot, its loader and pr-cli match exp.37
byte-for-byte. The application ID remains `com.tritonterm.android`, version
code is `202612038`, and the local development certificate matches exp.37.
APK SHA-256:
`94620ec28c01fed91c307f3b92f10b456dc53fd84c01cb8b6dcb0238a32a5a35`.

## First exp.38 phone collection

Collection `runtime-profile.ZcOwoD` completed with live seccomp and rewriting.
CTR_EL0 is 2487533572: IDC=1, DIC=0, with 64-byte data and instruction lines.
The optimized routine therefore skips data-cache cleaning on this phone;
instruction-cache invalidation remains required.

The isolated loader experiment reported stage 6, error -13, signal 0. The
kernel denied the RW-to-RX transition for the privately patched sealed-memfd
mapping. The command and subsequent collection continued normally. This
rejects the exact mapping sequence tested, not every possible executable
shared-memory strategy. No executable-image cache should be enabled from
this result. A future narrow experiment should distinguish unchanged RX
mapping from RX after private modification before choosing an architecture.

The retained Elio sample measured 21.29 ms in scope, 18.35 ms to entry and
17.39 ms in ELF mapping. Its child phase spans were 9.88 ms fill, 4.04 ms
rewrite, 1.47 ms protection and 1.88 ms synchronization. Copying remains a
material loader cost. Starship varied substantially: its minimal sample's
fill/rewrite spans were 20.01/14.28 ms, while its clock sample's were
5.51/3.17 ms. These are separate executions and overlapping instrumentation
scopes, not a stable elapsed-time partition or a controlled speedup estimate.

Unprofiled repository times were 352.01, 135.82 and 136.83 ms. Warm runs are
below the first exp.37 collection's 148.11 and 163.27 ms, but the measurements
are sequential and uncontrolled. The collection demonstrates active feature
handling and a safely reported mapping denial, not a substantial proven
overall latency improvement.
