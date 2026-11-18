#!/usr/bin/env python3
"""Check feature-aware cache synchronization and isolated native image probes."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--guest-cc', required=True)
    parser.add_argument('--runtime', type=Path, default=ROOT / 'app/src/main/jniLibs/arm64-v8a/libchroot-ng.so')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='cng-loader-check-') as directory:
        work = Path(directory)
        root = work / 'root'
        (root / 'bin').mkdir(parents=True)
        (root / 'tmp').mkdir()
        subprocess.run([args.guest_cc, '-static-pie', '-O2', '-Wall', '-Wextra', '-Werror',
            '-DCNG_CACHE_TEST', ROOT / 'tests/chroot_ng_cache_guest.c',
            ROOT / 'third_party/fake-chroot-ng/src/rt/cache.S', '-o', work / 'cache-check'], check=True)
        for cpu in ('cortex-a53', 'max'):
            result = subprocess.run(['qemu-aarch64-static', '-cpu', cpu, work / 'cache-check'],
                                    capture_output=True, text=True, timeout=30)
            assert result.returncode == 0 and result.stdout.startswith('PASS'), result
            print(f'PASS {cpu}: {result.stdout.strip()}')
        subprocess.run([args.guest_cc, '-static-pie', '-pthread', '-O0', '-Wall', '-Wextra', '-Werror',
            ROOT / 'tests/chroot_ng_profile_guest.c', '-o', root / 'bin/guest'], check=True)
        (root / 'fixture').write_text('payload')
        subprocess.run(['cc', '-O2', '-Wall', '-Wextra', '-Werror', ROOT / 'tests/chroot_ng_loader_filter.c',
                        '-o', work / 'filter'], check=True)
        command = ['qemu-aarch64-static', str(args.runtime.resolve()), '-R', '--link2symlink',
                   '-E', 'TRITONTERM_CNG_LOADER_PROBE=1', '-E', 'TRITONTERM_CNG_PROFILE=off',
                   str(root), '/bin/guest']
        env = dict(os.environ, CNG_PROFILE_SUPPORT='1', CNG_TMP_ENV_ONLY='1', TMPDIR=str(root / 'tmp'))
        for label in ('control', 'errno', 'trap', 'hang', 'disabled'):
            prefix = [] if label in ('control', 'disabled') else [str(work / 'filter'), label]
            result = subprocess.run(prefix + command, env=dict(env, CNG_PROFILE_SUPPORT='0' if label == 'disabled' else '1'),
                                    capture_output=True, text=True, timeout=30)
            assert result.returncode == 7 and result.stdout == 'PROFILE_GUEST_OK\n', result
            rows = [json.loads(line.split(' ', 1)[1]) for line in result.stderr.splitlines()
                    if line.startswith('TRITONTERM_CNG_LOADER_PROBE ')]
            if label == 'disabled':
                assert not rows, result.stderr
            else:
                assert len(rows) == 1 and rows[0]['v'] == 1, result.stderr
                row = rows[0]
                if label == 'control': assert row == {'v': 1, 'stage': 9, 'error': 0, 'signal': 0}, row
                elif label == 'errno': assert row['stage'] == 1 and row['error'] == -1 and row['signal'] == 0, row
                elif label == 'trap': assert row['stage'] == 15 and row['signal'] == 31, row
                else: assert row['stage'] == 14 and row['error'] == -110, row
            print(f'PASS {label}: capability result preserves guest stdout/exit and parent survival')
        # The phone invokes the probe through emulated exec from its shell.
        # Activate only in the guest so startup does not probe as well.
        command[command.index('TRITONTERM_CNG_LOADER_PROBE=1')] = 'TRITONTERM_CNG_LOADER_PROBE=0'
        result = subprocess.run(command + ['loader-probe'], env=env,
                                capture_output=True, text=True, timeout=30)
        assert result.returncode == 7 and result.stdout == 'PROFILE_GUEST_OK\n', result
        rows = [json.loads(line.split(' ', 1)[1]) for line in result.stderr.splitlines()
                if line.startswith('TRITONTERM_CNG_LOADER_PROBE ')]
        assert rows == [{'v': 1, 'stage': 9, 'error': 0, 'signal': 0}], result.stderr
        print('PASS probe activation across emulated exec preserves command behavior')


if __name__ == '__main__':
    main()
