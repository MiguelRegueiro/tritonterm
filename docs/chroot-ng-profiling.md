# Phone execution profiling

This instrumentation build collects evidence; it does not introduce another
performance optimization. Install the locally signed exp.33 APK over exp.32,
keep `arch_runtime chroot-ng`, and reopen the app. Arch does not need reinstalling.

Run this one command inside the guest:

```fish
fish /tmp/tritonterm-profile.fish
```

The app copies this helper into its existing guest `/tmp` binding on launch.
It uses Fish and the installed Elio/Starship packages; no Python, sudo, package
installation, ADB, rootfs migration or permanent prompt changes are needed.
It executes the installed binaries directly rather than Fish aliases/functions.
The executable paths appear in the summary for comparison.

The helper measures Elio `--version`, minimal Starship, a clock-only Starship,
and the normal Starship configuration at home and in `~/elio` when that directory
exists. It rotates off/count/timing mode order over three repetitions to reduce
ordering bias. Cache warmth and CPU scheduling remain variables; this is not a
controlled cold-start benchmark.

Paste the section between `BEGIN TRITONTERM PROFILE` and `END TRITONTERM PROFILE`.
All stdout/stderr samples and the summary remain in the private directory printed
at the end, under `~/.cache/tritonterm/runtime-profile.*`. Only one timing-mode
exit sample per workload is printed to keep the paste manageable. The saved
samples contain the rest, including counter-only records. Abnormal/nonzero
exits, missing reports, unexpected modes, truncated records or wrong minimal/
clock output, or output changes across modes (apart from clock text), stop
collection with an error; missing evidence is not a fast result.

## Runtime switch and scope

The app sets host `CNG_PROFILE_SUPPORT=1`, which makes per-exec profiling
available and includes normal process exit in the seccomp fallback. With the
guest environment variable unset or `off`, profiling stays disabled. Normal
usage does not produce reports, sample timers, report files or per-operation
clock calls. The diagnostic-capable build still has the extra disabled checks
and environment scan at exec; off is not identical machine code to exp.32.

For a single direct command, independent of the helper:

```fish
env TRITONTERM_CNG_PROFILE=count elio --version
env TRITONTERM_CNG_PROFILE=timing elio --version
```

Modes are `off`, `count` and `timing`. They are selected from the runtime's
validated guest exec-environment snapshot. `count` records operations without
per-section clocks; both enabled modes take wall/CPU snapshots and emit one
JSON line to stderr for a normal process exit. `timing` also times instrumented
sections. The helper uses clean child Fish shells so Fish's own `time` output
can be captured, while the timer measures the target rather than shell startup.
It shows all three external wall measurements per mode to expose overhead.
Do not assume enabled measurements are unperturbed baseline performance.

A profiled exec request ends the prior image's scope with `next_exec`; a failed
load gets `exec_failed` with its negative errno and leaves behavior/errno
unchanged. Counters restart for the new request. A fork without exec does not
report a duplicate of its parent's counters. Threads share atomic counters;
the planning/commit thread contributes during startup, and all threads may
contribute after entry. Startup attribution follows a nonleader exec handoff.
Report I/O is best effort; a closed stderr pipe does not turn the command
into SIGPIPE, and any existing signal mask/pending SIGPIPE is preserved.
Process termination by a fatal signal/kill need not produce a complete record.
An exec failure ends that request's scope rather than restoring the old scope.

## What the report means

Native lines begin with `TRITONTERM_CNG_PROFILE ` and contain JSON schema v1.
The report includes only a sanitized executable basename, PID, scope/mode,
exit/error status, clocks and counters. It does not log arguments, environment
contents, full paths or file contents. The collector saves the benchmark's
normal output separately in its private report directory.

* `wall_ns`: elapsed time since profile activation after the exec claim and
  validated argument snapshot, until exit reporting begins. It excludes shell
  fork/launch, argument snapshotting, report formatting and stderr I/O. Initial
  native launches (`initial_scope=1`) start earlier, after guest environment
  construction; helper targets use emulated exec (`initial_scope=0`).
* `to_entry_ns`: time to the monitor's ELF/interpreter entry preparation. It is
  not time to C/Rust main or Elio's first frame. Dynamic interpreter/library
  initialization continues after that point.
* `user_ns` and `system_ns`: process-self CPU differences from `getrusage`;
  they do not include subprocess CPU. `cpu_available=0` means unavailable.
* `metrics`: pairs of `[count,nanoseconds]`. Timers cover resolution, directory
  pinning, metadata lookup, ELF planning/headers, ELF mapping, rewriting,
  executable-library mappings, stack building, close-on-exec scanning,
  exec reset/address-space sweeping, retired-generation cleanup and dispatch. Counters include metadata file
  opens/cache hits, core component readlink probes, file versus anonymous
  mappings, copied bytes and candidate rewrite bytes/sites. Loader-object
  strategy counters are separate from library-segment mapping counters.
