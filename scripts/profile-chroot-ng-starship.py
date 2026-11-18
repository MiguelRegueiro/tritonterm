#!/usr/bin/env python3
"""Count native runtime syscalls for ARM64 Starship under QEMU (not phone timings).

Requires a provisioned rootfs containing /usr/bin/starship and its libraries.
No packages or rootfs images are downloaded. Failed syscall-rewriting runs under
QEMU's unavailable seccomp tier are rejected by checking the actual output.
"""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
ANSI = re.compile(r'\x1b\[[0-?]*[ -/]*[@-~]')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rootfs', required=True, type=Path)
    parser.add_argument('--runtime', type=Path,
                        default=PROJECT / 'app/src/main/jniLibs/arm64-v8a/libchroot-ng.so')
    parser.add_argument('--baseline-runtime', type=Path)
    parser.add_argument('--qemu', default='qemu-aarch64-static')
    parser.add_argument('--timezone', default='Europe/Madrid')
    parser.add_argument('--logs', type=Path, required=True)
    args = parser.parse_args()
    root = args.rootfs.resolve()
    assert (root / 'usr/bin/starship').is_file()
    (root / 'tmp').mkdir(exist_ok=True)
    args.logs.mkdir(parents=True, exist_ok=True)
    runtimes = [('current', args.runtime)]
    if args.baseline_runtime:
        runtimes.insert(0, ('baseline', args.baseline_runtime))
    with tempfile.TemporaryDirectory(prefix='starship-probe-', dir=root / 'tmp') as directory:
        configs = Path(directory)
        (configs / 'minimal.toml').write_text('add_newline = false\nformat = "$character"\n')
        (configs / 'clock.toml').write_text(
            'add_newline = false\nformat = "$time"\n[time]\n'
            'disabled = false\ntime_format = "%H:%M"\nformat = "$time"\n')
        rows = []
        for label, runtime in runtimes:
            for workload in ('minimal', 'clock'):
                guest_config = '/' + str((configs / (workload + '.toml')).relative_to(root))
                command = [args.qemu, '-strace', str(runtime.resolve()), '-R', '--link2symlink',
                           '-u', '11000:11000', '-w', '/home/triton']
                for key, value in [('HOME', '/home/triton'), ('PWD', '/home/triton'),
                                   ('PATH', '/usr/bin'), ('LANG', 'C.UTF-8'),
                                   ('TZ', args.timezone), ('STARSHIP_CONFIG', guest_config)]:
                    command += ['-E', f'{key}={value}']
                command += [str(root), '/usr/bin/starship', 'prompt']
                env = dict(os.environ, TRITONTERM_USERLAND_METADATA='1',
                           CNG_MMAP_FORCE_ANON='1', CNG_TMP_ENV_ONLY='1', TMPDIR=str(root / 'tmp'))
                for attempt in range(1, 4):
                    result = subprocess.run(command, env=env, capture_output=True, text=True,
                                            cwd=root / 'home/triton', timeout=60)
                    stem = args.logs / f'{label}-{workload}-{attempt}'
                    stem.with_suffix('.trace').write_text(result.stderr)
                    stem.with_suffix('.stdout').write_text(result.stdout)
                    output = ANSI.sub('', result.stdout).strip()
                    expected = (output == '❯') if workload == 'minimal' else bool(
                        re.fullmatch(r'\d\d:\d\d', output))
                    if result.returncode == 0 and expected:
                        break
                    print(f'Rejected {label}/{workload} attempt {attempt}: '
                          f'rc={result.returncode}, output={output!r}', flush=True)
                else:
                    raise RuntimeError('QEMU did not produce a valid guest run; see trace logs')
                calls = Counter(re.findall(r'\b([a-z][a-z0-9_]+)\(', result.stderr))
                row = {'runtime': label, 'workload': workload,
                       'pread64': calls['pread64'],
                       'individual_phdr_reads': len(re.findall(
                           r'\bpread64\([^,]+,[^,]+,56,', result.stderr)),
                       'filesystem_calls': sum(calls[k] for k in (
                           'openat', 'openat2', 'close', 'read', 'readlinkat',
                           'newfstatat', 'statx', 'fstat', 'getdents64', 'lseek'))}
                rows.append(row)
                print(json.dumps(row), flush=True)
        (args.logs / 'summary.json').write_text(json.dumps(rows, indent=2) + '\n')


if __name__ == '__main__':
    main()
