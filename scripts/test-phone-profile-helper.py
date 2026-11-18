#!/usr/bin/env python3
"""Exercise the phone Fish collector with controlled command/report fixtures."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MOCK = '''#!/usr/bin/python3
import json, os, sys
from pathlib import Path
name=Path(sys.argv[0]).name
mode=os.environ.get('TRITONTERM_CNG_PROFILE','off')
if name=='elio': print('elio 1.15.0')
else:
 config=Path(os.environ['STARSHIP_CONFIG']).read_text()
 print('12:34' if '[time]' in config else '❯')
if mode!='off' and os.environ.get('MOCK_BAD')!='missing':
 record={'v':1,'program':name,'pid':os.getpid(),'mode':mode,'reason':'exit','status':0,'wall_ns':1000,'to_entry_ns':500,'seccomp':1,'rewrite':1,'metrics':{'resolve':[2,20 if mode=='timing' else 0]},'syscalls':{'56':2},'truncated':int(os.environ.get('MOCK_BAD')=='truncated')}
 print('TRITONTERM_CNG_PROFILE '+json.dumps(record,separators=(',',':')),file=sys.stderr)
'''


def main():
    with tempfile.TemporaryDirectory(prefix='phone-profile-helper-') as directory:
        base = Path(directory)
        binaries = base / 'bin'
        binaries.mkdir()
        for name in ['elio', 'starship']:
            file = binaries / name
            file.write_text(MOCK)
            file.chmod(0o755)
        for scenario in ['normal', 'missing', 'truncated']:
            home = base / scenario
            (home / '.config').mkdir(parents=True)
            (home / 'elio').mkdir()
            (home / '.config/starship.toml').write_text('format = "$character"\n')
            env = dict(os.environ, HOME=str(home), PATH=str(binaries) + ':' + os.environ['PATH'],
                       MOCK_BAD=scenario, TRITONTERM_CNG_PROFILE='off')
            env.pop('STARSHIP_CONFIG', None)
            result = subprocess.run(['fish', '--no-config', str(ROOT / 'app/src/main/assets/profile-chroot-ng.fish')],
                                    env=env, cwd=home, capture_output=True, text=True, timeout=90)
            if scenario == 'normal':
                assert result.returncode == 0, result.stderr + result.stdout
                assert 'END TRITONTERM PROFILE' in result.stdout
                assert 'starship-repo' in result.stdout and 'off_ms:' in result.stdout
                reports = list((home / '.cache/tritonterm').glob('runtime-profile.*/summary.txt'))
                assert len(reports) == 1 and reports[0].stat().st_size > 100
                print('PASS portable Fish collector runs all workloads and prints/saves the summary')
            else:
                assert result.returncode != 0 and 'END TRITONTERM PROFILE' not in result.stdout
                print('PASS portable Fish collector rejects '+scenario+' reports')


if __name__ == '__main__':
    main()
