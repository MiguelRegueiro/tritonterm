#!/usr/bin/env python3
"""Build and test PRoot on Linux x86_64; does not require a phone or VM."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import shutil
import statistics
import subprocess
import termios
import time

PROJECT = Path(__file__).resolve().parents[1]
SOURCE = PROJECT / "third_party/oonid-pr"


def run(arguments, **kwargs):
    try:
        return subprocess.run([str(a) for a in arguments], check=True, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kwargs).stdout
    except subprocess.CalledProcessError as failure:
        print(failure.stdout, flush=True)
        raise


def build(output):
    library = output / "lib"
    library.mkdir(exist_ok=True)
    (library / "include/linux").mkdir(parents=True, exist_ok=True)
    ndk = SOURCE / "build/ndk/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
    shutil.copy2(ndk / "linux/ashmem.h", library / "include/linux/ashmem.h")
    compat = library / "host-compat.h"
    compat.write_text("#ifndef __ASSEMBLER__\n#include <signal.h>\n#endif\n")
    talloc = SOURCE / "vendor/samba/lib/talloc"
    run(["cc", "-O2", "-DNO_CONFIG_H=1", "-D__STDC_WANT_LIB_EXT1__=1",
         f"-I{SOURCE / 'src/proot/lib/talloc'}", f"-I{talloc}", "-c", talloc / "talloc.c",
         "-o", library / "talloc.o"])
    run(["ar", "rcs", library / "libtalloc.a", library / "talloc.o"])
    native = output / "src"
    shutil.copytree(SOURCE / "src/proot/src", native, dirs_exist_ok=True)
    run(["make", "clean"], cwd=native)
    for coverage in native.rglob("*.gcda"):
        coverage.unlink()
    flags = f"-O2 -include {compat} -I{library / 'include'} -I{talloc}"
    run(["make", "-j4", "GIT=true", f"CFLAGS={flags}",
         "loader/loader", "loader/loader-m32"], cwd=native)
    (output / "build.log").write_text(run([
        "make", "-j4", "GIT=true", f"CFLAGS={flags} --coverage",
        f"LDFLAGS=--coverage -L{library} -ltalloc -Wl,-z,noexecstack", "proot"], cwd=native))
    (output / "unit-build.log").write_text(run([
        "cc", "-O0", "--coverage", "-ffunction-sections", "-fdata-sections", "-include", compat,
        f"-I{native}", f"-I{talloc}", PROJECT / "tests/proot_fast_io_unit.c", native / "syscall/sysnum.c",
        "-Wl,--gc-sections", f"-L{library}", "-ltalloc", "-o", output / "unit"]))
    run(["cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", "-pthread",
         PROJECT / "tests/proot_fast_io_workload.c", "-o", output / "workload"])
    return native / "proot"


def prompt_benchmark(command, environment, shell):
    master, slave = pty.openpty()
    if shell == "fish":
        arguments = ["/usr/bin/fish", "--no-config", "--private", "--interactive", "--init-command",
                     "function fish_prompt; printf 'PERFPROMPT>'; end; function fish_right_prompt; end"]
    else:
        arguments = ["/bin/bash", "--noprofile", "--norc", "-i"]
    process = subprocess.Popen(command + arguments, env=environment, stdin=slave, stdout=slave, stderr=slave,
                               start_new_session=True,
                               preexec_fn=lambda: fcntl.ioctl(0, termios.TIOCSCTTY, 0))
    os.close(slave)
    received = bytearray()
    query_tail = bytearray()
    marker = b"PERFPROMPT>"

    def wait_for(count):
        deadline = time.monotonic() + 30
        while received.count(marker) < count:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError(f"{shell} prompt timeout: {bytes(received[-2000:])!r}")
            if select.select([master], [], [], remaining)[0]:
                try:
                    chunk = os.read(master, 65536)
                    received.extend(chunk)
                    query_tail.extend(chunk)
                    while b"\x1b[6n" in query_tail:
                        end = query_tail.index(b"\x1b[6n") + 4
                        del query_tail[:end]
                        os.write(master, b"\x1b[1;1R")
                    del query_tail[:-32]
                except OSError as failure:
                    raise RuntimeError(f"{shell} exited before prompt: {bytes(received)!r}") from failure
    try:
        wait_for(1)
        start = time.monotonic()
        os.write(master, b"\n" * 30)
        wait_for(31)
        return round((time.monotonic() - start) * 1000, 3)
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        os.close(master)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--skip-build", action="store_true")
    options = parser.parse_args()
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    proot = output / "src/proot" if options.skip_build else build(output)
    print(run([output / "unit"]), end="", flush=True)
    root = output / "rootfs"
    root.mkdir(exist_ok=True)
    for name in ("tmp", "work", "usr", "bin", "lib", "lib64", "etc", "proc", "dev"):
        (root / name).mkdir(exist_ok=True)
    command = [str(proot), "-r", str(root), "-i", "1000:1000", "-w", "/work"]
    for name in ("usr", "bin", "lib", "lib64", "etc", "proc", "dev"):
        if Path("/" + name).exists():
            command += ["-b", "/" + name]
    command += ["-b", f"{output}:/work"]
    toolchain = Path(run(["rustc", "--print", "sysroot"]).strip())
    command += ["-b", f"{toolchain}:/toolchain"]
    crate = output / "rust-smoke"
    (crate / "src").mkdir(parents=True, exist_ok=True)
    (crate / "Cargo.toml").write_text('[package]\nname = "tritonterm-perf-smoke"\nversion = "0.1.0"\nedition = "2021"\n')
    (crate / "src/main.rs").write_text('fn main() { assert_eq!(std::thread::spawn(|| 42).join().unwrap(), 42); println!("RUST_SMOKE_OK"); }\n')
    results = {}
    for fast in ("0", "1"):
        environment = dict(os.environ, PROOT_FAST_IO=fast, PROOT_NO_SECCOMP="1", TERM="xterm-256color",
                           HOME="/work", PS1="PERFPROMPT>", LANG="C.UTF-8",
                           RUSTC="/toolchain/bin/rustc", CARGO_HOME="/work/cargo-home",
                           RUSTUP_HOME="/work/rustup-home", PATH="/usr/bin:/bin")
        for trap in (False, True):
            arguments = command + ["/work/workload"] + (["--trap-guest"] if trap else [])
            label = f"fast={fast} trap={trap}"
            log = run(arguments, env=environment, timeout=60)
            (output / f"workload-fast{fast}-trap{int(trap)}.log").write_text(log)
            print(label + ": " + log.strip(), flush=True)
        for debugger_mode in ("--ptrace-attach", "--ptrace-traceme"):
            log = run(command + ["/work/workload", debugger_mode], env=environment, timeout=60)
            print(f"fast={fast}: " + log.strip(), flush=True)
        cargo = command + ["/toolchain/bin/cargo", "run", "--offline",
                           "--manifest-path", "/work/rust-smoke/Cargo.toml", "--target-dir", f"/work/rust-target-{fast}"]
        log = run(cargo, env=environment, timeout=120)
        assert "RUST_SMOKE_OK" in log, log
        (output / f"rust-fast{fast}.log").write_text(log)
        print(f"fast={fast}: Rust build, linker, run and threads passed", flush=True)
        cargo_times = []
        for _ in range(3):
            start = time.monotonic()
            assert "RUST_SMOKE_OK" in run(cargo, env=environment, timeout=60)
            cargo_times.append(round((time.monotonic() - start) * 1000, 3))
        results[f"fast{fast}_warm_cargo_run_ms"] = cargo_times
        times = []
        for _ in range(3):
            log = run(command + ["/work/workload", "--bench"], env=environment, timeout=60)
            times.append(float(log.split("elapsed_ms=")[1].strip()))
        results[f"fast{fast}_io_ms"] = times
        for shell in ("bash", "fish"):
            if shutil.which(shell):
                results[f"fast{fast}_{shell}_30_enters_ms"] = [
                    prompt_benchmark(command, environment, shell) for _ in range(3)]
    results["io_speedup"] = round(statistics.median(results["fast0_io_ms"]) /
                                  statistics.median(results["fast1_io_ms"]), 2)
    (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps(results, indent=2), flush=True)


if __name__ == "__main__":
    main()
