#!/usr/bin/env python3
"""Run one POSIX lab command with a bounded, task-owned process group.

Signals are best-effort; SIGKILL is asynchronous and this cannot verify that
all descendants have been reaped. Children that change group/session can escape.
This helper is not a sandbox.
"""
import os
import selectors
import signal
import subprocess
import sys
import time
from pathlib import Path


def supervise(status_fd, start_fd, command):
    """Keep the session leader alive after the command exits."""
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    # The parent must not start the command until our TERM-immune anchor exists.
    os.write(status_fd, b"READY\n")
    start = os.read(start_fd, 1)
    os.close(start_fd)
    if start == b"G":
        def reset_child_signals():
            # This supervisor is single-threaded. Ignored signal dispositions
            # survive exec, so restore defaults for the target command.
            signal.signal(signal.SIGTERM, signal.SIG_DFL)
            signal.signal(signal.SIGINT, signal.SIG_DFL)

        try:
            target = subprocess.Popen(command, close_fds=True, preexec_fn=reset_child_signals)
            status = target.wait()
            os.write(status_fd, ("%d\n" % status).encode("ascii"))
        except (OSError, ValueError):
            os.write(status_fd, b"ERROR\n")
    os.close(status_fd)
    # Retain the process-group identity until the watchdog kills the group.
    while True:
        signal.pause()


