# Terminal input and prompt latency investigation

This note records the current evidence about delayed repeated Enter presses and
choppy interaction in TritonTerm. It is a handoff for future investigation; no
performance fix has been established yet.

## Latest-build follow-up

The user confirmed that the delayed Enter and Cargo behavior persists with the
latest performance APK. An unchanged `cargo run` reported approximately 3–10
seconds before `Running`. The later prompt durations include time spent in the
interactive program and cannot be used as launch-time measurements. Plain-I/O
microbenchmark improvements have not established a fix for this workload.

Yazi also exposed a separate ownership defect: its cache under the host-bound
`/tmp` appeared owned by the Android app UID instead of guest UID 1000. USERLAND
`stat` returned early for bound paths, bypassing the existing guest ownership
mapping. The fix keeps metadata-sidecar handling confined to rootfs paths while
applying the normal UID/GID fallback to bindings. The regression test uses a
guest UID different from the host UID and checks stat, fstat, fstatat and statx,
including relative and descriptor-based queries. Permissions and file contents
remain unchanged. The old runtime fails this check and the updated runtime
passes with both tracing modes.

The visible `Gi=...` fragments are unsupported Kitty graphics APC payloads
escaping into screen text. The emulator now discards APC strings through their
terminator without buffering their payload. Tests cover chunk boundaries,
cancellation, reset and large payloads. This is protocol handling, not a claim
of graphics rendering support or a fix for prompt latency.

The remaining latency needs matching measurements of an offline no-change
Cargo build and direct executable startup inside the same installed guest.
These ownership/protocol fixes preserve the rootfs image and installed files.

The user then measured `cargo build --offline` at 7.22 seconds (Cargo reported
7.08 seconds), with about 0.52 seconds user CPU and 0.99 seconds system CPU.
`./target/debug/elio --version` completed in 69.55 milliseconds. This locates
the demonstrated delay before executable launch, in Cargo's no-change build
work. These CPU figures do not include the parent PRoot tracer, and do not
alone distinguish tracing, storage and scheduling waits. They also do not
measure interactive TUI startup or explain Fish's Enter backlog.

Two bounded metadata optimizations have passed host tests without persistent caches:
successful native statx exits reuse the host path already translated at entry;
failed and synthesized syscall exits retain the original translation path.
Ownership-record reads use a small supplied stdio buffer and stop after parsing
the three fields, avoiding unnecessary default-buffer work and trailing EOF
scanning. Guest identity, modes and metadata layout remain unchanged.
The targeted statx tests exercise native success, native failure, SIGSYS,
synthesized exits, fd paths and errors against the actual handler. They cover
97.62% of its executable lines. The broader suite checks path/symlink/bind
semantics, permissions, Rust spawning and debugger attachment. Metadata timing
samples vary substantially with host load and are not a measured phone Cargo
speedup.

After exp.24 was published, the requested `strace -f -c cargo build --offline`
attempt failed: PRoot reported an unsupported `PTRACE_???` request and strace
counted only one failed `execve`. Cargo did not run under that trace, so its
summary contains no usable workload counts. The warning does not identify the
numeric request; the source's unsupported requests must not be treated as a
confirmed identification of this one. A subsequent normal `cargo run` reported
7.72 seconds before `Running`, so the demonstrated delay remains unresolved.