* `syscalls`: AArch64 syscall-number counts for calls seen by the dispatcher,
  not all native kernel calls or the separate clone/exec handling paths.
  `tiers` separates rewritten, SIGSYS-handler and bounced dispatch calls.
* `seccomp` and `rewrite`: whether the fallback monitor installed and rewriting
  is enabled. QEMU cannot validate the live Android seccomp/SELinux context.
  `seccomp=0` can mean incomplete interception of unrewritten instructions.

Timers overlap/nest and accumulate across threads: do not add them into a wall
or CPU partition. For example, ELF mapping includes rewriting, and dispatch
includes resolver/metadata work. Planning/header API calls can nest as well.
Counters are observed live rather than a stop-the-world snapshot. Diagnostic
clock/counter overhead and normal application work are not fully attributed.
Concurrent exec requests and raw kernel calls outside the monitor are not a
complete tracing model; the intended workloads are short noninteractive queries.

### Loader diagnostics in exp.38

Reports add a `cache` object with numeric `ctr_el0`, `idc`, `dic`, `dline`
and `iline` fields. This is the kernel-visible register at report time; the
cache synchronization routine reads it independently on each invocation.
New metrics retain the existing `[count,nanoseconds]` representation:

* `loader_fill`: ELF segment preads into anonymous memory, including page
  faults/copying within those reads; header reads are outside this metric.
* `loader_protect`: the loader's protection changes, including trampoline
  protections and the existing conditional execmem geometry probe.
* `loader_sync`: instruction synchronization requests, including trampolines.
* `loader_sync_bytes`: requested synchronization bytes; its second value is
  zero. A skipped maintenance loop still contributes requested bytes.

Rewrite scanning retains its existing `rewrite` timer. These timings are not
a complete partition: initial mapping/allocation, header handling and other
work remain outside the new spans, and existing enclosing timers overlap.
Count mode records events without phase clocks; off mode emits nothing.

The Fish helper also invokes `true` with guest environment variable
`TRITONTERM_CNG_LOADER_PROBE=1` in a separate untimed command. Launcher
`CNG_PROFILE_SUPPORT=1` is required. A native child attempts sealed memfd
creation, a private writable mapping patched to executable code, RW-to-RX
transition and execution, then verifies a fresh mapping retains the original
code. Raw monitor gate syscalls bypass execmap's anonymous fallback. This is
only a capability diagnostic, not an executable-image cache.

The JSON prefix is `TRITONTERM_CNG_LOADER_PROBE`; `stage=9,error=0,signal=0`
indicates success. Failure stages are documented in `cng/loader_probe.h`.
Stage 15 reports a child signal, and stage 14 reports a timeout. The parent
polls for about two seconds and kills/reaps an unfinished child. Native Linux
uses a quiet clone child; qemu-user's EINVAL fallback uses ordinary fork, as
the existing broker does. Denial is reported without changing command output
or exit status. A missing report is retained as unavailable by the collector.
Nothing runs by default on normal commands or shell startup.

### Expanded mapping probes in exp.39

The same opt-in variable now emits four `TRITONTERM_CNG_LOADER_PROBE` JSON
records with `v=2` and a `strategy` field. Each strategy gets its own native
child, so denial, a signal or a timeout cannot prevent later strategies from
being tested. The collector prints all records and still accepts the old
single v1 report. Existing workload profile records remain schema v1.

| Strategy | Experiment |
| --- | --- |
| `private_patch` | File mapping RW, private code modification, then RX; repeats the exp.38 failure point. |
| `direct_rx` | Unmodified sealed file mapped RX directly, without a writable mapping. |
| `read_rx` | Unmodified sealed file mapped read-only, then changed to RX. |
| `hybrid` | Shared RX code branches into a page replaced with anonymous patched code, while an unchanged shared function and the sealed source retain their original values. |

Every successful strategy reports stage 9 and verifies the sealed source
bytes remain unchanged. Stage 5 is a file-mapping failure; stage 6 is the
file mapping's RX transition. For hybrid, stages 17/18 identify anonymous
replacement/RX, and 19/20 identify shared-entry/cross-mapping execution.
The full stage table is in `cng/loader_probe.h`. Isolation and the approximately
two-second timeout apply **per strategy** (up to about eight seconds for four
stalled children). No mapping cache or guest-loading change is enabled.

### Hybrid image loading in exp.40

The app launcher now enables the [bounded hybrid image cache](hybrid-image-cache.md).
New metrics report image hits, misses, fallbacks, loaded objects, copied patch
bytes and timed image fetching. Candidate-index rewriting contributes to
`rewrite` and `rewrite_sites` without a full-image `rewrite_bytes` scan.
Client copy counters exclude broker image construction on cold misses; check
hit/miss counts and external durations before making a latency claim.
Guest `TRITONTERM_CNG_IMAGE_CACHE=0` provides a command-scoped control while
the launcher's cache stays enabled for other commands.

