# Repository prompt responsiveness after exp.42

## Phone evidence and scope

The phone's exp.42 collection `runtime-profile.EP34Ki` renders the configured
home prompt in 23.53/23.19 ms warmed, versus 103.36/107.71 ms in `~/elio`.
Minimal Starship takes 14.66/22.40 ms unprofiled. Elio version queries are
8.40/12.93 ms warmed. Sampled loaders accept two cached images with no
fallbacks, so the previous executable-cache publication failure is absent.

A separate preceding `starship timings` invocation reports Rust at 283 ms,
Git status at 193 ms and branch at 60 ms. Those are not an additive elapsed
time partition or warmed controls: the invocation preceded the collector,
modules can run concurrently, and shared discovery can make modules wait on
one another. The actual later full prompt elapsed times are more relevant
to the sustained Enter behavior than summing these module durations.

The repository Starship sample has 2,715 metadata lookups, 2,496 metadata-open
attempts, 9,579 component probes and 3,031 pins. Its 276.435 ms accumulated
dispatch time overlaps across threads within 88.115 ms scope wall. It must
not be treated as 276 ms of exclusive input delay. About 100 ms per complete
synchronous prompt is consistent with Enter repeats accumulating faster than
prompt generation completes. Earlier literal-prompt tests removing the
backlog support this explanation; they do not rule out every UI/PTY issue.

The investigation fixture leaves installed config, runtime and rootfs unchanged. The
existing local fixture uses Arch Starship 1.27.0 and real ARM64 Git, with the
packaged exp.42 engine. Host Fish 4.6.0 is used for the isolated integration
check. The precise installed phone Fish functions/version still need to be
checked if its configuration differs from the app defaults.

## Fish integration: an extra empty right-prompt process

