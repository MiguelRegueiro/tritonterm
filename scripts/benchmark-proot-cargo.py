#!/usr/bin/env python3
"""Compare PRoot runtimes on an offline Cargo project with hundreds of crates."""
import argparse
import json
import os
from pathlib import Path
import resource
import statistics
import subprocess
import time

PROJECT = Path(__file__).resolve().parents[1]


def run(command, environment=None, cwd=None, timeout=300):
    result = subprocess.run([str(a) for a in command], env=environment, cwd=cwd,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {command}\n{result.stdout}")
    return result.stdout


def write_changed(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists() or path.read_text() != content:
        path.write_text(content)


def prepare(root, count, uid, gid):
    project = root / "home/triton/projects/cargo-scale"
    manifest = '[package]\nname="cargo-scale"\nversion="0.1.0"\nedition="2021"\n[dependencies]\n'
    for number in range(count):
        name = f"dep_{number:03d}"
        crate = project / "deps" / name
        write_changed(crate / "Cargo.toml",
                      f'[package]\nname="{name}"\nversion="0.1.0"\nedition="2021"\n')
        write_changed(crate / "src/lib.rs", f"pub fn value() -> u64 {{ {number} }}\n")
        if number < min(39, count):
            write_changed(crate / "build.rs",
                          'fn main() { println!("cargo:rerun-if-changed=build.rs"); }\n')
        manifest += f'{name}={{path="deps/{name}"}}\n'
    write_changed(project / "Cargo.toml", manifest)
    values = ",".join(f"dep_{number:03d}::value()" for number in range(count))
    write_changed(project / "src/main.rs",
                  f"fn main() {{ let values = [{values}]; "
                  f"assert_eq!(values.iter().sum::<u64>(), {sum(range(count))}); }}\n")
    for directory in ("tmp", "usr", "bin", "lib", "lib64", "etc", "proc", "dev", "toolchain"):
        (root / directory).mkdir(exist_ok=True)
    metadata = Path(str(root) + ".tritonterm-metadata")
    for path in root.rglob("*"):
        relative = path.relative_to(root)
        if "target" in relative.parts:
            continue
        record = metadata / relative.parent / (".proot-meta-file." + relative.name)
        mode = "755" if path.is_dir() else "644"
        write_changed(record, f"{mode}\n{uid}\n{gid}\n")
    return project


def guest_command(proot, root, output, toolchain, uid, gid):
    command = [str(proot), "-r", str(root), "-i", f"{uid}:{gid}",
               "-w", "/home/triton/projects/cargo-scale"]
    for directory in ("/usr", "/bin", "/lib", "/lib64", "/etc", "/proc", "/dev"):
        if Path(directory).exists():
            command += ["-b", directory]
    return command + ["-b", f"{toolchain}:/toolchain", "-b", str(output)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--proot", action="append", required=True, metavar="LABEL=PATH")
    parser.add_argument("--toolchain", type=Path)
    parser.add_argument("--crates", type=int, default=280)
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--guest-uid", type=int, default=os.getuid() + 10000)
    parser.add_argument("--guest-gid", type=int, default=os.getgid() + 10000)
    parser.add_argument("--memory-fallback", action="store_true")
    parser.add_argument("--check-rebuild", action="store_true")
    options = parser.parse_args()
    if options.crates < 1 or options.iterations < 1:
        parser.error("--crates and --iterations must be positive")
    binaries = {}
    for specification in options.proot:
        label, separator, binary = specification.partition("=")
        if not separator or not label or label in binaries:
            parser.error("each --proot needs a unique LABEL=PATH")
        binaries[label] = Path(binary).resolve(strict=True)
    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    root = output / "rootfs"
    root.mkdir(exist_ok=True)
    project = prepare(root, options.crates, options.guest_uid, options.guest_gid)
    toolchain = options.toolchain or Path(run(["rustc", "--print", "sysroot"]).strip())
    toolchain = toolchain.resolve(strict=True)
    environment = dict(os.environ, PROOT_FAST_IO="1", PROOT_NO_SECCOMP="1",
                       RUSTC="/toolchain/bin/rustc", CARGO_HOME="/home/triton/.cargo",
                       HOME="/home/triton", PATH="/usr/bin:/bin", CARGO_BUILD_JOBS="2")
    environment.pop("CARGO_LOG_PROFILE", None)
    environment.pop("CARGO_LOG", None)
    commands = {label: guest_command(binary, root, output, toolchain,
                                     options.guest_uid, options.guest_gid)
                for label, binary in binaries.items()}
    cargo = ["/toolchain/bin/cargo", "build", "--offline"]
    bootstrap = run(next(iter(commands.values())) + cargo, environment)
    (output / "bootstrap.log").write_text(bootstrap)
    prefixes = {"bulk-memory": []}
    if options.memory_fallback:
        wrapper = output / "memory-fallback"
        run(["cc", "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
             PROJECT / "tests/proot_memory_fallback.c", "-o", wrapper])
        prefixes["ptrace-memory"] = [wrapper]
    samples = {mode: {label: [] for label in commands} for mode in prefixes}
    cpu_samples = {mode: {label: [] for label in commands} for mode in prefixes}
    for iteration in range(options.iterations):
        labels = list(commands)
        if iteration % 2:
            labels.reverse()
        for mode, prefix in prefixes.items():
            for label in labels:
                usage_before = resource.getrusage(resource.RUSAGE_CHILDREN)
                start = time.monotonic()
                log = run(prefix + commands[label] + cargo, environment, timeout=120)
                elapsed = round((time.monotonic() - start) * 1000, 3)
                usage_after = resource.getrusage(resource.RUSAGE_CHILDREN)
                cpu_ms = round((usage_after.ru_utime + usage_after.ru_stime -
                                usage_before.ru_utime - usage_before.ru_stime) * 1000, 3)
                if "Compiling " in log:
                    raise RuntimeError(f"Benchmark rebuilt an unchanged project:\n{log}")
                samples[mode][label].append(elapsed)
                cpu_samples[mode][label].append(cpu_ms)
                print(f"{mode} {label} iteration={iteration} elapsed_ms={elapsed} cpu_ms={cpu_ms}", flush=True)
    if options.check_rebuild:
        leaf = project / "deps/dep_000/src/lib.rs"
        original = leaf.read_text()
        try:
            for label, command in commands.items():
                leaf.write_text(original + "pub fn rebuild_check() {}\n")
                log = run(command + cargo, environment)
                if "Compiling dep_000 " not in log or "Compiling cargo-scale " not in log:
                    raise RuntimeError(f"Source change did not rebuild leaf and dependent:\n{log}")
                run(command + ["./target/debug/cargo-scale"], environment)
                leaf.write_text(original)
                run(command + cargo, environment)
                print(f"{label}: changed leaf rebuilt with its dependent; executable passed", flush=True)
        finally:
            write_changed(leaf, original)
    report = dict(crates=options.crates, build_scripts=min(39, options.crates),
                  guest_uid=options.guest_uid, guest_gid=options.guest_gid, samples_ms=samples,
                  cpu_samples_ms=cpu_samples,
                  medians_ms={mode: {label: round(statistics.median(values), 3)
                                     for label, values in labels.items()}
                              for mode, labels in samples.items()},
                  cpu_medians_ms={mode: {label: round(statistics.median(values), 3)
                                         for label, values in labels.items()}
                                  for mode, labels in cpu_samples.items()},
                  source_rebuild_checked=options.check_rebuild)
    (output / "cargo-results.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
