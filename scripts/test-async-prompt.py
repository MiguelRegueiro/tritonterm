#!/usr/bin/env python3
"""Exercise the shipped Fish adapter in real PTYs with slow, observable Starship calls."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import signal
import struct
import subprocess
import tempfile
import termios
import time

ROOT = Path(__file__).resolve().parents[1]
MOCK = r'''#!/usr/bin/python3
import json,os,sys,time
from pathlib import Path
args=sys.argv[1:]
if args[0]=='init': print(Path(os.environ['TEST_INIT']).read_text());sys.exit()
config=Path(os.environ['STARSHIP_CONFIG']).read_text()
if args[0]=='print-config': print('right_format = "x"' if 'RIGHT' in config else 'right_format = ""');sys.exit()
control=json.loads(Path(os.environ['TEST_CONTROL']).read_text())
def log(event):
 data=json.dumps(dict(event=event,pid=os.getpid(),cwd=os.getcwd(),args=args,config=config,time=time.monotonic()))+'\n'
 fd=os.open(os.environ['TEST_LOG'],os.O_CREAT|os.O_WRONLY|os.O_APPEND,0o600);os.write(fd,data.encode());os.close(fd)
log('start')
time.sleep(control.get('delay',.08))
if control.get('hang'): time.sleep(20)
if control.get('fail'): sys.exit(1)
right='--right' in args
if not right or not control.get('empty_right'):
 print(('R' if right else 'L')+'|'+os.path.basename(os.getcwd())+'|'+('B' if '# B' in config else 'A')+'|'+next((x for x in args if x.startswith('--status=')),'')+'> ',end='')
log('end')
'''

class Shell:
    def __init__(self, folder, init_script=None, custom=False, optout=False, wrapped=False, theme=False):
        self.work=folder;folder.mkdir();self.log=folder/'calls';self.control=folder/'control';self.config=folder/'starship.toml'
        self.control.write_text('{}');self.config.write_text((ROOT/'scripts/arch-image/starship.toml').read_text() if theme else 'format="$character"\n# A\n')
        wrapper=folder/'starship';wrapper.write_text(MOCK);wrapper.chmod(0o755)
        original=Path(init_script).read_text() if init_script else subprocess.check_output(['starship','init','fish','--print-full-init'],text=True)
        import re
        original=re.sub(r'/[^\s;]+/starship(?= prompt|$)',str(wrapper),original,flags=re.M)
        self.init=folder/'init.fish';self.init.write_text(original)
        self.master,slave=pty.openpty();self.data=bytearray()
        fcntl.ioctl(slave,termios.TIOCSWINSZ,struct.pack('HHHH',24,100,0,0))
        env=dict(os.environ,PATH=str(folder)+':'+os.environ['PATH'],TERM='xterm-256color',TEST_INIT=str(self.init),TEST_LOG=str(self.log),TEST_CONTROL=str(self.control),STARSHIP_CONFIG=str(self.config),XDG_CONFIG_HOME=str(folder/'xdg'),XDG_CACHE_HOME=str(folder/'cache'),XDG_DATA_HOME=str(folder/'data'))
        if optout:env['TRITONTERM_ASYNC_PROMPT']='0'
        command=f'source {self.init}; source {ROOT}/app/src/main/assets/tritonterm-async-prompt.fish'
        if custom:command+='; function fish_prompt; printf "CUSTOM> "; end'
        if wrapped:command+='; functions -c fish_prompt saved_prompt; function fish_prompt; printf "WRAPPED|"; saved_prompt; string match -q never "starship prompt"; end'
        self.process=subprocess.Popen(['fish','--no-config','--interactive','--init-command',command],stdin=slave,stdout=slave,stderr=slave,env=env,start_new_session=True,preexec_fn=lambda:fcntl.ioctl(0,termios.TIOCSCTTY,0))
        os.close(slave)
    def pump(self,seconds=.03):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            ready,_,_=select.select([self.master],[],[],min(.02,max(0,end-time.monotonic())))
            if ready:
                try:b=os.read(self.master,65536)
                except OSError:return
                self.data.extend(b)
                if b'\x1b[6n' in b:os.write(self.master,b'\x1b[1;1R')
                if b'\x1b[0c' in b or b'\x1b[c' in b:os.write(self.master,b'\x1b[?1;2c')
    def wait(self,condition,timeout=10):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            if condition():return
            self.pump()
        raise AssertionError(bytes(self.data[-5000:]).decode(errors='replace'))
    def records(self):return [json.loads(x) for x in self.log.read_text().splitlines()] if self.log.exists() else []
    def send(self,text):os.write(self.master,(text+'\n').encode())
    def ready(self,after=0,**expected):
        def match(r):return r['event']=='end' and '--right' not in r['args'] and all(any(a==f'--{k}={v}' for a in r['args']) for k,v in expected.items())
        self.wait(lambda:any(match(r) for r in self.records()[after:]))
        self.pump(.12)
    def mode(self,**values):self.control.write_text(json.dumps(values))
    def close(self):
        self.send('exit');self.pump(.2)
        try:self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:self.process.kill();self.process.wait()
        os.close(self.master)
    def snapshot(self):
        target=self.work/'state';self.send(f'printf "%s\\n" $__triton_prompt_dir >{target}')
        self.wait(target.exists);return target.read_text().strip()

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--init-script');args=ap.parse_args()
    with tempfile.TemporaryDirectory(prefix='tritonterm-async-test-') as d:
        work=Path(d);s=Shell(work/'normal',args.init_script)
        try:
            s.ready();assert all('--right' not in r['args'] for r in s.records())
            print('PASS default empty right format avoids right rendering',flush=True)
            # Bracketed paste is a single multiline command/event, as on Android.
            gen_before=work/'generation-before';gen_after=work/'generation-after'
            before=len(s.records())
            block=f'printf "%s" $__triton_prompt_generation >{gen_before}\ntrue\nfalse | true'
            os.write(s.master,('\x1b[200~'+block+'\x1b[201~\n').encode())
            s.ready(before,status=0,pipestatus='1 0')
            before=len(s.records());s.send(f'printf "%s" $__triton_prompt_generation >{gen_after}')
            s.wait(lambda:gen_after.exists() and bool(gen_after.read_text()))
            assert int(gen_after.read_text())==int(gen_before.read_text())+1
            s.ready(before,status=0)
            assert b'test: unexpected argument' not in s.data
            print('PASS multiline pasted commands advance generation once without hook errors',flush=True)

            for command,status,pipes in [('false',1,'1'),('false | true',0,'1 0'),('true | false',1,'0 1'),('not false',0,'1')]:
                before=len(s.records());s.send(command);s.ready(before,status=status,pipestatus=pipes)
            print('PASS failed commands, pipelines and negated status survive async rendering',flush=True)
            state=work/'last-status';before=len(s.records());s.send('false');s.ready(before,status=1)
            before=len(s.records());s.send(f'printf "%s" $status >{state}');s.wait(state.exists);assert state.read_text()=='1'
            s.ready(before,status=0)
            print('PASS command status remains available to the next user command',flush=True)
            before=len(s.records());s.send('');s.pump(.12)
            assert len(s.records())==before,(before,s.records())
            print('PASS unchanged blank Enter reuses the ready prompt',flush=True)
            s.pump(1.05);s.send('');s.ready(before)
            print('PASS a requested refresh after expiry observes external changes',flush=True)
            before=len(s.records())
            s.mode(delay=1.2);marker=work/'input-marker';start=time.monotonic()
            os.write(s.master,('\n'*20+f'printf done >{marker}\n').encode());s.wait(marker.exists,timeout=.9)
            assert time.monotonic()-start<.9
            s.ready(before)
            print('PASS 20 rapid Enters preserve subsequent command execution without awaiting a 1.2s render',flush=True)
            a=work/'dirA';b=work/'dirB';c=work/'dirC'
            for p in (a,b,c):p.mkdir()
            before=len(s.records());s.send(f'cd {a}')
            s.wait(lambda:any(r['event']=='start' and r['cwd']==str(a) for r in s.records()[before:]))
            s.send(f'cd {b}');s.send(f'cd {c}')
            s.wait(lambda:any(r['event']=='end' and r['cwd']==str(c) for r in s.records()[before:]),timeout=6);s.pump(.1)
            rows=s.records()[before:];assert not any(r['cwd']==str(b) for r in rows),rows
            assert bytes(s.data).rfind(b'L|dirC|')>bytes(s.data).rfind(b'L|dirA|')
            print('PASS active refreshes coalesce and latest directory wins',flush=True)
            s.mode(delay=.08);s.config.write_text('format="$character"\nright_format="x"\n# RIGHT B\n')
            before=len(s.records());s.send('');s.ready(before)
            s.wait(lambda:any(r['event']=='end' and '--right' in r['args'] for r in s.records()[before:]))
            print('PASS config content changes enable a custom right format',flush=True)
            s.mode(delay=.08,empty_right=True);before=len(s.records());s.send('true');s.ready(before)
            s.wait(lambda:any(r['event']=='end' and '--right' in r['args'] for r in s.records()[before:]))
            s.mode(delay=.08);before=len(s.records());s.send('true');s.ready(before)
            s.wait(lambda:any(r['event']=='end' and '--right' in r['args'] for r in s.records()[before:]))
            print('PASS conditionally empty right output does not disable its configured format',flush=True)
            s.config.write_text('format="$character"\n# A\n');before=len(s.records());s.send('true');s.ready(before)
            created=work/'created-pid';later=work/'later-pid';jobs=work/'jobs'
            before=len(s.records());s.send(f'sleep 8 &; printf "%s" $last_pid >{created}');s.ready(before,jobs=1)
            s.send(f'printf "%s" $last_pid >{later}; jobs -p >{jobs}');s.wait(later.exists);s.wait(jobs.exists)
            assert created.read_text()==later.read_text();assert jobs.read_text().split()==[created.read_text()]
            print('PASS user jobs and last_pid exclude the internal worker',flush=True)
            before=len(s.records());fcntl.ioctl(s.master,termios.TIOCSWINSZ,struct.pack('HHHH',24,77,0,0));os.kill(s.process.pid,signal.SIGWINCH);s.ready(before,**{'terminal-width':77})
            print('PASS changed terminal width reaches the renderer',flush=True)
            active=peak=0
            for row in s.records():
                active += 1 if row['event']=='start' else -1;peak=max(peak,active)
            assert peak==1,(peak,s.records())
            print('PASS at most one Starship renderer is active',flush=True)
            s.mode(delay=.4);before=len(s.records());s.send('true')
            s.wait(lambda:any(r['event']=='start' for r in s.records()[before:]))
            typed=work/'typed';os.write(s.master,f'printf typed >{typed}'.encode());s.pump(.7);before=len(s.records());os.write(s.master,b'\n');s.wait(typed.exists);assert typed.read_text()=='typed'
            s.ready(before,status=0)
            print('PASS completion repaint preserves the unsubmitted command line',flush=True)
            s.mode(hang=True);before=len(s.records());s.send('true')
            s.wait(lambda:any(r['event']=='start' for r in s.records()[before:]));s.pump(4.2)
            marker2=work/'after-timeout';s.send(f'printf alive >{marker2}');s.wait(marker2.exists,timeout=.8)
            before=len(s.records());s.mode(delay=.08);s.pump(2.1);s.send('');s.ready(before)
            print('PASS renderer deadline/backoff preserves input and permits recovery',flush=True)
            before=len(s.records());s.send('set -g TRITONTERM_ASYNC_PROMPT 0');s.ready(before)
            before=len(s.records());s.send('false | true');s.ready(before,status=0,pipestatus='1 0')
            before=len(s.records());s.send('not false');s.ready(before,status=0,pipestatus='1')
            print('PASS live synchronous opt-out preserves pipeline and negated status',flush=True)
            before=len(s.records());s.send('set -e TRITONTERM_ASYNC_PROMPT');s.ready(before)
            transient=work/'transient';rtransient=work/'right-transient'
            s.send(f'function starship_transient_prompt_func; printf "%s\\n" $argv >{transient}; end; function starship_transient_rprompt_func; printf "%s\\n" $argv >{rtransient}; end; false | true; fish_prompt --final-rendering >/dev/null; not false; fish_right_prompt --final-rendering >/dev/null')
            s.wait(transient.exists);s.wait(rtransient.exists)
            assert '--status=0' in transient.read_text() and '--pipestatus=1 0' in transient.read_text()
            assert '--status=0' in rtransient.read_text() and '--pipestatus=1' in rtransient.read_text()
            print('PASS transient prompt hooks retain pipeline and negated status',flush=True)
            before=len(s.records());s.send('set -e TRITONTERM_ASYNC_PROMPT');s.ready(before)
            s.mode(delay=1);before=len(s.records());s.send('true');s.wait(lambda:any(r['event']=='start' for r in s.records()[before:]))
            directory=s.snapshot()
            running=[r['pid'] for r in s.records()[before:] if r['event']=='start']
            s.close();assert not Path(directory).exists(),(directory,s.process.returncode,bytes(s.data[-3000:]))
            def stopped(pid):
                try:return Path(f'/proc/{pid}/stat').read_text().rsplit(')',1)[1].split()[0]=='Z'
                except FileNotFoundError:return True
            deadline=time.monotonic()+2
            while not all(stopped(pid) for pid in running) and time.monotonic()<deadline:time.sleep(.02)
            assert all(stopped(pid) for pid in running),running
            print('PASS shell exit removes session files and stops its worker',flush=True)
            assert b'warning: An error occurred' not in s.data
        finally:
            if s.process.poll() is None:s.close()
        s=Shell(work/'themed',args.init_script,theme=True)
        try:
            import re, tomllib
            def plain(text):return re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', text)
            def quote(path):
                return subprocess.check_output(['fish','--no-config','-c','string escape -- $argv',str(path)],text=True).rstrip('\n')
            s.ready();s.mode(delay=1.2)
            destination=work/'one/two/three with spaces/雪';destination.mkdir(parents=True)
            offset=len(s.data);before=len(s.records());s.send('cd '+quote(destination))
            s.wait(lambda:any(r['event']=='start' and r['cwd']==str(destination) for r in s.records()[before:]))
            assert ''.encode() in s.data[offset:]
            preview=work/'preview';s.send(f'__triton_prompt_pending 7 0 >{preview}')
            s.wait(lambda:preview.exists() and '➜' in preview.read_text())
            frame=preview.read_text()
            assert plain(frame)==' two/three with spaces/雪  … \n[7] ➜ ',repr(plain(frame))
            theme=tomllib.loads(s.config.read_text())
            assert theme['format']==('$cmd_duration[](fg:overlay0)$directory[](fg:overlay0 bg:git)'
                '$git_branch$git_status[](fg:git bg:runtime)$python$nodejs$rust$golang$docker_context'
                '[](fg:runtime bg:time_bg)$time[](fg:time_bg)$line_break$status$character')
            assert theme['directory']['truncation_length']==3 and theme['directory']['truncate_to_repo'] is False
            assert theme['directory']['home_symbol']=='~' and theme['directory']['format']=='[ $path ]($style)'
            assert theme['character']['success_symbol']==theme['character']['error_symbol']=='[➜](bold fg:phone)'
            palette=theme['palettes']['prompt']
            for name in ('overlay0','git','runtime','time_bg','foreground','phone','error'):
                color=palette[name].lstrip('#');rgb=';'.join(str(int(color[n:n+2],16)) for n in (0,2,4))
                assert rgb in frame,(name,frame)
            print('PASS pending app-theme frame retains palette, two-line shape, status and current truncated Unicode path',flush=True)
            marker=work/'themed-input';start=time.monotonic();s.send(f'printf alive >{marker}')
            s.wait(marker.exists,timeout=.8);assert time.monotonic()-start<.8
            s.wait(lambda:any(r['event']=='end' and r['cwd']==str(destination) for r in s.records()[before:]))
            s.pump(1.4)
            s.mode(delay=.08)
            duration=work/'duration';s.send(f'__triton_prompt_pending 0 1200 >{duration}')
            s.wait(lambda:duration.exists() and '➜' in duration.read_text())
            assert plain(duration.read_text()).startswith('…\n ')
            assert '[0]' not in plain(duration.read_text())
            color=palette['lavender'].lstrip('#')
            assert ';'.join(str(int(color[n:n+2],16)) for n in (0,2,4)) in duration.read_text()
            print('PASS pending rendering stays responsive and reserves the slow-command duration line',flush=True)
            for directory,expected in [(os.environ['HOME'],'~'),('/','/')]:
                path_preview=work/'path-preview';path_preview.unlink(missing_ok=True)
                s.send(f'cd {quote(directory)}; __triton_prompt_pending_path >{path_preview}')
                s.wait(lambda:path_preview.exists() and bool(path_preview.read_text()))
                assert path_preview.read_text()==expected,path_preview.read_text()
            print('PASS pending paths abbreviate home and retain the root directory',flush=True)

            unsafe=work/'line\n\x1b[31m';unsafe.mkdir()
            s.send('cd '+quote(unsafe));s.pump(.3)
            control=work/'control-preview';s.send(f'__triton_prompt_pending 0 0 >{control}')
            s.wait(lambda:control.exists() and '➜' in control.read_text())
            text=plain(control.read_text());assert 'line??[31m' in text,repr(text)
            assert text.count('\n')==1,repr(text)
            print('PASS pending directory names cannot inject terminal controls or extra lines',flush=True)
            s.config.write_text('format="$character"\n# custom theme\n');s.mode(delay=1.2)
            offset=len(s.data);before=len(s.records());s.send('cd '+quote(destination))
            s.wait(lambda:any(r['event']=='start' and r['cwd']==str(destination) for r in s.records()[before:]))
            assert ''.encode() not in s.data[offset:]
            print('PASS changing to a custom config immediately disables the app-specific pending frame',flush=True)
        finally:s.close()
        for label,kwargs in [('custom',{'custom':True}),('wrapped',{'wrapped':True}),('optout',{'optout':True})]:
            s=Shell(work/label,args.init_script,**kwargs)
            try:
                if label=='custom':
                    s.wait(lambda:b'CUSTOM> ' in s.data)
                    assert all('--right' in r['args'] for r in s.records())
                elif label=='wrapped':
                    s.wait(lambda:b'WRAPPED|' in s.data)
                    marker=work/'fallback';s.send(f'printf "%s" $__triton_prompt_disabled >{marker}')
                    s.wait(marker.exists);assert marker.read_text()=='1'
                else:
                    s.ready();s.wait(lambda:any('--right' in r['args'] for r in s.records()))
                    before=len(s.records());s.send('false | true');s.ready(before,status=0,pipestatus='1 0')
                print(f'PASS {label}: original synchronous prompt remains available',flush=True)
            finally:s.close()

if __name__=='__main__':main()
