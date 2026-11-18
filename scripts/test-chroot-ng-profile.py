#!/usr/bin/env python3
"""Check opt-in profiling against the packaged ARM64 runtime under QEMU."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'TRITONTERM_CNG_PROFILE '


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--guest-cc', required=True)
    parser.add_argument('--runtime', type=Path, default=ROOT / 'app/src/main/jniLibs/arm64-v8a/libchroot-ng.so')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='cng-profile-check-') as directory:
        root = Path(directory) / 'root'
        (root / 'bin').mkdir(parents=True)
        (root / 'tmp').mkdir()
        (root / 'fixture').write_text('payload')
        subprocess.run([args.guest_cc, '-static-pie', '-pthread', '-O0', '-Wall', '-Wextra', '-Werror',
                        ROOT / 'tests/chroot_ng_profile_guest.c', '-o', root / 'bin/profile-guest'], check=True)
        def run(mode, *arguments, support=True):
            command = ['qemu-aarch64-static', str(args.runtime.resolve()), '-R', '--link2symlink',
                       '-E', 'TRITONTERM_CNG_PROFILE=' + mode, str(root), '/bin/profile-guest', *arguments]
            result = subprocess.run(command, capture_output=True, text=True, timeout=60,
                                    env=dict(os.environ, TMPDIR=str(root / 'tmp'),
                                             CNG_TMP_ENV_ONLY='1', CNG_PROFILE_SUPPORT='1' if support else '0'))
            records = [json.loads(line[len(PREFIX):]) for line in result.stderr.splitlines() if line.startswith(PREFIX)]
            return result, records
        off, records = run('off')
        assert off.returncode == 7 and off.stdout == 'PROFILE_GUEST_OK\n' and not records, (off, records)
        for mode in ('count', 'timing'):
            result, records = run(mode)
            assert result.returncode == off.returncode and result.stdout == off.stdout, result
            # A fork without exec must not publish a duplicate of its parent's counters.
            assert len(records) == 1, records
            record = records[0]
            assert record['program'] == 'profile-guest' and record['reason'] == 'exit'
            assert record['status'] == 7 and record['mode'] == mode and not record['truncated']
            assert record['wall_ns'] >= record['to_entry_ns'] > 0
            assert record['metrics']['resolve'][0] >= 120 and record['metrics']['rewrite_sites'][0] > 0
            assert sum(record['syscalls'].values()) > 0
            if mode == 'count': assert all(value[1] == 0 for value in record['metrics'].values())
            else: assert record['metrics']['resolve'][1] > 0
            print(f'PASS {mode}: unchanged stdout/exit, valid phases/counters, threads/fork scope')
        result, records = run('off', 'activate')
        assert result.returncode == 7 and len(records) == 1 and records[0]['mode'] == 'timing', (result, records)
        assert records[0]['metrics']['resolve'][0] >= 120
        assert records[0]['metrics']['stack'][0] > 0
        assert records[0]['metrics']['close_cloexec'][0] > 0
        assert records[0]['metrics']['exec_reset'][0] > 0
        print('PASS per-command guest environment activates profiling across emulated exec')
        result, records = run('timing', 'exec-off')
        assert result.returncode == 7 and len(records) == 1 and records[0]['reason'] == 'next_exec', (result, records)
        print('PASS exec into off disables reports without changing execution')
        result, records = run('count', 'exec-thread')
        assert result.returncode == 7 and [r['reason'] for r in records] == ['next_exec', 'exit'], (result, records)
        assert records[1]['metrics']['resolve'][0] >= 120
        print('PASS per-exec counters reset after threaded work')
        result, records = run('timing', 'exec-nonleader')
        assert result.returncode == 7 and [r['reason'] for r in records] == ['next_exec', 'exit'], (result, records)
        assert records[1]['metrics']['elf_map'][0] > 0 and records[1]['to_entry_ns'] > 0
        print('PASS nonleader exec transfers startup attribution to the commit thread')
        result, records = run('timing', 'exec-fail')
        assert result.returncode == 0 and result.stdout == 'PROFILE_EXEC_FAILED_OK\n'
        assert [r['reason'] for r in records] == ['next_exec', 'exec_failed'], records
        assert records[1]['status'] == -2
        print('PASS failed exec preserves errno and returns a labeled failure scope')
        result, records = run('timing', support=False)
        assert result.returncode == 7 and not records
        print('PASS launcher support disabled leaves profiling inactive')
        # Close the stderr reader only after startup warnings are complete.
        # An enabled report must preserve exit status rather than raise SIGPIPE.
        import select
        for mode in ('off', 'timing'):
            reader, writer = os.pipe()
            command = ['qemu-aarch64-static', str(args.runtime.resolve()), '-R',
                       '-E', 'TRITONTERM_CNG_PROFILE=' + mode,
                       str(root), '/bin/profile-guest', 'pipe']
            process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                       stderr=writer, text=True,
                                       env=dict(os.environ, TMPDIR=str(root / 'tmp'),
                                                CNG_TMP_ENV_ONLY='1', CNG_PROFILE_SUPPORT='1'))
            os.close(writer)
            try:
                ready, _, _ = select.select([process.stdout], [], [], 30)
                assert ready and process.stdout.readline().strip() == 'READY'
                os.close(reader)
                reader = -1
                process.communicate('\n', timeout=30)
                assert process.returncode == 7, process.returncode
            finally:
                if reader >= 0: os.close(reader)
                if process.poll() is None: process.kill(); process.wait()
        print('PASS report I/O to a closed pipe preserves the command exit status')
        print('PASS native profile records parse as complete JSON')


if __name__ == '__main__':
    main()