The next diagnostic is Cargo's built-in profiler:
`env CARGO_LOG_PROFILE=true cargo build --offline`. It writes a
`trace-*.json` file in the working directory without nesting a ptrace tracer.
The command was verified on host Cargo 1.99.1 with a fresh no-change build.
Cargo documents this mechanism in its
[profiling guide](https://doc.crates.io/contrib/tests/profiling.html).
The trace can distinguish Cargo phases, but cannot by itself separate PRoot
CPU work from storage or scheduler waits inside those phases. Profiling can
also add overhead; its elapsed time is not a replacement performance baseline.

### Phone Cargo profiles

The user supplied three Cargo profiler JSON files in a local archive. They
contain identical counts of 70,747 events: 13,683 span entries, 13,683 span
exits, 43,380 instant events and one thread-name record. Entries/exits were
paired per thread; the sum of exclusive span durations matches the outer
`main` span. These are elapsed times inside instrumented Cargo, not CPU times.

| Phase | First run | Second run | Third run |
| --- | ---: | ---: | ---: |
| Entire Cargo `main` span | 8.928 s | 3.058 s | 2.465 s |
| Dependency resolution (`resolve`, 3 spans) | 1.653 s | 0.916 s | 0.626 s |
| Cached package loading (`download_accessible`) | 2.116 s | 0.866 s | 0.707 s |
| Fingerprint preparation (`prepare_target`, 358 spans) | 4.046 s | 0.644 s | 0.545 s |
| Job queue execution (`execute`) | 0.233 s | 0.245 s | 0.254 s |

Each run reads 282 manifests. Their total `read_manifest` span time is
0.770, 0.493 and 0.400 seconds respectively; this is nested inside other
phases and must not be added to the table's package-loading time. The
`download_accessible` name is Cargo's internal package-loading scope; this
offline run does not establish network downloads or a network timeout.

The single longest first-run fingerprint preparation span falls from 3.486
seconds to 0.485 and 0.405 seconds. Its 1,764 interior instant events come
from fingerprint calculation and filesystem freshness checking. Across all
fingerprint preparation spans, the decrease accounts for about 3.50 seconds
of the 6.46-second difference between first and third runs. No package names,
file paths or log arguments were captured, so the particular expensive
dependency cannot be identified from these files.

This narrows the observed Cargo delay to dependency loading/resolution and
existing-artifact freshness checks. It supports investigating PRoot's
path/metadata operations with a representative dependency-heavy workload;
the one-crate smoke benchmark is not representative of this project.
Repeated runs getting faster is consistent with warmed filesystem and
metadata caches, but the traces do not isolate that from CPU frequency,
scheduling, tracer overhead or profiler I/O. There is no fixed multi-second
wait visible at a single startup boundary. These Cargo-only traces do not
measure Fish prompt processing or establish a fix for Enter backlog.

### Runtime changes after the Cargo profiles

Two further bounded changes target work in the tracer without caching guest
paths, ownership records or file-existence results:

- The USERLAND fstat-to-readlink chain reads exactly the returned path length
  and writes its missing NUL terminator into the existing tracee buffer. It
  previously copied an entire 4 KiB local buffer back. Deleted-file and pipe
  handling still use the original descriptor. Bounds and memory errors are
  checked before chaining another syscall.
- Native statx exits returning `ENOENT` or `ENOTDIR` return that existing error
  directly. Entry translation has already applied binds and link handling.
  PRoot previously translated the same path again and called host stat to
  rediscover the error. SIGSYS and synthesized exits retain their fallback,
  as do permission and unsupported-syscall errors.

`scripts/benchmark-proot-cargo.py` generates 280 path dependencies with 39 build
scripts in the guest rootfs, seeds guest ownership metadata, and compares
runtime binaries in alternating order. Its default guest UID/GID differ from
the host. `--memory-fallback` installs a host-only seccomp filter denying
process_vm_readv/writev and verifies those denials before launching PRoot.
This exercises the ptrace memory fallback without a phone or VM; it does not
reproduce all Android restrictions. `--check-rebuild` edits a leaf source,
checks that the leaf and dependent executable rebuild, and runs the executable.

Seven samples per runtime/memory mode produced these median host wall times:

| Memory access | Previous runtime | Updated runtime |
| --- | ---: | ---: |
| Bulk process-memory access available | 738.851 ms | 660.779 ms |
| Ptrace memory fallback | 1389.009 ms | 1216.793 ms |

Total child CPU medians, including the tracer and its reaped children, were
711.243 versus 634.644 ms and 1348.966 versus 1182.296 ms respectively. Samples
varied substantially with host load; these medians are not phone speedup
predictions or controlled cold-cache measurements. The fstat-only change did
not materially improve the larger Cargo workload before the statx change.

An isolated fstat loop with the ptrace fallback took median 547.976 versus
125.300 ms for 2000 calls. With bulk access available, a separate seven-sample
5000-call comparison was approximately unchanged: 188.361 versus 190.761 ms.
The isolated fallback improvement must not be presented as a Cargo speedup.

The complete host suite passed with bulk memory access available and denied,
with syscall filtering both enabled and disabled. It covers ownership, modes,
Rust spawning/linking, debugger attachment, renamed/deleted/pipe descriptors,
and statx missing-file creation/removal transitions and dangling links. The
fstat helper has 100% targeted line/branch coverage; the actual statx handler
has 85/87 executable lines and 60/72 branch outcomes covered. Both new patches
also apply to a pinned source checkout and prepare idempotently. The ARM64
runtime was rebuilt for packaging; the Arch image and metadata format are
unchanged.

As a control, the same synthetic 30-Enter minimal-Fish PTY test took roughly
927 ms directly on the host without PRoot, versus roughly 965 ms under PRoot.
It therefore has a substantial shell/test-protocol baseline and cannot be
used alone to assign the phone's held-Enter backlog to PRoot. No shell timing
variables or input-repeat behavior were changed by these runtime fixes.

### Follow-up after exp.25

The user reported another profiled no-change build taking 7.76 seconds. The
subsequent unprofiled runs on November 17 fell from 5.85 seconds wall time
(Cargo reported 5.65) to 2.56 seconds. The first reported user+system CPU sum
was about 1.29 seconds; the final sum was about 1.03 seconds. These totals
still exclude the PRoot tracer. The warm-up effect remains substantial, and
the remaining warm-build delay is not established as solved.

A host-verified diagnostic can identify the actual tracer from
`/proc/self/status`'s `TracerPid`, read its utime/stime counters before and
after a normal offline build, and report those separately from Cargo and
its children. Tracer nice level and guest/tracer seccomp-filter counts are
also available in the same small output. This does not use nested ptrace.
The host guest showed the expected additional filter on the tracee; the
corresponding phone measurements are pending. CPU counters do not by
themselves attribute scheduler or storage waits, and CPU totals from
concurrent processes must not be subtracted from wall time as an I/O metric.

The next phone run measured 7.037 seconds wall time, 1.533 seconds Cargo child
CPU, and 3.200 seconds tracer CPU from `/proc/<TracerPid>/stat`. The tracer's
nice value was zero. Guest and tracer reported two and one seccomp filters
respectively, consistent with the added fast-I/O filter being installed.
The tracer consumes substantial CPU, rather than the missing wall time being
entirely storage wait. Those CPU totals can overlap and do not constitute an
additive wall-time breakdown. They do not identify individual hot functions,
prove that process_vm access is blocked, or rule out Android cgroup scheduling
effects merely because the nice value is zero.

An opt-in host prototype retained process_vm first, then attempted a bulk
`/proc/<pid>/mem` transfer for larger buffers before the existing ptrace path.
The phone child-process probe then returned 256 successful bytes for both
process_vm operations and proc-mem read/write. This does not establish the
real tracer's transfer success rate, but rules out a blanket denial in that
probe. The prototype was preserved outside the active source tree and is not
packaged; its forced-denial host gain is not evidence of a phone fix.

### Measuring the real tracer

An explicitly enabled native counter recorder now measures the installed
tracer rather than inferring its behavior from a separate child probe. It
records process-memory attempts/successes, wordwise ptrace and stub writes,
path-component lookups, binding comparisons, metadata reads/opened records,
F2FS scan activity and the syscall/seccomp/SIGSYS stop mix. The recorder writes
an initial numeric JSON record, then writes only when requested. SIGUSR2 sets
a signal-safe flag; the event loop publishes an atomic snapshot outside the
signal handler and preserves errno. Disabled SIGUSR2 and SIGUSR1 retain their
existing behavior. Records contain the tracer PID and process start ticks to
reject stale files after PID reuse.

TritonTerm enables this for debug/experimental builds only and puts the helper
in its cache, exposed through the existing `/tmp` binding. It changes neither
the Arch image nor guest metadata. After installing the diagnostic build,
force-stop the app in Android settings and reopen it, then run:

```sh
python /tmp/tritonterm-measure-proot.py cargo build --offline
```

The helper requests snapshots before/after the command and prints their
counter differences with command/tracer CPU and wall time. Bookkeeping
syscalls are included in those differences; this is a diagnostic rather than
a replacement for the uninstrumented performance baseline. `f2fs_active` is a
gauge and is reported as its actual value rather than subtracted.

Native recorder coverage is 96.06% for its implementation and 100% for its
counter header. Host tests cover signal/file failure paths, atomic output,
PID reuse, rejected stale snapshots, command failures and disabled runtimes.
The full runtime suite passed with counters disabled and with counters
enabled plus process_vm denied. Real signals were checked with fast filtering
and the F2FS workaround independently on/off. App/emulator/view JVM tests
passed. A five-sample 280-dependency host comparison had medians 655.735 ms
before, 655.173 ms with recording disabled, and 642.551 ms enabled. The sample
spread overlaps; this establishes no measured slowdown in that run, not a
speedup from instrumentation or a bound on phone overhead.

## Symptom and device tests

- Holding Enter in the normal Fish + Starship session can leave several prompts
  appearing after the key is released. This was reproduced with both the
  built-in keyboard and Android's keyboard.
- `bash --noprofile --norc -i` handled the same held-Enter test responsively.
- `fish --no-config -i` still lagged. Replacing its prompt with a simple
  `FISH-MIN>` prompt reduced the lag in one test but did not remove it.
- `fish --private --no-config -i` still lagged, so Fish history is not the
  primary explanation.
- When opening a new shell, the terminal caret is visible first; the complete
  Fish prompt appears a short time later. This places the visible delay before
  the first complete prompt, but by itself does not say whether the delay is in
  shell startup, PRoot, PTY output delivery, or rendering.
- A previously compiled `cargo run` in the Arch environment still took several
  seconds before Elio launched. On the PC, running the already-built Elio with
  `cargo run` starts immediately. This is an observation, not a
  controlled benchmark: the host, filesystem, runtime, and launch paths differ.
  It suggests that the delay is not limited to compilation and is consistent
  with overhead in the guest execution/backend path.
- Compilation in TritonTerm also felt very slow, but no matched compile-time
  measurements or CPU profile were collected.
- `cat >/dev/null` after a single Enter accepted repeated newlines while Enter
  was held and stopped when the key was released. This shows that held input can
  reach a foreground process without producing a prompt backlog; it does not
  measure Fish's prompt response or PTY queue wait time.
- Fish in `~/elio` felt substantially worse than Fish at `~`. Do not treat that
  as a minimal-Fish comparison unless the `FISH-MIN>` prompt was visibly active:
  the normal Fish prompt calls `fish_vcs_prompt`, which can inspect Git state.

These observations point to more than one possible contributor: prompt work can
make repository sessions slower, while the remaining delay with a minimal Fish
prompt means Starship alone does not explain the general symptom.

## Starship timing evidence

The normal prompt is configured in
[`scripts/arch-image/starship.toml`](../scripts/arch-image/starship.toml). It
includes Git branch/status and language runtime modules, including Rust.

`env STARSHIP_LOG=trace starship timings` produced these results:

- At `~`, the measured modules were generally short; directory scanning took
  about 10 ms in the trace output. That run did not reproduce the recurring
  scan-timeout warning.
- In `~/elio`, the reported module durations included `git_status` at about
  268 ms, `git_branch` at about 102 ms, and `rust` at about 142 ms. The Rust
  module ran `rustup`; Git modules ran Git commands. Directory and Docker
  context work also took longer than at home.
- `starship timings` reports module computation, not total time from shell
  prompt request to prompt display. It also does not identify the source of
  lag that persists with a simple Fish prompt.
- Starship's recurring scan-timeout warning was not captured in these timings.
  Treat it as a separate observed symptom, not proof that every delayed prompt
  is caused by Starship.

## Perfetto captures

The following device traces were captured and inspected. They are separate
captures, not one continuous benchmark:

| Trace | Intended workload | Qualification |
| --- | --- | --- |
| `~/Downloads/trace-garnet-BP2A.250605.031.A3-2026-11-16-11-09-46.perfetto-trace` | Baseline session; exact test phase not labeled | Contains TritonTerm surface frames but predates the added input/PTY trace markers. |
| `~/Downloads/trace-garnet-BP2A.250605.031.A3-2026-11-16-12-36-27.perfetto-trace` | Normal Fish + Starship | Starship processes were present. |
| `~/Downloads/trace-garnet-BP2A.250605.031.A3-2026-11-16-12-38-50.perfetto-trace` | Intended minimal Fish | The trace did not confirm a separate `fish --no-config -i` child. Do not use it as a clean minimal-Fish comparison. |
| `~/Downloads/trace-garnet-BP2A.250605.031.A3-2026-11-16-12-40-30.perfetto-trace` | `bash --noprofile --norc -i` | Bash was present. |
| `~/Downloads/trace-garnet-BP2A.250605.031.A3-2026-11-16-12-51-24.perfetto-trace` | Intended `FISH-MIN>` prompt | The trace contained 79 Starship processes and no confirmed separate minimal-Fish child, so it is not a clean minimal-Fish trace. |
| `~/Downloads/trace-garnet-BP2A.250605.031.A3-2026-11-16-12-59-07.perfetto-trace` | Confirmed minimal Fish at `~` | Confirmed child command `fish --no-config -i`, no Starship processes, prompt `triton@localhost ~>`. |

### FrameTimeline details by capture

These frame results are useful context for the perceived choppiness, but they
do not by themselves identify the code responsible for delayed prompts:

- **11:09 baseline:** 729 surface FrameTimeline records: 679 on time and 50
  late (24 app deadline misses and 26 buffer-stuffing classifications). The
  worst reported frame durations were about 189 ms and 167 ms. Late frames
  clustered near trace times 856986–856994, with additional misses around
  857004 and 857010–857014. In the touch-heavy interval around 857018–857029,
  recorded frames were on time, about 54–66 per second, with reported frame
  durations below about 12 ms. The exact user action phases were not marked.
  Typical `View#draw()` work averaged about 1.7 ms; RenderThread drawing
  averaged about 2.9 ms with a maximum around 3.9 ms. Recorded touch callbacks
  were typically under 1 ms, maximum around 1.6 ms. Near trace end there was a
  separate ~72 ms `clientTransactionExecuted`/Binder slice and ~63 ms traversal,
  followed by relayout and activity-stop work. The Android key-event table was
  empty, so Enter-to-output timing could not be measured. Trace Processor also
  reported one empty power-rail packet and 27 conflicting track-descriptor
  reservations; keep these capture-quality caveats in mind.
- **12:36 normal Fish + Starship:** about 43% of frames were late. Coarse
  scheduler CPU totals for the whole capture were about 5.28 s for PRoot,
  1.25 s for Fish, and 69 ms across Starship processes. These are whole-capture
  totals, not normalized per active test second.
- **12:38 intended minimal Fish:** about 16% of frames were late. The process
  list showed the normal login Fish but no separate `fish --no-config -i`
  child, so this does not confirm the intended test. Coarse totals were about
  3.38 s for PRoot and 1.50 s for the recorded login Fish.
- **12:40 Bash:** about 61% of frames were late, despite Bash handling held
  Enter responsively. Many late frames clustered near trace times 862469–862472
  while terminal-draw markers remained below about 8.4 ms. Coarse totals were
  about 0.33 s for PRoot and 0.25 s for Bash. Each of the 12:36–12:40 captures
  also had an approximately 69–80 ms traversal/relayout stall near its start.
- **12:51 intended `FISH-MIN>`:** 79 Starship processes used about 1.40 s of
  scheduler CPU in total, and no separate minimal-Fish child was confirmed.
  The active approximately five-second region had 4 late frames among 243;
  the approximately 201 ms frame at capture start coincided with a long
  relayout. Marked work averages/maxima were: input enqueue 0.03/0.22 ms, PTY
  write 0.12/1.47 ms, parse/update 0.25/1.52 ms, and terminal draw
  0.82/2.17 ms. Starship activity means this is not evidence from an isolated
  minimal prompt.
- **12:59 confirmed minimal Fish:** 27 late frames among 422, with misses
  clustered around trace times 863580–863581 and 863588. The approximately
  182 ms frame at trace start and approximately 203 ms relayout near the end
  coincide with window/lifecycle work. Scheduler CPU was about 4.59 s for the
  Fish child and 6.35 s for PRoot over the capture. Marked input enqueue,
  PTY write, parse/update, and terminal draw averaged approximately 0.04,
  0.13, 0.29, and 0.87 ms respectively.

The 12:36–12:40 coarse CPU figures and late-frame percentages came from captures
of different lengths and phases. The 12:59 CPU comparison with Bash is also not
normalized. Do not compare these totals as benchmark ratios. CPU sampling was
disabled in these captures, so none provides sampled stacks attributing CPU to
specific Fish or PRoot functions.

Across the marked 12:36–12:59 captures, TritonTerm's instrumented sections
were short: input enqueue about 0.03–0.10 ms, PTY write about 0.10–0.18 ms,
parse/update about 0.25–0.46 ms, and terminal draw about 0.82–3.1 ms on
average. Reported maxima were below roughly 12.3 ms. This does not show a long
operation inside those measured sections. The markers do not measure queue
dwell time, the gap between stages, when Fish consumes each newline, or time
until Fish emits the next prompt.

FrameTimeline showed late frames in several captures, but jank percentages
are not a direct measurement of prompt backlog. Some large outliers coincided
with window relayout or lifecycle work. In particular, the Bash capture also
had substantial frame jank despite responsive held-Enter behavior. Interpret
the per-capture counts with their capture phases and lifecycle events in mind.

## App-side path and current evidence

Relevant code is in the vendored terminal emulator:

- [`TerminalSession.java`](../terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java)
  keeps a 4 KiB terminal-to-process byte queue. `write()` enqueues input, and a
  dedicated `TermSessionOutputWriter` thread drains it to the PTY.
- PTY output is read separately, queued, then parsed/updated on the Android
  main thread with a bounded 16 KiB message budget.
- [`TerminalTrace.java`](../terminal-emulator/src/main/java/com/termux/terminal/TerminalTrace.java)
  supplies opt-in Android trace markers. Existing markers include keyboard
  delivery, input enqueue, PTY write/read, PTY read enqueue, PTY parse/update,
  screen callback, and terminal draw.

The short marker durations argue against moving parsing or rendering to a
worker based only on the current evidence. They do not rule out time spent
waiting in queues, scheduling delays, or work in PRoot/Fish between markers.

## PRoot seccomp lead: investigated and ruled out as a toggle

An earlier suggestion was to make an opt-in build that removed
`PROOT_NO_SECCOMP=1` for the interactive shell, based on the idea that this
would enable PRoot's syscall filter. Code review of this fork found that this
would be a no-op for performance:

- TritonTerm sets `PROOT_NO_SECCOMP=1` in the Android shell environment in
  [`TerminalSessionController.kt`](../app/src/main/java/com/tritonterm/android/terminal/TerminalSessionController.kt)
  and in several installer/diagnostic environments. `pr-cli` also sets it in
  `third_party/oonid-pr/src/pr-cli/src/shared.rs`.
- In this fork, `PROOT_NO_SECCOMP` only suppresses an informational message
  about an `execve`/`EPERM` kernel issue in `src/proot/src/cli/cli.c`; it does
  not enable or disable an active filter.
- The Android PRoot path deliberately skips PRoot's own seccomp filter because
  it conflicts with the seccomp filter inherited from Android's zygote and can
  cause `SIGSYS`. The fork instead uses ptrace syscall interception. See
  `third_party/oonid-pr/docs/proot-improvement.md` and
  `third_party/oonid-pr/src/proot/src/tracee/event.c`.
- The event loop relies on syscall entry/exit handling for translations,
  extensions, and chained syscalls. Blindly skipping exit stops is not a safe
  optimization. A real change would need to preserve those semantics and be
  measured with functional regression coverage.

Therefore, do not make an APK that only removes `PROOT_NO_SECCOMP=1` as a
performance experiment. Enabling the filter would require a native PRoot code
change and would carry Android compatibility risk; current traces do not
identify a safe filter-based optimization.

## Current conclusion and next useful investigation

What the evidence supports:

1. Starship's Git and Rust modules can be expensive in a repository and likely
   explain some repo-specific prompt delay.
2. Starship is not a complete explanation: Fish still lagged with
   `--no-config`, `--private`, and a simple prompt.
3. TritonTerm's measured enqueue, PTY write, parse/update, and draw slices are
   short. No trace has yet attributed the prompt delay to a long block in one
   of those slices.
4. The confirmed minimal-Fish trace shows much more coarse scheduler CPU for
   Fish and PRoot than the earlier Bash trace, but the workloads were not
   normalized and there are no sampled stacks. This does not yet separate Fish
   cost from PRoot syscall-tracing cost.
5. The environment variable is not a switch for the disabled PRoot seccomp
   filter; changing it alone is not a valid optimization.

The combined observations—delay before Fish's first full prompt, slow launch of
an already-built program inside Arch, slow compilation, and lag that remains
with minimal Fish—make the PRoot/guest execution path a strong area to
investigate. They do not prove PRoot is the cause: Fish startup, guest I/O,
process scheduling, syscall translation, and gaps between the existing app
trace markers remain possible contributors. The existing measurements make a
long Android-side enqueue, parse, or draw operation less likely, but do not
measure all waiting between those stages.

If work resumes, first use a repeatable, phase-labeled workload with a visible
prompt and process verification (`ps` must show the intended child shell):

1. Idle prompt, then a small fixed number of Enter presses and a held-Enter
   interval, separately for Bash, Fish `--no-config`, and Fish with the trivial
   prompt. Keep the directory at `~` for the shell comparison.
2. Repeat at a fixed Git repository path to isolate prompt/module filesystem
   work; record whether the custom prompt was actually active.
3. Record elapsed time from PTY write to the next prompt (and queue depth or
   bytes if instrumented), not just duration of the write call. Keep app trace
   markers, process identity, and test phase labels in the same capture.
4. If Fish/PRoot still differ from Bash, collect sampled CPU profiles or
   targeted syscall/ptrace counts for matching command workloads before
   changing PRoot. Compare equal-duration captures and report per-process CPU
   normalized by active test time.
5. For any native PRoot optimization, preserve syscall translation behavior and
   run the existing PRoot and Arch guest regression checks before a phone APK
   test.

Do not infer that the Android UI is the cause from frame jank alone, or that
PRoot is the cause from unnormalized process CPU totals alone.


### Phone counters from exp.26 and next measurements

The real tracer measured a no-change Cargo build at 7.399 seconds wall time,
1.640 seconds command CPU and 3.380 seconds tracer CPU, with nice zero. It
recorded 21,516 events (10,760 syscall stops, 10,706 seccomp stops and 11
SIGSYS stops). Bulk memory reads succeeded in 29,071 of 29,072 attempts;
all 14,403 writes succeeded. There was one ptrace peek and no ptrace pokes
or stub writes. Thus wordwise memory fallbacks do not explain this run.
The F2FS workaround was inactive with no scans or enumerated entries.

The remaining counts were 7,808 path translations, 2,139,610 binding
comparisons, 57,197 component lstats and 10,395 ownership-record read
attempts (10,390 opened). Counts establish repeated work, not its elapsed
cost. A host-only binding index preserved mapping order and passed native
checks, but its seven-sample Cargo medians were 615.112 versus 611.123 ms,
with overlapping variation. A monitored positive-directory cache also
passed targeted rename, removal, symlink replacement, chmod, root-replacement
and monitoring-error tests, without a clear whole-command gain. Neither
prototype is in the packaged runtime; no guest filesystem cache is enabled.

Stat/statx metadata handling now opens a record directly instead of making
an existence check first. Missing records retain normal ownership mapping;
permission helpers retain their default for missing records. Truncated
records no longer partially assign uninitialized metadata. The shared
reader and its defaulting wrapper have complete targeted line/block
coverage for valid, missing and truncated records. A seven-sample host
comparison had medians 602.297 versus 591.321 ms, a small difference amid
substantial timing variation, not a demonstrated phone fix.

The next diagnostic adds explicitly enabled monotonic elapsed timers for
component lstat, binding lookup, ownership-record reads, process_vm reads
and writes, and trace-event handling. Experimental/debug launches set
`PROOT_PERF_TIMING=1`; ordinary launches leave timers disabled. Clock calls
preserve errno and failed clock calls do not add an elapsed value. The
same helper also separates tracer user/system CPU. Timer values measure
elapsed wall time, including pauses inside each measured operation. Event
handling encloses component timers, so those figures overlap and must not
be added to it or treated as CPU accounting. Host and phone timing overhead
must not be confused with an uninstrumented performance baseline.

The command remains `python /tmp/tritonterm-measure-proot.py cargo build --offline`.
No new trace export, Arch image, guest package rebuild or data reset is needed.

Validation for this update: the full native host suite passed with bulk memory
access available and denied, both tracing modes, metadata/path transitions,
Rust/linker/thread workloads and debugger attachment. The updated helper was
checked with timers enabled/disabled in both memory modes. The timer header
has 85.51% targeted line coverage. All 85 app/emulator/view JVM tests completed
with zero failures/errors and one existing skip, and the Android ARM64 build
passed. The new patches prepare against the pinned source and reapply
idempotently; no rootfs pin or guest metadata format changed.


### Phone elapsed timers from exp.27

Two successful offline Cargo runs reported the following values. These are
elapsed section times, including scheduling pauses, rather than a CPU-time
partition. Event handling encloses the component timers.

| Measurement | First run | Second run |
| --- | ---: | ---: |
| Command wall | 7.590 s | 5.352 s |
| Command CPU | 1.573 s | 1.332 s |
| Tracer user CPU | 0.430 s | 0.380 s |
| Tracer system CPU | 3.010 s | 2.190 s |
| Component lstat elapsed | 1.7087 s | 1.0701 s |
| Ownership-record read elapsed | 1.7700 s | 1.1535 s |
| Bulk memory read/write elapsed | 0.1749 s | 0.1383 s |
| Binding lookup elapsed | 0.0799 s | 0.0648 s |
| Event handling elapsed, enclosing components | 5.5363 s | 3.5610 s |

The workload counts remained approximately 57,200 component lstats and
10,400 opened ownership records. Bulk memory still succeeded except for
one read; the F2FS workaround remained inactive. This supplies the elapsed
cost missing from the earlier count-only diagnosis. The two filesystem
sections account for approximately 63% of measured event-handler elapsed
time in both runs. Binding lookup is a small elapsed cost despite its
millions of comparisons. The two filesystem sections explain about 1.26 s
of the 2.24 s wall-time difference between runs, without proving a complete
additive wall/CPU breakdown.

The next candidates are reducing live directory validation and ownership
record open/read/close work. The monitored-directory prototype's lack of
host benefit does not establish lack of phone benefit: the phone's measured
filesystem cost differs substantially from the host profile. Nevertheless,
changes need mutation, symlink, permission, replacement and monitoring-failure
checks before packaging. Reusing metadata descriptors with live content
reads and pathname/identity validation is another candidate that avoids
caching ownership values; descriptor limits and permission changes must be
handled. Neither candidate is established as a phone fix yet. No change to
the rootfs format is required for these runtime approaches. These Cargo
measurements still do not independently establish the Fish Enter bottleneck.

## exp.31 phone follow-up: Cargo and prompt isolation

With the validated metadata cache and openat path reuse, the phone reported
unchanged Cargo builds at 3.48 s, 851.20 ms and 877.03 ms wall time. Cargo's
own reported durations were 3.27 s, 0.74 s and 0.76 s; a subsequent cargo run
reported 0.80 s before launching Elio. These sequential measurements are not a
controlled speedup estimate against exp.30: filesystem warmth and phone state
remain variables.

Enter still piled up in the normal configured Fish session. In the requested
shell comparison, the user reported no piling up with fish --no-config
--private, with its fish_prompt replaced by a literal MIN> prompt, or with
bash --noprofile --norc -i. Crucially, replacing fish_prompt with that same
literal prompt in the normal configured Fish session also removed the backlog.
This latest result isolates expensive normal prompt generation as a practical
trigger on this build. It does not identify an individual Starship module or
prove that all terminal input/rendering latency is absent.

The next prompt change should target synchronous prompt generation and repeated
context discovery, retaining useful status information and checking freshness
when commands or directories change. Earlier PRoot measurements of Git/Rust
module cost are leads, not fresh module-level profiling of exp.31. Cargo's
remaining startup overhead is a separate workload; faster prompt rendering
must not be presented as a fix for it.

### exp.32 source investigation

The corrected Starship comparison at home was about 23–25 ms minimal and
53–57 ms configured on the phone; in Elio it was 118–130 ms configured. On the
laptop, the first user block's repository cd failed, so both of those batches
were home measurements. The corrected host path is /home/regueiro/elio-fm/elio:
local repeats measured roughly 2.2 ms minimal and 21–27 ms configured, with
Git status around 7 ms, Rust around 14 ms and the clock around 2 ms. Different
hardware, versions and repository state limit causal interpretation.

An ARM64 Starship from an existing local Arch image was traced under QEMU,
with the original archive metadata and TZ=Europe/Madrid. Clock initialization
walked timezone-data directories. The runtime repeated path-stat work in the
l2s probe, repeated fd-target lookup between l2s and ownership handling, and
read each executable-mapping program header separately in two passes. The
exp.32 candidate reuses those per-operation results and reads the bounded ELF
program-header table in bulk. No prompt modules, time settings or freshness
rules are changed. Focused stat fixture filesystem calls dropped 6065 to 3565
(path stat) and 2572 to 2072 (fd stat), relative to exp.31. Validated Starship
runs removed 58 individual program-header reads, replaced with three bulk
reads. These are operation counts, not measured phone speedups.

QEMU's unavailable guest seccomp fallback can leave a shared-library syscall
untranslated. A profiling run that rendered the wrong/default prompt after
reading an untranslated config path was rejected explicitly; its timing and
operation counts are not comparison evidence. The profiling script verifies
prompt output and keeps rejected logs. Source and test details are in
docs/testing-chroot-ng.md; /tmp/tritonterm-starship-validated/summary.json holds
the accepted operation-count summaries for this session.
