#!/usr/bin/env python3
"""Run one opt-in probe in a subprocess with a wall-clock deadline (POSIX)."""
import argparse
import os
from pathlib import Path
import signal
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument("case", choices=("baseline", "mutex", "race"))
parser.add_argument("--timeout", type=int, default=90, help="seconds, including Gradle startup")
parser.add_argument("--memory", action="store_true", help="enable interleaving of memory operations")
args = parser.parse_args()
root = Path(__file__).resolve().parents[2]
command = [
    "./gradlew", "--offline", ":integration-test:test", "--no-daemon", "--console=plain",
    "--tests", f"org.pastalab.fray.poc.CoroutinePocTest.{args.case}",
    "-Dfray.coroutine.poc=true", f"-Dfray.coroutine.memory={str(args.memory).lower()}",
]
print("Running:", " ".join(command), flush=True)
process = subprocess.Popen(command, cwd=root, start_new_session=True)
try:
    sys.exit(process.wait(timeout=args.timeout))
except subprocess.TimeoutExpired:
    print(f"Timed out after {args.timeout}s (including build); stopping process group", file=sys.stderr)
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
    sys.exit(124)
