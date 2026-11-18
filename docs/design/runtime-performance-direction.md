# Runtime performance direction

Scope: rootless ARM64 Android, the current app sandbox and target SDK, upstream
Arch Linux ARM packages, and preservation of guest filesystem/identity behavior.
This document records the initial source-based architectural recommendation,
written before the chroot-ng port and phone trials. For implementation status,
measurements and unresolved limitations, see [the experimental backend
notes](../testing-chroot-ng.md).

## Evidence

The exp.27 phone runs took 7.590 and 5.352 seconds wall time. Tracer CPU was
3.440/2.570 seconds, including 3.010/2.190 seconds system CPU. Component lstat
elapsed time was 1.7087/1.0701 seconds; ownership-record reads were
1.7700/1.1535 seconds. Binding lookups were only 0.0799/0.0648 seconds, and
bulk-memory transfers 0.1749/0.1383 seconds. Event handling includes component
timers: do not add the enclosing and enclosed measurements or interpret the
elapsed values as a CPU partition. This identifies syscall amplification and
metadata access as material targets, not just ptrace transport.

The laptop's 0.10–0.16 seconds is the desired experience. It is not yet a
same-phone native baseline. Neither CPU totals nor section elapsed times give
an irreducible runtime floor or a predicted replacement speedup.

## Source audit

Inspected upstream snapshots:

- fake-chroot-ng: `869c42b705e889a487a9503e219fedfab71f4d88`.
- proot-next: `04cfb059ddafb426f6488d8a285bf8d87f747e23`.

fake-chroot-ng runs interception in-process. Its ARM64 rewriting tier replaces
eligible syscall sites with branches to a dispatcher; SIGSYS handles remaining
interceptions. Its loader and Android block-list handling address the execution
restrictions of an ordinary app, rather than assuming a privileged shell.
Sources: `src/monitor/rewrite.c`, `sigsys.c`, `blocklist.c`, `execmap.c`.
However, `cng_resolve_lim` in `src/monitor/dispatch.c` still walks components,
and `src/monitor/pin.c` performs additional directory pinning/verification.
An unchanged swap does not establish removal of our filesystem bottleneck.
It also does not implement TritonTerm's sidecar ownership format.

proot-next uses a user-notification supervisor/worker pool for most filesystem
calls, retaining limited ptrace or a guest-stub fallback. Normal notified calls
still cross a process boundary and require argument/result transfer; opens can
require descriptor injection. `src/seccomp/filter.rs::install` uses
`SECCOMP_FILTER_FLAG_NEW_LISTENER`. Android inherited-filter behavior and loader
integration must be verified, not inferred from desktop/container operation.
Sources: `src/syscall/notify.rs`, `src/seccomp/notify.rs`, `src/exec/shim`.

Its filesystem code has useful ideas: pinned directory descriptors, relative
`*at` operations and a directory cache (`src/fs/canon.rs`, `binding.rs`). But the
cache explicitly documents that outside changes are not observed until flush;
separate app sessions/installers therefore need coherent invalidation. Its
`fake_owner` path maps supervisor-owned files to root rather than preserving
our per-file archive ownership/mode records. A simpler ownership implementation
must not be mistaken for equivalent compatibility at lower cost.

## Recommendation

Prefer an in-process, ARM64-rewriting execution design, with fake-chroot-ng as
the first foundation to evaluate. This is a recommendation about mechanism and
Android fit, not a claim that its current implementation is fastest or ready.
Do not plan two complete ports or replace the working backend on this evidence.

The intended design must solve three problems together:

1. Avoid a second process round trip for ordinary translated calls. Use direct
   in-process dispatch where possible and a verified trap fallback where needed.
2. Reduce filesystem amplification: retain validated directory descriptors,
   operate relative to the right parent, and re-resolve on changes. Cache
   coherence must span sessions, package updates and outside modifications;
   uncertainty or monitoring failure must return to correct uncached handling.
3. Remove per-stat ownership-record opens from the normal path. A compact
   persistent metadata index with inexpensive shared reads is a design candidate.
   It must retain actual guest UID/GID/mode information and define transaction,
   invalidation and crash-recovery behavior. Simply deleting metadata checks or
   treating every file as owned by the current user is not equivalent behavior.

A directory cache alone leaves metadata and tracing cost. A ptrace replacement
alone can leave component walks and metadata cost. Rewriting PRoot in another
language does not inherently address either.

Before a full integration, validate the indispensable Android mechanisms from
the actual app process: executable mappings/loader, trap delivery, rewritten
calls and child execution. Then establish one representative end-to-end Cargo
path with correct stat results. This is a gate on the chosen design, not a
sequence of speculative backend migrations. An unsupported mechanism or no
material workload improvement is a reason to stop before a full port.

Keep PRoot selectable during evaluation. Use a separate writable test rootfs
for mutation/package tests. Cover symlinks and binds, rename/replacement,
permissions/ownership/set-id, hardlinks, concurrent sessions, Cargo dependency
freshness/rebuilds, compiler/linker spawning, signals/threads and package scripts.
No VM/host-only result can establish behavior under the phone's app policy.

The recommendation changes if privileged real Linux isolation becomes available;
that is outside the current rootless-app scope. Native Android package execution
is another route, but upstream Termux packages have a fixed application prefix,
so moving them into TritonTerm is not a trivial way to preserve its current
package-management model without maintaining differently built packages.
