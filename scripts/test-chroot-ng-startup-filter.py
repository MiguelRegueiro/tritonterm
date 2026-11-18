#!/usr/bin/env python3
"""Exercise pre-monitor startup under Android-like openat2 syscall denial."""
import argparse
import os
from pathlib import Path
import resource
import signal
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--guest-cc', required=True)
    parser.add_argument('--host-cc', default='cc')
    parser.add_argument('--qemu', default='/usr/bin/qemu-aarch64-static')
    parser.add_argument('--runtime', type=Path, default=PROJECT / 'app/src/main/jniLibs/arm64-v8a/libchroot-ng.so')
    parser.add_argument('--baseline-runtime', type=Path, help='Optional failing exp.34 runtime; require SIGSYS reproduction')
    args = parser.parse_args()
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    with tempfile.TemporaryDirectory(prefix='cng-startup-filter-') as directory:
        work = Path(directory)
        root = work / 'root'
        (root / 'bin').mkdir(parents=True)
        (root / 'tmp').mkdir()
        source = work / 'startup.c'
        source.write_text('#include <stdio.h>\n#include <unistd.h>\n'
            'int main(void) {\n'
            '  if (getuid() != 1000 || geteuid() != 0) return 2;\n'
            '  puts("STARTUP_OK"); return 0;\n}\n')
        subprocess.run([args.guest_cc, '-static-pie', '-O0', '-Wall', '-Wextra', '-Werror',
                        str(source), '-o', str(root / 'bin/startup')], check=True, timeout=60)
        metadata = Path(str(root) + '.tritonterm-metadata') / 'bin'
        metadata.mkdir(parents=True)
        (metadata / '.proot-meta-file.startup').write_text('4755\n0\n0\n')
        wrapper = work / 'deny-openat2'
        subprocess.run([args.host_cc, '-O2', '-Wall', '-Wextra', '-Werror',
                        str(PROJECT / 'tests/chroot_ng_no_openat2.c'), '-o', str(wrapper)], check=True, timeout=60)
        environment = dict(os.environ, TMPDIR=str(root / 'tmp'), CNG_TMP_ENV_ONLY='1',
                           TRITONTERM_USERLAND_METADATA='1')
        def run(runtime, mode):
            command = [args.qemu, str(runtime.resolve()), '-R', '--link2symlink',
                       '--setuid-root', '--setgid-root', '-u', '1000:1000',
                       str(root), '/bin/startup']
            if mode != 'control':
                command = [str(wrapper)] + (['--trap'] if mode == 'trap' else []) + command
            return subprocess.run(command, env=environment, capture_output=True, text=True, timeout=30)
        if args.baseline_runtime:
            result = run(args.baseline_runtime, 'errno')
            assert result.returncode == 0 and result.stdout == 'STARTUP_OK\n', result
            result = run(args.baseline_runtime, 'trap')
            assert result.returncode == -signal.SIGSYS, result
            print('PASS exp.34 reproduction: errno denial starts; trap denial terminates with SIGSYS', flush=True)
        for mode in ('control', 'errno', 'trap'):
            result = run(args.runtime, mode)
            assert result.returncode == 0 and result.stdout == 'STARTUP_OK\n', result
            print(f'PASS {mode}: startup completes and metadata set-ID identity is preserved', flush=True)


if __name__ == '__main__':
    main()