The app defaults source `starship init fish` in
`app/src/main/assets/tritonterm-fish-system-config.fish`. The actual ARM64
Starship binary emits a `fish_prompt` that calls `starship prompt` and a
`fish_right_prompt` that calls `starship prompt --right`, each synchronously.
The shipped TOML sets no right_format, whose default is empty; see the
[official configuration reference](https://starship.rs/config/).

An isolated host Fish PTY loads that exact emitted integration, substituting
only a counting executable for Starship. Five blank Enters launch five left
and five right processes. Removing the right-prompt function in that isolated
session yields five left processes for five blank Enters. This establishes
the invocation behavior; it is not a phone timing measurement.

Running the actual ARM64 right prompt with the app's real TOML produces empty
stdout on all three accepted runs. It still performs startup, configuration
and context work: 23 resolves, 35 pins and 17 metadata-open attempts in this
fixture. Warm runs hit both main/interpreter images. QEMU forces anonymous
library mappings here, so the fixture's library copies/scheduling counters
must not be presented as phone costs.

Skipping a confirmed empty right prompt is useful cleanup but will not
eliminate the expensive left prompt. A production change must retain custom
right formats and account for config-path/content changes. Blindly removing
a user's right prompt or caching its emptiness indefinitely is not an
established compatibility-preserving implementation.

Artifacts: `.tools/prompt-investigation/empty-right.json` and
`.tools/prompt-investigation/repro/tritonterm-fish-prompt-investigation.log`.
The emitted init script and PTY transcript are saved beside the latter.

## Rust: short toolchain names select the Rustup route

Starship 1.27's Rust module checks environment, Rustup overrides, toolchain
files and defaults. It first tries an installed compiler path built from the
selected toolchain name, and falls back to `rustup run <name> rustc --version`
when that command cannot be created. The Rust command result is cached only
within that Starship process. The execution captures subprocess stdout and
stderr, so the normal parent prompt profile does not provide the Rust child's
runtime costs. See [the versioned Rust module source](https://github.com/starship/starship/blob/v1.27.0/src/modules/rust.rs).

The laptop Elio project uses the platform-neutral channel `1.99.1`, matching
the phone's shown toolchain selection. A controlled ARM64 routing fixture
uses the actual Starship binary with tiny fake tools, an installed full
`1.99.1-aarch64-unknown-linux-gnu` directory and Rustup settings. Both cases
render exactly `v1.99.1`:

| Project channel | Observed subprocess |
| --- | --- |
| `1.99.1` | `rustup run 1.99.1 rustc --version` |
| `1.99.1-aarch64-unknown-linux-gnu` | selected full compiler path, `rustc --version` |

The fake tools log selection only; they supply no real compiler performance
measurement. The precise phone module command is still to be confirmed by
its trace. Changing a shared project's channel to an Android-specific host
triple is not a portable fix. Any shortcut should resolve the existing
selection correctly, respecting overrides, custom toolchains and updates.

A direct phone Rust version profile has been requested to inspect child
loading, library mapping, filesystem work and total startup. In particular,
the existing image cache covers the main ELF and interpreter; library
mapping behavior must be read from the real phone profile before proposing
more loader changes. The host Rust fixture's forced anonymous mappings are
not evidence that those same copies occur on Android, where native library
file mappings can succeed.

Artifacts: `.tools/prompt-investigation/rust-routing.json` and the saved
`tritonterm-investigate-rust-selection.py` reproduction script.

## Git: parent-only measurements overstate an executable switch

Starship can use its built-in Git engine or the Git executable for status;
[the versioned status implementation](https://github.com/starship/starship/blob/v1.27.0/src/modules/git_status.rs)
shows both paths. Repository discovery is already shared within a prompt
through its Context; adding the same within-process memoization again is
not an optimization. See [Context](https://github.com/starship/starship/blob/v1.27.0/src/context/mod.rs).

A disposable repository contains 512 source files, a modified tracked file
and an untracked file. Configurations differ only in `use_git_executable`.
All six alternating runs produce exactly `main|M1U1`. Optional index writes
are disabled consistently with `GIT_OPTIONAL_LOCKS=0`.

Parent Starship metadata-open attempts fall from around 641 to 48–49 when
using the executable. This alone is misleading because work moves into Git,
whose stderr is captured. Ordinary QEMU `-strace` follows that redirected
stderr too and hides many of the child's calls from the parent's trace.
The first parent-only trace is retained as an incomplete diagnostic, not a
comparison of total work.

A second collection uses QEMU's separate `-d strace -D <file>` logging to
retain calls across redirection. It includes the Git child's source-file
stat/metadata operations and retains the same correct prompt output.

| Complete logged syscall counts | Built-in Git engine | Git executable |
| --- | ---: | ---: |
| newfstatat + fstat + statx | 3,092 | 2,651–2,652 |
| readlinkat | 2,243 | 3,136–3,143 |
| openat, including emulation housekeeping | 21,711–21,714 | 21,686–21,690 |

This does not establish a clear overall filesystem-work win. The executable
uses fewer stat-family calls but more readlinks; open counts stay similar.
Trace-wide open/getdents totals include substantial QEMU-visible thread
housekeeping under `/proc/self/task` during emulated exec. Interleaved
multithreaded traces also prevent reliable pathname/return-value attribution
by matching single lines. No elapsed-time speedup, successful-sidecar-read
count or native phone syscall total is inferred from these figures. Thread
counts and scheduling differ from the phone.

Artifacts: `.tools/prompt-investigation/complete/git-comparison.json`,
its six trace/stderr/stdout sets, and the saved reproduction script. The
incomplete parent-only comparison is in the enclosing directory.

## Direction for the next implementation

For reliable repeated-Enter responsiveness, keep expensive Git/Rust
refreshes off Fish's synchronous input path, retain Starship's configured
information, and coalesce repeated refresh requests. Background rendering is
a known Fish approach; [fish-async-prompt](https://github.com/acomagu/fish-async-prompt)
provides an existing implementation to inspect rather than proof that an
unmodified installation meets this app's requirements.

A suitable integration needs one bounded active refresh, latest-context
selection and rejection of late results from another directory. Capture
status/pipestatus, duration, jobs, keymap and width in the interactive shell;
a worker's own jobs/status are not substitutes. Keep clock/config/toolchain
and external repository changes refreshing, and distinguish pending state
from a newly computed result. Empty Enter repeats should not create an
unbounded queue of full Git scans. Clear files/children on shell exit and
retain a synchronous fallback. Test commands, pipelines, directory changes,
resize, job updates, transient prompts and completion while refreshes finish.

The inspected generic async plugin starts background Fish work per configured
function on prompt events; its source does not establish the generation and
worker-bound requirements above. Blind installation is not the verdict.
An app integration or carefully tested adapter is needed before claiming it
preserves all current behavior and resource usage.

Skipping the empty right prompt can accompany that work. Rust lookup may have
a separate avoidable Rustup layer; quantify it with the requested phone
profile. Continue backend traversal/metadata optimization independently for
Cargo and general tools. Neither switching Git engines nor increasing
caches blindly is justified as the fix for this interaction symptom.

## exp.43: bounded asynchronous rendering

The app now ships `tritonterm-async-prompt.fish` in its existing `/tmp`
cache binding and sources it after user configuration in the initial Arch
Fish shell. Both backends use the adapter; no rootfs rebuild, migration or
user configuration rewrite is needed. Manually launched nested Fish shells
retain their own configuration unless the adapter is sourced there too.

The adapter wraps the standard Starship Fish functions. A first background
worker compares their definitions against initialization from the installed
Starship; custom wrappers fall back to their original synchronous functions.
Unrelated literal prompts are left alone. `set -g TRITONTERM_ASYNC_PROMPT 0`
opts out in the current shell; putting that setting in Fish configuration
also disables setup in future shells.

Only one heavy renderer runs at a time. New requests coalesce while it is
busy. Directory and command-generation checks reject obsolete results;
the displayed cache also requires the complete captured context to match.
Status, pipeline status, duration, width, keymap and real user-job count
come from the interactive shell. A pending prompt shows the current path
and a failure status, then repaint publishes the configured Starship layout
without replacing the input buffer. Git/Rust information may therefore be
pending briefly after a command or directory change.

An unchanged blank Enter reuses a completed snapshot. After one second,
a new prompt request can refresh it, so clock and external repository changes
are not cached indefinitely. This is request-driven; no idle polling loop
runs. Configuration path/content changes invalidate the snapshot and query
Starship's normalized `right_format`. Only a confirmed empty format skips
right rendering; conditionally empty output does not disable future right
modules. Every real command advances generation so environment changes are
recomputed even when they are not among the explicitly tracked variables.

A disowned GNU `timeout` process group bounds rendering to three seconds
with a one-second forced-kill allowance. Natural child-exit notifications
publish completion; no private application signal is reserved. A failed or
timed-out renderer uses a two-second retry backoff while input stays usable.
The worker is excluded from user jobs and `$last_pid` is restored. Shell exit
removes private files and kills a still-owned worker group when the parent
children list is available; the timeout also bounds its remaining lifetime.
Transient rendering and explicit opt-out use the original prompt hooks with
reconstructed status/pipeline state.

The real-PTY host suite `scripts/test-async-prompt.py` exercises the adapter
with emitted Starship initialization and observable slow renderers. It
checks rapid Enter plus command execution, context coalescing, status and
pipelines, config/right-prompt changes, jobs and `$last_pid`, resizing,
completion while typing, timeout recovery, cleanup and compatibility fallback.
Use `--init-script` to test a saved upstream initialization from another
Starship version. These are host integration checks; the phone provides the
separate Android integration and subjective responsiveness check.

The native engines remain identical to exp.42. This change removes prompt
refreshes from the synchronous input path; it does not claim another Cargo
speedup or eliminate the remaining backend filesystem overhead.


### exp.43 validation

The signed APK passed package and signature verification. Its twelve native
libraries are byte-identical to exp.42, and its packaged adapter matches the
tested source. App unit tests report 70 passed and one skipped. Twenty real-PTY
checks passed with Starship 1.27 initialization; an installed Starship CLI
also rendered through the adapter without compatibility fallback.

After installing exp.43, the user confirmed that holding Enter no longer
piles up prompts. This validates the interaction fix on the phone, without
claiming an additional measured Cargo or backend filesystem speedup.
