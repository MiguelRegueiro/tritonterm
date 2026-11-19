#!/usr/bin/env python3
"""Check statx result reuse, real fields, fallback, containment and counts."""
import argparse
import json
import os
from pathlib import Path
import resource
import select
import subprocess
import tempfile
import threading

PROJECT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--guest-cc', required=True)
    parser.add_argument('--runtime', type=Path, default=PROJECT / 'app/src/main/jniLibs/arm64-v8a/libchroot-ng.so')
    parser.add_argument('--baseline-runtime', type=Path)
    args = parser.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    with tempfile.TemporaryDirectory(prefix='cng-statx-path-') as folder:
        work = Path(folder); root = work / 'root'; meta = Path(str(root) + '.tritonterm-metadata')
        for name in ('bin', 'tmp', 'inside', 'jail/inside', 'tree', 'race'):
            (root / name).mkdir(parents=True, exist_ok=True)
        subprocess.run([args.guest_cc, '-static-pie', '-O0', '-Wall', '-Wextra', '-Werror',
            str(PROJECT / 'tests/chroot_ng_statx_guest.c'), '-o', str(root / 'bin/statx-probe')], check=True, timeout=60)
        subprocess.run([args.guest_cc, '-static-pie', '-O2', '-Wall', '-Wextra', '-Werror',
            '-Wno-unused-parameter', '-mgeneral-regs-only', '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections',
            '-I', str(PROJECT / 'third_party/fake-chroot-ng/include'),
            str(PROJECT / 'tests/chroot_ng_statx_candidate.c'), '-o', str(work / 'candidate')], check=True)
        p = subprocess.run(['qemu-aarch64-static', str(work / 'candidate')], capture_output=True, text=True, timeout=30)
        assert p.returncode == 0 and p.stdout.startswith('PASS'), p
        print(p.stdout.strip(), flush=True)
        def record(path, mode='640', uid=123, gid=456):
            relative = path.relative_to(root)
            target = meta / relative.parent / ('.proot-meta-file.' + relative.name)
            target.parent.mkdir(parents=True, exist_ok=True); target.write_text(f'{mode}\n{uid}\n{gid}\n')
        (root / 'inside/file').write_text('data'); record(root / 'inside/file')
        (root / 'jail/inside/file').write_text('jail')
        (root / 'parent-link').symlink_to('/inside')
        (root / 'relative-parent').symlink_to('inside')
        (root / 'final-link').symlink_to('/inside/file')
        (root / 'loop').symlink_to('loop')
        (root / 'bound-link').symlink_to('/bound')
        bound = work / 'bound'; bound.mkdir(); (bound / 'file').write_text('outside')
        # Query the same inode natively for timestamp/device/attribute fields.
        oracle = work / 'oracle.c'
        oracle.write_text('#define _GNU_SOURCE\n#include <assert.h>\n#include <stdio.h>\n'
            '#include <fcntl.h>\n#include <sys/stat.h>\n'
            'int main(int argc,char **argv){struct statx st;assert(argc==2);'
            'assert(statx(AT_FDCWD,argv[1],0,STATX_BASIC_STATS|STATX_BTIME,&st)==0);'
            'assert(fwrite(&st,sizeof st,1,stdout)==1);return 0;}\n')
        subprocess.run(['cc', '-O2', '-Wall', '-Wextra', '-Werror', str(oracle), '-o', str(work / 'oracle')], check=True)
        def write_oracle():
            path = root / 'inside/file'
            os.utime(path, ns=(1234567890123456789, 1234567890123456789))
            p = subprocess.run([str(work / 'oracle'), str(path)], capture_output=True, check=True)
            assert len(p.stdout) == 256
            (root / 'oracle').write_bytes(p.stdout)
        write_oracle()
        subprocess.run(['cc', '-O2', '-Wall', '-Wextra', '-Werror',
            str(PROJECT / 'tests/chroot_ng_statx_filter.c'), '-o', str(work / 'deny-statx')], check=True)
        for directory in range(16):
            for file in range(32):
                path = root / f'home/triton/.cargo/registry/src/index.fixture/crate{directory}/src/file{file}'
                path.parent.mkdir(parents=True, exist_ok=True); path.write_text('data'); record(path)
        env = dict(os.environ, CNG_PROFILE_SUPPORT='1', TRITONTERM_USERLAND_METADATA='1',
                   CNG_TMP_ENV_ONLY='1', TMPDIR=str(root / 'tmp'))
        def command(runtime, mode, *more):
            return ['qemu-aarch64-static', str(runtime.resolve()), '-R', '--link2symlink', '-u', '0:0',
                '-b', str(bound) + ':/bound:ro', '-E', 'TRITONTERM_CNG_PROFILE=count', str(root),
                '/bin/statx-probe', mode, *more]
        def run(runtime, mode):
            p = subprocess.run(command(runtime, mode), env=env, capture_output=True, text=True, timeout=60)
            assert p.returncode == 0 and p.stdout.startswith('PASS'), (p.returncode, p.stdout, p.stderr)
            rows = [json.loads(line.removeprefix('TRITONTERM_CNG_PROFILE ')) for line in p.stderr.splitlines() if line.startswith('TRITONTERM_CNG_PROFILE ')]
            assert len(rows) == 1 and rows[0]['status'] == 0 and not rows[0]['truncated'], rows
            return rows[0]
        # Stat semantics remain identical with the optional bind present.
        def reset_plain():
            # Correctness exercises link/unlink, which leaves the original
            # name as an emulated member. Start each runtime with an ordinary
            # inode rather than feeding the second one a changed fixture.
            path = root / 'inside/file'
            (root / 'inside/alias').unlink(missing_ok=True)
            path.unlink(); path.write_text('data'); record(path)
            write_oracle()
        run(args.runtime, 'correctness'); reset_plain()
        if args.baseline_runtime:
            run(args.baseline_runtime, 'correctness'); reset_plain()
        print('PASS statx real kernel fields, masks, sync flags, metadata, symlinks, binds, hardlinks and invalid arguments', flush=True)
        current = run(args.runtime, 'scan')
        if args.baseline_runtime:
            baseline = run(args.baseline_runtime, 'scan')
            before = baseline['metrics']['path_probes'][0]; after = current['metrics']['path_probes'][0]
            assert after < before // 3, (before, after)
            assert current['metrics']['pin'][0] < baseline['metrics']['pin'][0] * 3 // 4
            assert current['syscalls']['291'] == baseline['syscalls']['291'] == 1536
            print(f'PASS 1536 statx: probes {before} -> {after}, pins {baseline["metrics"]["pin"][0]} -> {current["metrics"]["pin"][0]}, unchanged metadata output', flush=True)
        for runtime in ([args.baseline_runtime] if args.baseline_runtime else []) + [args.runtime]:
            p = subprocess.run([str(work / 'deny-statx')] + command(runtime, 'denied'),
                env=env, capture_output=True, text=True, timeout=30)
            assert p.returncode == 0 and p.stdout.startswith('PASS'), p
        print('PASS kernel statx EPERM retains a working stat fallback', flush=True)
        with tempfile.TemporaryFile(mode='w+t') as errors:
            child = subprocess.Popen(command(args.runtime, 'live'), env=env, stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=errors, text=True)
            try:
                ready, _, _ = select.select([child.stdout], [], [], 30)
                assert ready and child.stdout.readline().strip() == 'READY'
                temporary = root / 'inside/replacement'; temporary.write_text('replacement')
                temporary.replace(root / 'inside/file')
                record(root / 'inside/file', mode='600', uid=789, gid=321)
                child.stdin.write('\n'); child.stdin.flush()
                out, _ = child.communicate(timeout=30); errors.seek(0)
                assert child.returncode == 0 and out.startswith('PASS'), (out, errors.read())
            finally:
                if child.poll() is None: child.kill(); child.wait()
        print('PASS same-process statx sees externally replaced inode and metadata', flush=True)
        run(args.runtime, 'chroot')
        print('PASS statx follows changed filesystem view', flush=True)
        data = root / 'race/file'; data.write_text('safe')
        outside = work / 'outside-race'; outside.mkdir(); (outside / 'file').write_text('forbidden')
        stop = threading.Event(); failures = []
        def swap():
            parent = root / 'race'; held = root / 'race-held'
            try:
                while not stop.is_set():
                    parent.rename(held)
                    try:
                        parent.symlink_to(outside, target_is_directory=True)
                        parent.unlink()
                    finally:
                        if parent.is_symlink(): parent.unlink()
                        held.rename(parent)
            except Exception as error: failures.append(error)
        inode = data.stat().st_ino
        worker = threading.Thread(target=swap); worker.start()
        try:
            p = subprocess.run(command(args.runtime, 'race', str(inode)), env=env,
                capture_output=True, text=True, timeout=60)
            assert p.returncode == 0 and p.stdout.startswith('PASS'), (p.returncode, p.stdout, p.stderr)
        finally:
            stop.set(); worker.join(timeout=10)
        assert not failures and not worker.is_alive(), failures
        print('PASS statx concurrent parent replacement preserves containment', flush=True)


if __name__ == '__main__':
    main()
