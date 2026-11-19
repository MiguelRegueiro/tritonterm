#!/usr/bin/env python3
"""Exercise cached execution, live source changes, bounds and fallback under QEMU."""
import argparse
import json
import os
from pathlib import Path
import resource
import select
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--guest-cc', required=True)
    ap.add_argument('--runtime', type=Path, default=ROOT / 'app/src/main/jniLibs/arm64-v8a/libchroot-ng.so')
    args = ap.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    with tempfile.TemporaryDirectory(prefix='cng-image-cache-') as directory:
        work = Path(directory); root = work / 'root'
        (root / 'bin').mkdir(parents=True); (root / 'tmp').mkdir()
        def build(name, value=42, bss=1):
            target = work / name
            subprocess.run([args.guest_cc, '-static-pie', '-Wl,-z,separate-code', '-O0', '-Wall', '-Wextra', '-Werror',
                '-DVALUE='+str(value), '-DBSS_MIB='+str(bss), ROOT / 'tests/chroot_ng_image_guest.c',
                '-o', target], check=True, timeout=60)
            return target
        subprocess.run([args.guest_cc, '-static-pie', '-O2', '-Wall', '-Wextra', '-Werror',
            '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections',
            '-I', ROOT / 'third_party/fake-chroot-ng/include',
            ROOT / 'tests/chroot_ng_hybrid_fallback.c',
            ROOT / 'third_party/fake-chroot-ng/src/rt/cache.S', '-o', work / 'fallback-check'], check=True)
        checked = subprocess.run(['qemu-aarch64-static', work / 'fallback-check'],
            capture_output=True, text=True, timeout=30)
        assert checked.returncode == 0 and checked.stdout.startswith('PASS'), checked
        print(checked.stdout.strip(), flush=True)
        subprocess.run([args.guest_cc, '-static-pie', '-O2', '-Wall', '-Wextra', '-Werror',
            '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections',
            '-I', ROOT / 'third_party/fake-chroot-ng/include',
            ROOT / 'tests/chroot_ng_image_descriptor.c',
            ROOT / 'third_party/fake-chroot-ng/src/loader/image_cache.c',
            ROOT / 'third_party/fake-chroot-ng/src/loader/elf.c',
            ROOT / 'third_party/fake-chroot-ng/src/monitor/rewrite.c',
            ROOT / 'third_party/fake-chroot-ng/src/rt/cache.S', '-o', work / 'descriptor-check'], check=True)
        checked = subprocess.run(['qemu-aarch64-static', work / 'descriptor-check', work / 'source.elf'],
            capture_output=True, text=True, timeout=30)
        assert checked.returncode == 0 and checked.stdout.startswith('PASS'), checked
        print(checked.stdout.strip(), flush=True)
        normal = build('normal'); changed = build('changed', 43); big = build('big', bss=48)
        shutil.copyfile(normal, root / 'bin/driver'); (root / 'bin/driver').chmod(0o755)
        for i in range(12):
            shutil.copyfile(normal, root / f'bin/w{i}'); (root / f'bin/w{i}').chmod(0o755)
        for i in range(3):
            shutil.copyfile(big, root / f'bin/b{i}'); (root / f'bin/b{i}').chmod(0o755)
        subprocess.run(['cc', '-O2', '-Wall', '-Wextra', '-Werror',
            ROOT / 'tests/chroot_ng_loader_filter.c', '-o', work / 'filter'], check=True)
        env = dict(os.environ, CNG_IMAGE_CACHE='1', CNG_PROFILE_SUPPORT='1',
                   TMPDIR=str(root / 'tmp'), CNG_TMP_ENV_ONLY='1')
        command = ['qemu-aarch64-static', str(args.runtime.resolve()), '-R', '--link2symlink',
            '-E', 'TRITONTERM_CNG_IMAGE_CACHE=0', str(root), '/bin/driver']
        with tempfile.TemporaryFile(mode='w+b') as errors:
            child = subprocess.Popen(command, env=env, stdin=subprocess.PIPE,
                stdout=subprocess.PIPE, stderr=errors, bufsize=0)
            position = 0
            def line():
                ready, _, _ = select.select([child.stdout], [], [], 30)
                assert ready, 'guest did not respond'
                return child.stdout.readline().decode().strip()
            try:
                assert line() == 'READY'
                def run(path, value=42, off=False):
                    nonlocal position
                    child.stdin.write((('OFF' if off else 'RUN')+' /bin/'+path+'\n').encode())
                    actual = line()
                    if actual != 'VALUE='+str(value):
                        errors.seek(0); raise AssertionError((actual, errors.read().decode(errors='replace')))
                    assert line() == 'DONE'
                    errors.seek(position); log = errors.read().decode(); position = errors.tell()
                    rows = [json.loads(s.split(' ', 1)[1]) for s in log.splitlines() if s.startswith('TRITONTERM_CNG_PROFILE ')]
                    assert len(rows) == 1 and rows[0]['status'] == 0 and not rows[0]['truncated'], log
                    return rows[0]['metrics']
                first = run('w0'); second = run('w0')
                assert first['image_misses'][0] == 1 and first['image_objects'][0] == 1, first
                assert second['image_hits'][0] == 1 and second['image_objects'][0] == 1, second
                off = run('w0', off=True)
                assert off['image_objects'][0] == off['image_hits'][0] == off['image_misses'][0] == 0
                assert second['copied_bytes'][0] < off['copied_bytes'][0] // 4, (second, off)
                assert second['rewrite_bytes'][0] == 0 and second['rewrite_sites'][0] == off['rewrite_sites'][0], (second, off)
                print(f"PASS warm hit: code copies {off['copied_bytes'][0]} -> {second['copied_bytes'][0]}, same rewritten sites/output", flush=True)
                child.stdin.write(b'PAR /bin/w10\n')
                assert [line() for _ in range(4)] == ['VALUE=42'] * 4
                assert line() == 'DONE'
                errors.seek(position); log = errors.read().decode(); position = errors.tell()
                parallel = [json.loads(s.split(' ', 1)[1]) for s in log.splitlines() if s.startswith('TRITONTERM_CNG_PROFILE ')]
                assert len(parallel) == len({r['pid'] for r in parallel}) == 4, log
                assert all(r['status'] == 0 and r['metrics']['image_objects'][0] == 1 for r in parallel)
                assert sum(r['metrics']['image_misses'][0] for r in parallel) == 1
                assert sum(r['metrics']['image_hits'][0] for r in parallel) == 3
                print('PASS concurrent cold requests publish one immutable image and reuse it safely', flush=True)
                # Same opened inode is overwritten in place; preserve mtime to
                # ensure a simple path/mtime cache would be insufficient.
                source = root / 'bin/w0'; stat = source.stat()
                assert len(changed.read_bytes()) == source.stat().st_size
                with source.open('r+b') as f: f.write(changed.read_bytes()); f.flush(); os.fsync(f.fileno())
                os.utime(source, ns=(stat.st_atime_ns, stat.st_mtime_ns))
                mutated = run('w0', 43)
                assert mutated['image_misses'][0] == 1, mutated
                assert run('w0', 43)['image_hits'][0] == 1
                replacement = root / 'bin/replacement'; shutil.copyfile(normal, replacement); replacement.chmod(0o755)
                replacement.replace(source)
                assert run('w0')['image_misses'][0] == 1
                print('PASS in-place same-size rewrite with restored mtime and atomic inode replacement stay fresh', flush=True)
                # A burst exceeding the kernel queue must discard all images
                # rather than silently lose change tracking. Alternate event
                # types so inotify cannot coalesce successive modifications.
                with source.open('ab') as f: f.write(b'\0')
                assert run('w0')['image_misses'][0] == 1
                queue = int(Path('/proc/sys/fs/inotify/max_queued_events').read_text())
                assert queue <= 262144, 'host queue unexpectedly large for this bounded fixture'
                with source.open('r+b') as f:
                    for i in range(queue // 2 + 128):
                        f.seek(-1, 2); f.write(bytes([i & 1])); f.flush()
                        source.chmod(0o750 if i & 1 else 0o755)
                flooded = run('w0')
                assert flooded['image_objects'][0] == 0 and flooded['image_fallbacks'][0] > 0, flooded
                assert run('w0')['image_misses'][0] == 1
                print('PASS inotify queue overflow discards cached images and recovers through fallback', flush=True)

                # More objects than the fixed slot count must evict the oldest;
                # touching the newest object must retain its cached image.
                for i in range(1, 10): assert run('w'+str(i))['image_misses'][0] == 1
                assert run('w9')['image_hits'][0] == 1
                assert run('w0')['image_misses'][0] == 1
                print('PASS bounded eight-entry LRU eviction with active-image execution preserved', flush=True)
                # Three ~49 MiB virtual images exceed the 128 MiB budget well
                # before the entry-count limit. Sparse BSS must remain zero.
                for i in range(3): assert run('b'+str(i))['image_misses'][0] == 1
                assert run('b2')['image_hits'][0] == 1
                assert run('b0')['image_misses'][0] == 1
                print('PASS byte-budget eviction independent of slot count and sparse BSS initialization', flush=True)
                child.stdin.close(); child.stdin = None
                child.wait(timeout=30); assert child.returncode == 0
            finally:
                if child.poll() is None: child.kill(); child.wait()

        # The cache must degrade to established loading under actual kernel
        # refusals, including a broker child killed by an ambient trap.
        for denial in ('errno', 'trap', 'kill', 'watch'):
            with tempfile.TemporaryFile(mode='w+b') as errors:
                p = subprocess.run([str(work / 'filter'), denial] + command,
                    input=b'RUN /bin/w0\nRUN /bin/w0\n', env=env,
                    stdout=subprocess.PIPE, stderr=errors, timeout=45)
                errors.seek(0); log = errors.read().decode(errors='replace')
                assert p.returncode == 0 and p.stdout == b'READY\nVALUE=42\nDONE\nVALUE=42\nDONE\n', (denial, p, log)
                rows = [json.loads(s.split(' ', 1)[1]) for s in log.splitlines() if s.startswith('TRITONTERM_CNG_PROFILE ')]
                assert len(rows) == 2 and all(r['status'] == 0 and r['metrics']['image_objects'][0] == 0 and r['metrics']['image_fallbacks'][0] > 0 for r in rows), (denial, rows)
                # The inherited SIGSYS handler translates TRAP to ENOSYS;
                # KILL_PROCESS exercises an actual broker transport loss.
                expected_stage = {'errno': 16, 'trap': 16, 'kill': 1, 'watch': 13}[denial]
                expected_error = {'errno': -1, 'trap': -38, 'kill': -5, 'watch': -1}[denial]
                assert all(r['image_cache']['stage'] == expected_stage and r['image_cache']['error'] == expected_error
                           and r['image_cache']['blocked'] == (denial != 'watch') for r in rows), (denial, rows)
                print(f'PASS {denial}: cache refusal preserves stdout/exit through anonymous fallback', flush=True)


        # An initial shell failure is inherited through fork/exec. Ordinary
        # child commands must skip cache RPC; explicit opt-in retries it.
        inherited = command.copy()
        inherited[inherited.index('TRITONTERM_CNG_IMAGE_CACHE=0')] = 'TRITONTERM_CNG_IMAGE_CACHE=1'
        for denial in ('errno', 'trap', 'kill'):
            p = subprocess.run([str(work / 'filter'), denial] + inherited,
                input=b'INHERIT /bin/w0\nINHERIT /bin/w0\nRUN /bin/w0\n', env=env,
                capture_output=True, timeout=45)
            assert p.returncode == 0 and p.stdout == b'READY\n' + b'VALUE=42\nDONE\n' * 3, p
            rows = [json.loads(s.split(' ', 1)[1]) for s in p.stderr.decode().splitlines()
                    if s.startswith('TRITONTERM_CNG_PROFILE ')]
            assert len(rows) == 3 and all(r['image_cache']['blocked'] for r in rows), rows
            assert all(r['metrics']['image_fetch'][0] == 0 for r in rows[:2]), rows
            assert rows[2]['metrics']['image_fetch'][0] > 0, rows
            print(f'PASS {denial}: inherited platform failure skips RPC; explicit opt-in retries', flush=True)


if __name__ == '__main__':
    main()
