#!/usr/bin/env python3
"""Run the packaged ARM64 runtime's metadata checks under qemu-user."""
import argparse
import os
from pathlib import Path
import shutil
import select
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]


def run(arguments, **kwargs):
    result = subprocess.run([str(a) for a in arguments], capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {arguments}\n{result.stdout}\n{result.stderr}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', type=Path, default=PROJECT / 'app/src/main/jniLibs/arm64-v8a/libchroot-ng.so')
    parser.add_argument('--guest-cc', required=True)
    parser.add_argument('--baseline-runtime', type=Path, help='Optional previous runtime for operation-count comparison')
    parser.add_argument('--qemu', default='qemu-aarch64-static')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='tritonterm-cng-check-') as directory:
        output = Path(directory)
        root = output / 'rootfs'
        for name in ('bin', 'home/test', 'tmp'):
            (root / name).mkdir(parents=True, exist_ok=True)
        (root / 'archive').write_text('archive payload')
        (root / 'archive').chmod(0o666)
        metadata = Path(str(root) + '.tritonterm-metadata')
        for name, mode, uid, gid in (('', '755', 0, 0), ('archive', '644', 0, 123),
                                     ('home', '755', 0, 0), ('home/test', '755', 11000, 11000),
                                     ('setid', '4755', 0, 0)):
            relative = Path(name)
            record = metadata / relative.parent / ('.proot-meta-file.' + (relative.name if name else 'root'))
            record.parent.mkdir(parents=True, exist_ok=True)
            record.write_text(f'{mode}\n{uid}\n{gid}\n')
        guest = root / 'bin/metadata-check'
        run([args.guest_cc, '-static-pie', '-pthread', '-O0', '-Wall', '-Wextra', '-Werror',
             PROJECT / 'tests/chroot_ng_metadata_guest.c', '-o', guest], timeout=60)
        shutil.copyfile(guest, root / 'setid')
        (root / 'setid').chmod(0o755)
        environment = dict(os.environ, TMPDIR=str(root / 'tmp'), CNG_TMP_ENV_ONLY='1',
                           TRITONTERM_USERLAND_METADATA='1')
        command = [args.qemu, args.runtime.resolve(), '-R', '--link2symlink', '-u', '11000:11000',
                   '-w', '/home/test', root, '/bin/metadata-check']
        log = run(command, env=environment, timeout=60)
        assert 'PASS metadata' in log
        assert not (metadata / 'home/test/work/.proot-meta-file.renamed').exists()
        print(log.strip())
        # Independent host writes while one guest process keeps its cache.
        record = metadata / '.proot-meta-file.archive'
        for cache in ('1', '0'):
            record.write_text('644\n0\n123\n')
            env = dict(environment, TRITONTERM_METADATA_CACHE=cache)
            with tempfile.TemporaryFile(mode='w+t') as errors:
                child = subprocess.Popen([str(a) for a in command + ['cache-changes']],
                                         env=env, stdin=subprocess.PIPE,
                                         stdout=subprocess.PIPE, stderr=errors, text=True)
                try:
                    old_stat = record.stat()
                    def replace():
                        temp = record.with_suffix('.replacement')
                        temp.write_text('600\n0\n321\n')
                        os.utime(temp, ns=(old_stat.st_atime_ns, old_stat.st_mtime_ns))
                        temp.replace(record)
                    def edit():
                        stamp = record.stat()
                        record.write_text('640\n0\n456\n')
                        os.utime(record, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
                    def recreate():
                        record.write_text('644\n0\n789\n')
                    def symlink():
                        alternate = record.with_suffix('.alternate')
                        alternate.write_text('600\n0\n999\n')
                        record.unlink()
                        record.symlink_to(alternate.name)
                    for mutate in (replace, edit, record.unlink, recreate, symlink):
                        ready, _, _ = select.select([child.stdout], [], [], 30)
                        assert ready and child.stdout.readline().strip() == 'READY'
                        mutate()
                        child.stdin.write('\n')
                        child.stdin.flush()
                    out, _ = child.communicate(timeout=30)
                    errors.seek(0)
                    assert child.returncode == 0, errors.read()
                    assert 'PASS live' in out
                finally:
                    if child.poll() is None:
                        child.kill()
                        child.wait()
            record.unlink()
            print(f'PASS external metadata mutations with cache={cache}')
        record.write_text('644\n0\n123\n')
        def cache_bucket(path):
            value = 2166136261
            for byte in str(path).encode():
                value = ((value ^ byte) * 16777619) & 0xffffffff
            return value % 256
        # Same hash slot, different records: a collision must never return
        # another path's uid/gid, even with concurrent callers or fork.
        for number in range(10000):
            alias = f'cache-collision-{number}'
            alias_record = metadata / ('.proot-meta-file.' + alias)
            if cache_bucket(alias_record) == cache_bucket(record):
                break
        else:
            raise AssertionError('No collision fixture found')
        (root / alias).write_text('collision fixture')
        alias_record.write_text('640\n0\n777\n')
        for cache in ('1', '0'):
            log = run(command + ['cache-threads', '/' + alias],
                      env=dict(environment, TRITONTERM_METADATA_CACHE=cache), timeout=60)
            assert 'PASS colliding' in log
            print(f'PASS colliding paths, threads and fork with cache={cache}')
        # QEMU tracing counts real monitor syscalls: this is an operation-count
        # assertion, not a phone timing benchmark. No Android device required.
        counts = {}
        for cache in ('0', '1'):
            trace_command = [args.qemu, '-strace'] + command[1:] + ['cache-loop']
            trace = subprocess.run([str(a) for a in trace_command],
                                   env=dict(environment, TRITONTERM_METADATA_CACHE=cache),
                                   capture_output=True, text=True, timeout=60)
            assert trace.returncode == 0, trace.stderr[-3000:]
            counts[cache] = sum('openat(' in line and '.proot-meta-file.archive' in line
                                for line in trace.stderr.splitlines())
        assert counts['0'] >= 500, counts
        assert counts['1'] <= 3, counts
        print(f"PASS repeated-record opens reduced {counts['0']} -> {counts['1']} with live validation")
        if args.baseline_runtime:
            operations = {}
            for name, runtime, cache in (
                    ('baseline', args.baseline_runtime, '0'),
                    ('path-reuse', args.runtime, '0'),
                    ('path-reuse+cache', args.runtime, '1')):
                trace_command = [args.qemu, '-strace', runtime.resolve()] + command[2:] + ['open-loop']
                trace = subprocess.run([str(a) for a in trace_command],
                                       env=dict(environment, TRITONTERM_METADATA_CACHE=cache),
                                       capture_output=True, text=True, timeout=60)
                assert trace.returncode == 0, trace.stderr[-3000:]
                calls = ('newfstatat(', 'readlinkat(', 'fstat(', 'openat(', 'read(', 'close(')
                operations[name] = sum(any(call in line for call in calls)
                                       for line in trace.stderr.splitlines())
            assert operations['path-reuse'] < operations['baseline'], operations
            assert operations['path-reuse+cache'] < operations['path-reuse'], operations
            print(f'PASS 100 opens, filesystem syscall counts: {operations}')
        source = output / 'disabled.c'
        source.write_text('''#include <assert.h>
#include <sys/stat.h>
int main(void) { struct stat s; assert(stat("/archive", &s)==0);
assert(s.st_uid==11000 && (s.st_mode&07777)==0666); return 0; }
''')
        run([args.guest_cc, '-static-pie', '-O0', source, '-o', root / 'bin/disabled'], timeout=60)
        environment.pop('TRITONTERM_USERLAND_METADATA')
        run([args.qemu, args.runtime.resolve(), '-R', '--link2symlink', '-u', '11000:11000', root, '/bin/disabled'],
            env=environment, timeout=60)
        print('PASS disabled metadata adapter retains upstream ownership behavior')
        print('QEMU verifies rewritten ARM64 execution; Android seccomp/SELinux still require device validation.')


if __name__ == '__main__':
    main()
