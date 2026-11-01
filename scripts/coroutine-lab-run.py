#!/usr/bin/env python3
"""Run one lab command sequentially with a process-group deadline and durable log."""
import os
import signal
import subprocess
import sys
from pathlib import Path

if len(sys.argv) < 4:
    raise SystemExit("usage: fray-lifecycle-run.py LOG TIMEOUT COMMAND [ARG ...]")
log, seconds, *command = sys.argv[1:]
Path(log).parent.mkdir(parents=True, exist_ok=True)
with open(log, "w", encoding="utf-8") as output:
    output.write("Command: " + " ".join(command) + "\n")
    output.flush()
    process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        status = process.wait(timeout=int(seconds))
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
        status = 124
    output.write("\nExternal status: %d\n" % status)
print("External status:", status)
raise SystemExit(status)