## Host checks

```sh
python3 scripts/test-chroot-ng-profile.py --guest-cc /path/to/aarch64-linux-gcc
python3 scripts/test-phone-profile-helper.py
python3 scripts/test-chroot-ng-runtime.py --guest-cc /path/to/aarch64-linux-gcc
```

The native checks compare stdout/exit status with profiling off and enabled,
exercise threads, fork, exec activation/reset/off, nonleader exec and preserved
failed-exec errno, and parse the emitted JSON. The portable collector tests
exercise the entire Fish flow against controlled commands and reject missing or
truncated records. The existing upstream QEMU suite retains its known capability
limitations; hardware performance decisions come from the phone report.

For exp.33, the dedicated profiling, collector and metadata checks passed,
as did the Android JVM tests (71 tests, one existing skip). APK native-library
hashes and the local development signature were verified.

The final broad QEMU run recorded 1406 passed, 5 failed and 38 skipped. Two
failures are the known high-bit `openat2` reference discrepancies. Two assertions
in the lazy mapping test failed intermittently; the same test also failed on
the unchanged exp.32 runtime. The remaining failure was a timeout in the forced
hardlink-emulation fork stress test. Isolated runs with the suite's library
bindings reproduced that timeout on both exp.32 and exp.33, while other runs
passed. These remain unresolved host-suite limitations/failures, not passing
checks, and this build has not been validated on an Android device.

## Phone results for exp.33

Two completed phone collections confirmed live seccomp and rewriting. Elio
`--version` copied 15,947,119 bytes per launch; the retained timing samples
spent 24.6 ms and 37.8 ms mapping the main executable/interpreter, with about
2.5 ms from entry preparation to exit. Those individual samples are not medians.

Minimal Starship made 40–44 pin calls. Clock-only Starship made 2,988, taking
17–18 ms in the pin timer. Repository samples recorded about 2,700 metadata
lookups, 2,500 metadata file opens and 15,500 path probes. Repository timers
overlap across threads and cannot be added into elapsed-time partitions.
The reports identify loading and repeated filesystem work as optimization
targets; they do not establish the benefit of a proposed implementation.

Both collections completed despite the adapter's unsupported-metadata warning.
The warning does not identify a syscall/path. The collector invokes chmod on
its private report directory; set-ID execution, ownership changes and root
mutations also reach explicit rejection paths in the current adapter. Full
sudo/package-management support remains separate compatibility work.


### Executable-image cache diagnostics (exp.41)

The additive `image_cache` object reports `enabled`, `blocked`, `stage` and
negative `error`. A platform refusal can suppress future cache requests in
the inherited process lineage; explicit `TRITONTERM_CNG_IMAGE_CACHE=1`
retries it when the launcher permits caching. `=0` bypasses the cache.
Counters remain separate: `image_hits`/`image_misses` count accepted broker
images, `image_objects` counts completed hybrid mappings, and `image_fetch`
includes unsuccessful RPCs. Zero objects with unchanged `copied_bytes` is
fallback, not a speedup. See [failure stages and the exp.40 phone result](hybrid-image-cache.md#exp40-phone-failure-and-exp41-correction).

### Filesystem diagnostics (exp.44)

The statx path counters classify intercepted requests as `statx_absolute`,
`statx_cwd` (relative to AT_FDCWD), `statx_dirfd`, or `statx_empty` (including a
null pathname). The matching disposition is `statx_reuse_abs`,
`statx_reuse_resolved`, `statx_reuse_error` (ENOENT/ENOTDIR), or `statx_fallback`.
Both category sums count visits to the statx handler. In ordinary successful
workloads they match the intercepted statx count; an early argument-copy
failure can precede the handler, and fake-root DAC retries can visit it more
than once. Fallback includes emulated links and errors, not only expensive
successful calls. These counters do not log names.

`metadata_open_ok`, `metadata_absent` (ENOENT), and `metadata_open_error`
partition `metadata_opens` attempts. `metadata_parsed` counts successfully
parsed records, excluding validated cache hits. `metadata_collision` counts
lookups finding a different nonempty slot key; `metadata_admission` counts
successful publication of a validated positive record. These are operation
counts, not unique files, exclusive times, or proof of a cache-size benefit.

`copy_pvm` and `copy_memfd` count copyin/copyout attempts using their selected
mechanism. A memfd attempt can fall through to the prior probe/copy fallback;
it is not a count of successful descriptor transfers. QEMU's fallback must
not be used to infer which mechanism Android selects.

The report buffer now accommodates all metrics at maximum integer width.
The syscall histogram can still set `truncated:1`; the resulting record must
remain complete JSON. Tests exercise this boundary. Normal commands with
profiling disabled still produce no diagnostics.
