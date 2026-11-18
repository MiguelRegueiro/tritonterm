#!/usr/bin/env python3
"""Validate hardlink directory views and compare native operation counts."""
import argparse
import json
import os
from pathlib import Path
import resource
import select
import threading
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--guest-cc', required=True)
    parser.add_argument('--qemu', default='qemu-aarch64-static')
    parser.add_argument('--runtime', type=Path, default=PROJECT / 'app/src/main/jniLibs/arm64-v8a/libchroot-ng.so')
    parser.add_argument('--baseline-runtime', type=Path)
    args = parser.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    with tempfile.TemporaryDirectory(prefix='cng-dirent-') as directory:
        work = Path(directory)
        root = work / 'root'
        for name in ('bin', 'tmp', 'view', '.l2s', 'legacy'):
            (root / name).mkdir(parents=True, exist_ok=True)
        subprocess.run([args.guest_cc, '-static-pie', '-O0', '-Wall', '-Wextra', '-Werror',
                        str(PROJECT / 'tests/chroot_ng_dirent_guest.c'), '-o', str(root / 'bin/dirent-probe')], check=True, timeout=60)
        env = dict(os.environ, CNG_TMP_ENV_ONLY='1', TMPDIR=str(root / 'tmp'), CNG_PROFILE_SUPPORT='1')
        # First compare ordinary PRoot groups, with many aliases of each group.
        # Target/metadata stay identical across runtimes; timings are not used.
        store = root / '.l2s'
        view = root / 'view'
        expected = {}
        def expect(name, inode_path, dtype):
            expected[name] = (inode_path.lstat().st_ino, dtype)
        def group(stem, count=2, payload='payload', fifo=False):
            ind = store / stem
            data = Path(str(ind) + f'.{count:04d}')
            if fifo: os.mkfifo(data)
            else: data.write_text(payload)
            ind.symlink_to(data)
            return ind, data
        for number in range(16):
            ind, data = group(f'.proot.l2s.group{number:04d}', count=16)
            for alias in range(16):
                name = f'group-{number}-{alias}'
                (view / name).symlink_to(ind)
                expect(name, data, 8)
        manifest = root / 'manifest'
        def save():
            manifest.write_text(''.join(f'{name} {ino} {dtype}\n' for name, (ino, dtype) in expected.items()))
        def command(runtime, capacity, directory='/view', mode=None):
            result = [args.qemu, str(runtime.resolve()), '-R', '--link2symlink',
                       '-u', '1000:1000', '-E', 'TRITONTERM_CNG_PROFILE=count', str(root),
                       '/bin/dirent-probe', directory, '/manifest', str(capacity)]
            if mode: result.append(mode)
            return result
        def run(runtime, capacity):
            p = subprocess.run(command(runtime, capacity), env=env, capture_output=True, text=True, timeout=60)
            assert p.returncode == 0 and p.stdout.startswith('PASS directory entries'), (p.returncode, p.stdout, p.stderr)
            rows = [json.loads(line.removeprefix('TRITONTERM_CNG_PROFILE ')) for line in p.stderr.splitlines() if line.startswith('TRITONTERM_CNG_PROFILE ')]
            assert len(rows) == 1 and rows[0]['status'] == 0 and not rows[0]['truncated'], rows
            return rows[0]['metrics']['pin'][0]
        save()
        current = run(args.runtime, 32768)
        if args.baseline_runtime:
            baseline = run(args.baseline_runtime, 32768)
            assert current < baseline // 3, (baseline, current)
            print(f'PASS 256 aliases, three listings: pin operations {baseline} -> {current}', flush=True)
        else:
            print(f'PASS 256 PRoot aliases, inode/type and repeated enumeration (pins={current})', flush=True)
        # Correctness cases must retain the existing slow/healing path too.
        plain = root / 'plain'; plain.write_text('ordinary')
        for name, target in [('ordinary-relative', '../plain'), ('ordinary-absolute', '/plain')]:
            link = view / name; link.symlink_to(target); expect(name, link, 10)
        ind, data = group('.l2s.12340000')  # ambiguous numeric spelling
        (view / 'numeric-group').symlink_to(ind); expect('numeric-group', data, 8)
        ind, data = group('.l2s.upstream0000')  # upstream non-UserLAnd prefix
        (view / 'upstream-prefix').symlink_to(ind); expect('upstream-prefix', data, 8)
        ind, data = group('.proot.l2s.fifo0000', fifo=True)
        (view / 'fifo-group').symlink_to(ind); expect('fifo-group', data, 1)
        # Existing CNG one-level data/marker format.
        data = store / 'legacy-data'; data.write_text('legacy')
        inode = data.stat().st_ino; renamed = store / f'.l2s.{inode}'; data.rename(renamed)
        (store / f'.l2s.{inode}.0002').touch()
        (view / 'cng-group').symlink_to(renamed); expect('cng-group', renamed, 8)
        regular_ind = store / '.proot.l2s.regular0000'; regular_ind.write_text('not an indirection')
        (view / 'regular-target').symlink_to(regular_ind); expect('regular-target', regular_ind, 8)
        # Missing, malformed, or symlink backing must stay symlinks in readdir.
        for number, target in enumerate(('missing', 'malformed', 'symlink', 'directory', 'outside')):
            ind = store / f'.proot.l2s.bad{number:04d}'
            data = Path(str(ind) + '.0002')
            if target == 'malformed': ind.symlink_to(store / '.proot.l2s.other0000.0002')
            else: ind.symlink_to(data)
            if target == 'symlink': data.symlink_to(plain)
            if target == 'directory': data.mkdir()
            if target == 'outside':
                outside = work / 'outside'; outside.mkdir()
                alien = outside / '.proot.l2s.alien0000'; alien.symlink_to(plain)
                ind.unlink(); ind = alien
            link = view / ('bad-' + target); link.symlink_to(ind); expect(link.name, link, 10)
        # Stale member and stale second-level store paths are accepted/healed
        # as the prior implementation did, using the current local backing.
        ind, data = group('.proot.l2s.stale0000')
        stale = view / 'stale-member'; stale.symlink_to(work / 'old-root/.l2s' / ind.name)
        expect(stale.name, data, 8)
        ind, data = group('.proot.l2s.second0000')
        ind.unlink(); ind.symlink_to(work / 'old-root/.l2s' / data.name)
        (view / 'stale-data-path').symlink_to(ind); expect('stale-data-path', data, 8)
        save()
        for capacity in (64, 256, 32768):
            run(args.runtime, capacity)
            if args.baseline_runtime: run(args.baseline_runtime, capacity)
        print('PASS both hardlink formats, numeric names, ordinary symlinks, stale groups, malformed/outside targets and small buffers', flush=True)
        # Keep the same guest and directory descriptor live across external
        # mutations. Rewind/reopen must observe each newly published view.
        with tempfile.TemporaryFile(mode='w+t') as errors:
            child = subprocess.Popen(command(args.runtime, 256, mode='live'), env=env,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, text=True)
            try:
                for step in range(3):
                    ready, _, _ = select.select([child.stdout], [], [], 30)
                    assert ready and child.stdout.readline().strip() == 'READY'
                    original = store / '.proot.l2s.group0000.0016'
                    if step == 0:
                        replacement = store / 'replacement'; replacement.write_text('new inode')
                        replacement.replace(original)
                        for alias in range(16): expect(f'group-0-{alias}', original, 8)
                    elif step == 1:
                        new = store / '.proot.l2s.group0000.0015'
                        original.rename(new)
                        ind = store / '.proot.l2s.group0000'
                        temporary = store / 'new-ind'; temporary.symlink_to(new); temporary.replace(ind)
                        (view / 'group-0-15').unlink(); expected.pop('group-0-15')
                    else:
                        ind = store / '.proot.l2s.group0000'
                        temporary = store / 'new-ind'; temporary.symlink_to(work / 'outside/forbidden'); temporary.replace(ind)
                        for alias in range(15):
                            name = f'group-0-{alias}'; expect(name, view / name, 10)
                    save()
                    child.stdin.write('\n'); child.stdin.flush()
                out, _ = child.communicate(timeout=30)
                errors.seek(0)
                assert child.returncode == 0 and out.startswith('PASS directory entries'), (out, errors.read())
            finally:
                if child.poll() is None: child.kill(); child.wait()
        print('PASS same-process inode replacement, count change/unlink and poisoned indirection visibility', flush=True)
        # A concurrent symlink swap of the indirection's parent must not let
        # path-based validation follow it outside the guest rootfs.
        parent = root / 'race-store'; parent.mkdir()
        ind = parent / '.proot.l2s.race0000'; data = Path(str(ind) + '.0001')
        data.write_text('inside'); ind.symlink_to(data)
        race = root / 'race-view'; race.mkdir()
        member = race / 'race'; member.symlink_to(ind)
        outside = work / 'outside-race'; outside.mkdir()
        alien = outside / ind.name; alien_data = Path(str(alien) + '.0001')
        alien_data.write_text('outside'); alien.symlink_to(alien_data)
        manifest.write_text(f'race {data.stat().st_ino} 8 {member.lstat().st_ino}\n')
        stop = threading.Event()
        failures = []
        def swap():
            held = root / 'race-store-held'
            try:
                while not stop.is_set():
                    parent.rename(held)
                    try:
                        parent.symlink_to(outside, target_is_directory=True)
                        parent.unlink()
                    finally:
                        if parent.is_symlink(): parent.unlink()
                        held.rename(parent)
            except Exception as error:
                failures.append(error)
        worker = threading.Thread(target=swap)
        worker.start()
        try:
            result = subprocess.run(command(args.runtime, 256, '/race-view', 'race'),
                                    env=env, capture_output=True, text=True, timeout=60)
            assert result.returncode == 0 and result.stdout.startswith('PASS concurrent'), (result.returncode, result.stdout, result.stderr)
        finally:
            stop.set(); worker.join(timeout=10)
        assert not failures and not worker.is_alive(), failures
        print('PASS concurrent parent-directory/symlink replacement preserves containment', flush=True)



if __name__ == '__main__':
    main()