def signal_group(supervisor, sig):
    # WNOWAIT leaves the original child waitable and its PID reserved until
    # after the final signal; a recycled numeric PGID cannot be targeted.
    os.waitid(os.P_PID, supervisor.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
    os.killpg(supervisor.pid, sig)


def main():
    if len(sys.argv) >= 5 and sys.argv[1] == "--internal-supervisor":
        supervise(int(sys.argv[2]), int(sys.argv[3]), sys.argv[4:])
        return 125
    if len(sys.argv) < 4:
        raise SystemExit("usage: coroutine-lab-run.py LOG TIMEOUT COMMAND [ARG ...]")
    if (os.name != "posix" or not all(
        hasattr(os, name) for name in ("waitid", "P_PID", "WEXITED", "WNOHANG", "WNOWAIT")
    ) or not all(hasattr(signal, name) for name in ("pthread_sigmask", "sigpending"))):
        raise SystemExit("safe process-group cleanup requires POSIX waitid/WNOWAIT and signal masks")
    log, seconds, *command = sys.argv[1:]
    timeout = int(seconds)
    if timeout <= 0:
        raise SystemExit("TIMEOUT must be a positive integer")

    # A caller can inherit blocked termination signals across exec. Do not
    # launch a child if the watchdog could not observe cancellation.
    inherited = signal.pthread_sigmask(signal.SIG_BLOCK, set())
    if inherited & {signal.SIGTERM, signal.SIGINT}:
        raise SystemExit("watchdog requires SIGTERM and SIGINT to be unblocked")

    # This dedicated watchdog has no other children or reaper threads. Reset an
    # inherited SIGCHLD=SIG_IGN so the kernel does not automatically reap ours.
    signal.signal(signal.SIGCHLD, signal.SIG_DFL)
    interrupted = [None]

    def request_stop(signum, _frame):
        if interrupted[0] is None:
            interrupted[0] = signum

    # Recording signals instead of raising closes the gap around Popen/cleanup.
    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    Path(log).parent.mkdir(parents=True, exist_ok=True)
    with open(log, "w", encoding="utf-8") as output:
        output.write("Command: " + " ".join(command) + "\n")
        output.flush()
        # A cancellation observed before launch must not start a command.
        if interrupted[0] is not None:
            status = 128 + interrupted[0]
            output.write("\nExternal status: %d\n" % status)
            print("External status:", status)
            return status
        read_fd, write_fd = os.pipe()
        start_read, start_write = os.pipe()
        try:
            supervisor = subprocess.Popen(
                [sys.executable, str(Path(__file__).resolve()), "--internal-supervisor", str(write_fd), str(start_read), *command],
                stdout=output,
                stderr=subprocess.STDOUT,
                start_new_session=True,
                pass_fds=(write_fd, start_read),
            )
        except BaseException:
            for fd in (read_fd, write_fd, start_read, start_write):
                os.close(fd)
            raise
        os.close(write_fd)
        os.close(start_read)
        pending = bytearray()
        cleanup_failed = None
        anchor_ready = False
        started = False
        timed_out = False
        result = None
        deadline = time.monotonic() + timeout
        try:
            with selectors.DefaultSelector() as selector:
                selector.register(read_fd, selectors.EVENT_READ)

                def read_line(until, stop_on_signal):
                    while True:
                        if b"\n" in pending:
                            line, _, rest = pending.partition(b"\n")
                            pending[:] = rest
                            return line
                        if stop_on_signal and interrupted[0] is not None:
                            return None
                        remaining = until - time.monotonic()
                        if remaining <= 0:
                            return None
                        if selector.select(min(0.05, remaining)):
                            chunk = os.read(read_fd, 64)
                            if not chunk:
                                raise ValueError("supervisor exited before reporting status")
                            pending.extend(chunk)
                            if len(pending) > 64:
                                raise ValueError("invalid supervisor status")

                # Normal startup can use the caller's whole deadline. After
                # cancellation, briefly await READY so TERM cannot kill an
                # anchor whose signal handlers are not yet installed.
                ready = read_line(deadline, True)
                if ready is None and interrupted[0] is not None:
                    ready = read_line(min(deadline, time.monotonic() + 3), False)
                anchor_ready = ready == b"READY"
                if not anchor_ready:
                    timed_out = interrupted[0] is None and time.monotonic() >= deadline
                    if not timed_out and interrupted[0] is None:
                        raise ValueError("supervisor did not become ready")
                else:
                    # Linearize the start decision against already-delivered or
                    # pending cancellation. GO is never sent after that decision.
                    blocked = {signal.SIGTERM, signal.SIGINT}
                    previous = signal.pthread_sigmask(signal.SIG_BLOCK, blocked)
                    try:
                        pending_signals = signal.sigpending() & blocked
                        if interrupted[0] is None and pending_signals:
                            interrupted[0] = min(pending_signals)
                        if interrupted[0] is None and time.monotonic() < deadline:
                            os.write(start_write, b"G")
                            started = True
                    finally:
                        signal.pthread_sigmask(signal.SIG_SETMASK, previous)
                    if started:
                        result = read_line(deadline, True)
                    timed_out = result is None and interrupted[0] is None
                os.close(start_write)
                start_write = None
                if anchor_ready:
                    signal_group(supervisor, signal.SIGTERM)
                    if started and result is None:
                        # Preserve the previous bounded TERM grace, with the
                        # supervisor alive so the group remains anchored.
                        read_line(time.monotonic() + 10, False)
        except (OSError, ValueError) as error:
            cleanup_failed = error
        finally:
            if start_write is not None:
                os.close(start_write)
            try:
                # Before READY, no GO was sent and no target can exist. If the
                # supervisor already exited, reap it without a numeric group signal.
                event = os.waitid(os.P_PID, supervisor.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                if anchor_ready or event is None:
                    signal_group(supervisor, signal.SIGKILL)
            except OSError as error:
                cleanup_failed = cleanup_failed or error
            try:
                supervisor.wait(timeout=2)
            except subprocess.TimeoutExpired as error:
                cleanup_failed = cleanup_failed or error
            os.close(read_fd)

        if cleanup_failed is not None:
            output.write("\nProcess-group cleanup failed: %s\n" % cleanup_failed)
            status = 125
        elif interrupted[0] is not None:
            status = 128 + interrupted[0]
        elif timed_out:
            status = 124
        else:
            try:
                value = int(result.decode("ascii"))
                status = 128 - value if value < 0 else value
            except (AttributeError, UnicodeError, ValueError):
                status = 125
        output.write("\nExternal status: %d\n" % status)
    print("External status:", status)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
