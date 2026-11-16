# Terminal input and prompt latency investigation

This note records the current evidence about delayed repeated Enter presses and
choppy interaction in TritonTerm. It is a handoff for future investigation; no
performance fix has been established yet.

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
