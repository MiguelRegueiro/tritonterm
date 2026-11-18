#!/usr/bin/env python3
"""Offline sudo/pacman transaction test in a disposable provisioned Arch fixture.

The supplied rootfs must be a test copy, with sudo, pacman, their dependencies,
PAM helpers, /usr/sbin alias, an account named triton (1000:1000), the usual
TritonTerm pacman NOPASSWD rule and the existing sidecar metadata tree.
Nothing is downloaded and the installed phone/rootfs is never accessed.
"""
import argparse
import io
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile

PROJECT = Path(__file__).resolve().parents[1]


def package(path, version, name):
    with tarfile.open(path, 'w') as archive:
        def add(name, data, mode=0o644, uid=0, gid=0):
            member = tarfile.TarInfo(name)
            member.mode, member.uid, member.gid = mode, uid, gid
            member.size = len(data)
            archive.addfile(member, io.BytesIO(data))
        add('.PKGINFO', (f'pkgname = {name}\npkgver = {version}-1\n'
            'pkgdesc = Offline runtime regression fixture\nurl = https://example.invalid\n'
            'builddate = 1\npackager = runtime test\nsize = 16\narch = aarch64\nlicense = MIT\n').encode())
        add('.INSTALL', b'post_install() { printf installed > /tmp/tritonterm-pkg-script; }\n'
            b'post_upgrade() { printf upgraded > /tmp/tritonterm-pkg-script; }\n'
            b'post_remove() { printf removed > /tmp/tritonterm-pkg-script; }\n')
        base = 'usr/share/' + name
        for directory in ('usr', 'usr/share', base):
            member = tarfile.TarInfo(directory + '/')
            member.type, member.mode = tarfile.DIRTYPE, 0o755
            archive.addfile(member)
        add(base + '/payload', version.encode(), uid=123, gid=456)
        link = tarfile.TarInfo(base + '/hard')
        link.type, link.linkname, link.mode = tarfile.LNKTYPE, base + '/payload', 0o644
        archive.addfile(link)
        link = tarfile.TarInfo(base + '/symbolic')
        link.type, link.linkname = tarfile.SYMTYPE, 'payload'
        archive.addfile(link)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rootfs', required=True, type=Path)
    parser.add_argument('--runtime', type=Path, default=PROJECT / 'app/src/main/jniLibs/arm64-v8a/libchroot-ng.so')
    parser.add_argument('--qemu', default='qemu-aarch64-static')
    args = parser.parse_args()
    root = args.rootfs.resolve()
    assert (root / 'usr/bin/sudo').is_file() and (root / 'usr/bin/pacman').is_file()
    name = 'tritonterm-runtime-smoke'
    assert not (root / 'usr/share' / name).exists(), 'Use a clean disposable fixture'
    # Fix QEMU's guest address-space limit for this correctness fixture.
    # Unconstrained layouts can miss rewriting and QEMU has no SIGSYS floor.
    environment = dict(os.environ, QEMU_RESERVED_VA=os.environ.get('QEMU_RESERVED_VA', '0x2000000000'),
                       TRITONTERM_USERLAND_METADATA='1', CNG_MMAP_FORCE_ANON='1',
                       CNG_TMP_ENV_ONLY='1', TMPDIR=str(root / 'tmp'))
    command = [args.qemu, str(args.runtime.resolve()), '-R', '--link2symlink',
        '--setuid-root', '--setgid-root', '-u', '1000:1000', '-w', '/home/triton',
        '--env=USER=triton', '--env=LOGNAME=triton', '--env=HOME=/home/triton',
        '--env=PATH=/usr/bin', str(root)]
    def run(*arguments):
        result = subprocess.run(command + list(arguments), env=environment, capture_output=True,
                                text=True, timeout=90)
        assert result.returncode == 0, (arguments, result.returncode, result.stdout, result.stderr)
        return result.stdout
    def pacman(*arguments):
        return run('/usr/bin/sudo', '-n', '/usr/bin/pacman', *arguments)
    assert 'Pacman' in pacman('--version')
    print('PASS actual Arch sudo launches pacman', flush=True)
    with tempfile.TemporaryDirectory(prefix='package-test-', dir=root / 'tmp') as directory:
        for version, marker in [('1', 'installed'), ('2', 'upgraded')]:
            path = Path(directory) / f'{name}-{version}-1-aarch64.pkg.tar'
            package(path, version, name)
            guest = '/tmp/' + Path(directory).name + '/' + path.name
            pacman('-U', '--noconfirm', guest)
            assert (root / 'tmp/tritonterm-pkg-script').read_text() == marker
            assert run('/usr/bin/cat', '/usr/share/' + name + '/payload') == version
            assert run('/usr/bin/cat', '/usr/share/' + name + '/hard') == version
            assert run('/usr/bin/cat', '/usr/share/' + name + '/symbolic') == version
            fields = run('/usr/bin/stat', '-c', '%u:%g:%a:%h', '/usr/share/' + name + '/payload').strip()
            assert fields == '123:456:644:2', fields
            assert version + '-1' in pacman('-Q', name)
            print(f'PASS sudo pacman {marker}: data, hardlink, symlink, ownership and install script', flush=True)
        pacman('-R', '--noconfirm', name)
        assert not (root / 'usr/share' / name).exists()
        assert (root / 'tmp/tritonterm-pkg-script').read_text() == 'removed'
        print('PASS sudo pacman removal and post-remove script', flush=True)


if __name__ == '__main__':
    main()
