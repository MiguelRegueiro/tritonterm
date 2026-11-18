# Repository stat path reuse (exp.37)

## Scope and evidence

The exp.36 phone reports leave repository prompts at roughly 112–132 ms,
with about 2,500 metadata opens and 15,500 component probes. The next change
targets repeated path traversal beneath stat, retaining metadata compatibility.
It does not change Starship configuration or executable loading.

A disposable Arch fixture used the actual ARM64 Starship and Git binaries
from existing local artifacts. A committed repository contains 512 source
files, a modified tracked file and an untracked file. The prompt configuration
includes only Git branch/status. Both runtimes rendered the same `main|M1U1`
output. `GIT_OPTIONAL_LOCKS=0` prevents optional Git index updates during the
comparison. Both runs use the same rootfs, metadata, configuration and QEMU
settings (`QEMU_RESERVED_VA=0x2000000000`, `CNG_MMAP_FORCE_ANON=1`).

| Measurement | exp.36 | exp.37 |
| --- | ---: | ---: |
| Parent Starship component probes | 3,856 | 1,289 |
| Parent Starship full resolver calls | 801 | 287 |
| Parent Starship pin calls | 967 | 967 |
| Parent Starship metadata opens | 640 | 640 |
| Parent Starship metadata cache hits | 150 | 150 |
| Trace-wide native readlinkat calls | 4,887 | 2,317 |

The profile counters above cover the parent scope; native trace counts also
include subprocess work. Threaded QEMU trace lines interleave: syscall names
must be counted without assuming line boundaries. Return values cannot safely
be attributed to metadata opens by matching errno on the same line. Repeated
nonblocking pipe reads vary with scheduling, so aggregate filesystem-call
totals and QEMU wall times are not evidence of a phone speedup.

Most metadata records in this workload are first-time unique reads. This
change deliberately retains them and the existing cache validation. A larger
or less carefully validated cache would not address the demonstrated duplicate
component work and could break package-manager visibility.

## Implementation

`stat_plain_absolute` in `src/monitor/dispatch.c` handles canonical absolute
rootfs paths for `newfstatat`. It translates the current filesystem view and
uses the established pinned-parent lstat operation. That operation verifies
the opened parent against its canonical descriptor path, falling back to a
descriptor walk that rejects symlinks. A nonsymlink leaf can reuse this kernel
result for stat or lstat, followed by the existing ownership/mode fixups and
guest-buffer copy. A filesystem-view sequence check rejects a concurrent view
replacement before publishing a candidate.

Binds, proc/dev paths, final or parent symlinks, internal hardlink names,
noncanonical paths, invalid flags and unsuccessful candidates retain full
resolution. Relative paths and statx retain their existing paths. Nothing is
cached across operations; a replaced inode remains visible immediately. No
new optional syscall, startup capability probe or metadata format is added.

## Validation

The self-contained regression fixture exercises ordinary and noncanonical
paths, relative/absolute symlinks, a symlink loop, read-only binds, hardlink
identity, ownership/mode, invalid flags/buffers/descriptors, proc/dev paths,
external inode replacement, chroot view replacement and concurrent parent
directory/symlink replacement. It compiles a static PIE guest with warnings
treated as errors. A 512-file, three-pass comparison reduced component probes
from 3,074 to 2 while asserting all ownership, mode, size and type results.

```sh
python3 -B scripts/test-chroot-ng-stat-path.py \
  --guest-cc /path/to/aarch64-linux-gcc \
  --baseline-runtime /path/to/exp36-libchroot-ng.so
```

The baseline argument is optional. The profiling guest retains its canonical
stat checks and additionally stats `/./fixture` to explicitly exercise full
resolution; its original resolver-counter assertions remain unchanged.

Metadata compatibility, profiling scope/output, exp.34 SIGSYS reproduction
and fixed startup, hardlink directory enumeration, and actual Arch sudo/pacman
install, upgrade and removal passed. Logs are
`/tmp/tritonterm-exp37-{stat-path,runtime,profile,startup,dirent,packages}.log`.
The repository comparison logs and traces are under
`/tmp/tritonterm-repository-final-{baseline,current}` and corresponding `.log`
files. These disposable host fixtures are not installed phone measurements.

The broad runtime suite completed with 1,407 passes, four failed assertions
and 38 skips. Two failures are the previously observed high-bit openat2 QEMU
reference discrepancies. Two are the intermittent 60-mapping lazy-rewriter
check, reproduced independently with unchanged exp.36: after five initially
passing repeats each, another 50 runs failed nine times with exp.36 and eight
times with exp.37. The failed lazytest reports patching/running fewer than 60
mappings. That baseline issue is not fixed or waived by this stat optimization;
the full suite is not reported as entirely passing. The repeated comparison
is saved in `/tmp/tritonterm-exp37-lazy-comparison.log` and the broad result in
`/tmp/tritonterm-exp37-upstream.log`.

An absolute self-referential parent symlink produced an existing errno
mismatch in both unchanged exp.36 and the candidate during investigation;
that unrelated resolver behavior remains unresolved. The checked-in fixture
retains an exact ELOOP assertion for a relative self-referential parent.

Exp.37 uses the existing local development signer and rootfs pin. Install over
exp.36, reopen the shell with chroot-ng selected, and run the unchanged
`fish /tmp/tritonterm-profile.fish` twice. Compare repository component probes,
configured prompt wall times, and warmed Cargo builds. Both backends and the
installed Arch data are preserved; there is no rootfs rebuild or migration.

## First exp.37 phone report

The phone completed collection `runtime-profile.aaRarR` with live seccomp and
rewriting. Repository component probes fell from roughly 15,500 in exp.36 to
10,674, and full resolver calls from roughly 2,580 to 1,771 (about 31% fewer
in each case). Pin calls remained around 3,250, and metadata opens around
2,500. This confirms execution of the optimized path on the phone while
retaining the remaining metadata work.

Unprofiled repository wall times were 408.76, 148.11 and 163.27 ms, compared
with 132.58, 126.38 and 112.32 ms in the previous exp.36 second collection.
Minimal Starship took 24.02, 44.37 and 42.75 ms, and Elio's version query took
81.06, 41.62 and 39.10 ms. These controls were also generally slower than
the earlier collection. The sequential, uncontrolled results demonstrate
less path work, but no prompt latency improvement; they do not establish
whether the change caused the timing difference. Threaded timers overlap
and must not be summed into elapsed time.

Repeated phone measurements are needed before making a speedup claim.
Executable loading/copying and unique metadata/path operations remain
separate substantial targets; these data do not justify a speculative
persistent cache.
