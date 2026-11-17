import argparse
import json
import os
from pathlib import Path
import resource
import signal
import subprocess
import sys
import time


def process_stat(pid):
    fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return {
        "cpu_ticks": int(fields[11]) + int(fields[12]),
        "user_ticks": int(fields[11]),
        "system_ticks": int(fields[12]),
        "nice": int(fields[16]),
        "start_ticks": int(fields[19]),
    }


def read_snapshot(path, tracer, started):
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise RuntimeError("Invalid profiler snapshot.")
    if (value.get("version") != 1 or value.get("tracer_pid") != tracer or
            value.get("process_start_ticks") != started):
        raise RuntimeError("Profiler snapshot does not belong to this terminal session.")
    sequence = value.get("sequence")
    counters = value.get("counters")
    if type(sequence) is not int or sequence < 0 or not isinstance(counters, dict):
        raise RuntimeError("Invalid profiler snapshot.")
    if any(not isinstance(key, str) or type(count) is not int or count < 0
           for key, count in counters.items()):
        raise RuntimeError("Invalid profiler counters.")
    return value


def request_snapshot(path, tracer, started):
    previous = read_snapshot(path, tracer, started)
    os.kill(tracer, signal.SIGUSR2)
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        current = read_snapshot(path, tracer, started)
        if current["sequence"] > previous["sequence"]:
            return current
        time.sleep(0.01)
    raise RuntimeError("The tracer did not publish a new profiler snapshot.")


def main():
    parser = argparse.ArgumentParser(description="Measure a command and its PRoot tracer.")
    parser.add_argument("--stats-dir", type=Path, default=Path("/tmp"))
    parser.add_argument("command", nargs=argparse.REMAINDER)
    options = parser.parse_args()
    command = options.command
    if command[:1] == ["--"]:
        command = command[1:]
    if not command:
        command = ["cargo", "build", "--offline"]
    status = dict(line.split(":", 1) for line in Path("/proc/self/status").read_text().splitlines()
                  if ":" in line)
    tracer = int(status["TracerPid"])
    if not tracer:
        raise RuntimeError("This command needs to run inside TritonTerm's Arch session.")
    start_stat = process_stat(tracer)
    path = options.stats_dir / f"proot-perf-{tracer}.json"
    before = request_snapshot(path, tracer, start_stat["start_ticks"])
    start_stat = process_stat(tracer)
    start_usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    start = time.monotonic()
    result = subprocess.run(command)
    wall = time.monotonic() - start
    end_usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    end_stat = process_stat(tracer)
    after = request_snapshot(path, tracer, start_stat["start_ticks"])
    differences = {}
    for name, count in after["counters"].items():
        differences[name] = count if name == "f2fs_active" else count - before["counters"].get(name, 0)
        if differences[name] < 0:
            raise RuntimeError("Profiler counters reset during the command.")
    report = {
        "exit_code": result.returncode,
        "wall_seconds": round(wall, 3),
        "command_cpu_seconds": round(end_usage.ru_utime + end_usage.ru_stime -
                                     start_usage.ru_utime - start_usage.ru_stime, 3),
        "tracer_cpu_seconds": round((end_stat["cpu_ticks"] - start_stat["cpu_ticks"]) /
                                    os.sysconf("SC_CLK_TCK"), 3),
        "tracer_user_seconds": round((end_stat["user_ticks"] - start_stat["user_ticks"]) /
                                     os.sysconf("SC_CLK_TCK"), 3),
        "tracer_system_seconds": round((end_stat["system_ticks"] - start_stat["system_ticks"]) /
                                       os.sysconf("SC_CLK_TCK"), 3),
        "tracer_nice": end_stat["nice"],
        "timings_seconds": {name.removesuffix("_ns"): round(count / 1_000_000_000, 4)
                            for name, count in differences.items() if name.endswith("_ns")},
        "counters": {name: count for name, count in differences.items() if not name.endswith("_ns")},
    }
    print(json.dumps(report, indent=2))
    return result.returncode if result.returncode >= 0 else 128 - result.returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, IndexError, RuntimeError) as error:
        print(f"TritonTerm profiler: {error}", file=sys.stderr)
        sys.exit(2)
