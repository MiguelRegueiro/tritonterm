#!/usr/bin/env python3
"""Run the packaged ARM64 runtime's metadata checks under qemu-user."""
import argparse
import os
from pathlib import Path
import shutil
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
        run([args.guest_cc, '-static-pie', '-O0', '-Wall', '-Wextra', '-Werror',
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
