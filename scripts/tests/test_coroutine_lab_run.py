import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path


WATCHDOG = Path(__file__).resolve().parents[1] / "coroutine-lab-run.py"


@unittest.skipUnless(os.name == "posix" and hasattr(os, "waitid"), "requires POSIX waitid")
class ProcessGroupWatchdogTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.log = self.root / "watchdog.log"
        self.ready = self.root / "ready"
        self.escaped = self.root / "escaped"
        self.child = self.root / "child.py"
        self.child.write_text(
            "import pathlib,signal,sys,time\n"
            "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
            "pathlib.Path(sys.argv[1]).write_text('ready')\n"
            "time.sleep(2)\n"
            "pathlib.Path(sys.argv[2]).write_text('survived')\n"
        )
        self.leader = self.root / "leader.py"
        self.leader.write_text(
            "import pathlib,subprocess,sys,time\n"
            "subprocess.Popen([sys.executable, sys.argv[1], sys.argv[2], sys.argv[3]])\n"
            "deadline=time.monotonic()+3\n"
            "while not pathlib.Path(sys.argv[2]).exists():\n"
            "  if time.monotonic() >= deadline: raise RuntimeError('child not ready')\n"
            "  time.sleep(.01)\n"
            "if sys.argv[4] == 'wait': time.sleep(30)\n"
        )

    def tearDown(self):
        self.temporary.cleanup()

    def wrapper_command(self, timeout, mode):
        return [
            sys.executable, str(WATCHDOG), str(self.log), str(timeout),
            sys.executable, str(self.leader), str(self.child), str(self.ready),
            str(self.escaped), mode,
        ]

    def assert_no_late_work(self):
        # The fixture exits on its own after writing this marker. Waiting beyond
        # its deadline makes the regression safe even against the broken version.
        time.sleep(2.2)
        self.assertFalse(self.escaped.exists(), "task-owned child survived cleanup")

    def test_normal_exit_preserves_status_and_logs(self):
        result = subprocess.run(
            [sys.executable, str(WATCHDOG), str(self.log), "5", sys.executable, "-c", "print('hello'); raise SystemExit(7)"],
            text=True, capture_output=True, timeout=8,
        )
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertIn("External status: 7", result.stdout)
        self.assertIn("hello", self.log.read_text())

    def test_signaled_target_status_is_normalized(self):
        result = subprocess.run(
            [sys.executable, str(WATCHDOG), str(self.log), "5", sys.executable, "-c",
             "import os,signal; os.kill(os.getpid(), signal.SIGTERM)"],
            text=True, capture_output=True, timeout=8,
        )
        self.assertEqual(result.returncode, 128 + signal.SIGTERM, result.stderr)
        self.assertIn("External status: 143", result.stdout)
        self.assertIn("External status: 143", self.log.read_text())

    def test_inherited_blocked_signal_fails_before_target_launch(self):
        for sig in (signal.SIGTERM, signal.SIGINT):
            with self.subTest(signal=sig):
                marker = self.root / ("blocked-" + str(sig))
                result = subprocess.run(
                    [sys.executable, str(WATCHDOG), str(self.log), "5", sys.executable, "-c",
                     "open(%r,'w').write('ran')" % str(marker)],
                    text=True, capture_output=True, timeout=8,
                    preexec_fn=lambda: signal.pthread_sigmask(signal.SIG_BLOCK, {sig}),
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("requires SIGTERM and SIGINT to be unblocked", result.stderr)
                self.assertFalse(marker.exists(), "target started with blocked cancellation")

    def test_early_cancellation_never_starts_target(self):
        injector = self.root / "inject.py"
        injector.write_text(
            "import os,runpy,signal,subprocess,sys\n"
            "wrapper,log,marker,when=sys.argv[1:]\n"
            "original=subprocess.Popen\n"
            "def intercepted(*args, **kwargs):\n"
            "  if when == 'before': os.kill(os.getpid(), signal.SIGTERM)\n"
            "  process=original(*args, **kwargs)\n"
            "  if when == 'after': os.kill(os.getpid(), signal.SIGTERM)\n"
            "  return process\n"
            "subprocess.Popen=intercepted\n"
            "sys.argv=[wrapper,log,'5',sys.executable,'-c',\"open(%r,'w').write('ran')\" % marker]\n"
            "runpy.run_path(wrapper, run_name='__main__')\n"
        )
        for when in ("before", "after"):
            with self.subTest(when=when):
                marker = self.root / ("started-" + when)
                result = subprocess.run(
                    [sys.executable, str(injector), str(WATCHDOG), str(self.log), str(marker), when],
                    text=True, capture_output=True, timeout=8,
                )
                self.assertEqual(result.returncode, 128 + signal.SIGTERM, (result.stdout, result.stderr))
                self.assertFalse(marker.exists(), "target started after cancellation was recorded")

    def test_delayed_supervisor_honors_deadline_and_bounded_cancellation(self):
        injector = self.root / "delayed-supervisor.py"
        injector.write_text(
            "import os,runpy,signal,subprocess,sys\n"
            "wrapper,log,marker,when=sys.argv[1:]\n"
            "original=subprocess.Popen\n"
            "def intercepted(args, **kwargs):\n"
            "  delayed='import os,sys,time; time.sleep(4); os.execv(sys.argv[1], sys.argv[1:])'\n"
            "  process=original([sys.executable,'-c',delayed,*args], **kwargs)\n"
            "  if when == 'cancel': os.kill(os.getpid(), signal.SIGTERM)\n"
            "  return process\n"
            "subprocess.Popen=intercepted\n"
            "sys.argv=[wrapper,log,'7',sys.executable,'-c',\"open(%r,'w').write('ran')\" % marker]\n"
            "runpy.run_path(wrapper, run_name='__main__')\n"
        )
        for when in ("normal", "cancel"):
            with self.subTest(when=when):
                marker = self.root / ("delayed-" + when)
                start = time.monotonic()
                result = subprocess.run(
                    [sys.executable, str(injector), str(WATCHDOG), str(self.log), str(marker), when],
                    text=True, capture_output=True, timeout=10,
                )
                elapsed = time.monotonic() - start
                if when == "normal":
                    self.assertEqual(result.returncode, 0, (result.stdout, result.stderr))
                    self.assertTrue(marker.exists(), "delayed target did not run")
                    self.assertGreater(elapsed, 3)
                else:
                    self.assertEqual(result.returncode, 128 + signal.SIGTERM, (result.stdout, result.stderr))
                    self.assertFalse(marker.exists(), "cancelled target started")
                    self.assertLess(elapsed, 5, "cancellation waited for the caller's full deadline")

    def test_waitid_observes_exited_child_without_reaping(self):
        child = subprocess.Popen([sys.executable, "-c", "pass"])
        try:
            deadline = time.monotonic() + 3
            first = None
            while first is None and time.monotonic() < deadline:
                first = os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                if first is None:
                    time.sleep(.01)
            self.assertIsNotNone(first, "child did not exit")
            second = os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            self.assertIsNotNone(second, "first waitid unexpectedly reaped the child")
            self.assertEqual(first.si_pid, child.pid)
            self.assertEqual(second.si_pid, child.pid)
        finally:
            child.wait(timeout=3)

    def test_timeout_kills_term_ignoring_descendant(self):
        result = subprocess.run(self.wrapper_command(1, "wait"), text=True, capture_output=True, timeout=14)
        self.assertEqual(result.returncode, 124, result.stderr)
        self.assertTrue(self.ready.exists(), "fixture did not start")
        self.assert_no_late_work()

    def test_normal_leader_exit_kills_remaining_descendant(self):
        result = subprocess.run(self.wrapper_command(5, "exit"), text=True, capture_output=True, timeout=8)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.ready.exists(), "fixture did not start")
        self.assert_no_late_work()

    def test_unrelated_process_group_is_untouched(self):
        outsider = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(8)"], start_new_session=True)
        try:
            result = subprocess.run(self.wrapper_command(5, "exit"), text=True, capture_output=True, timeout=8)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIsNone(outsider.poll(), "watchdog signaled an unrelated process group")
            self.assert_no_late_work()
        finally:
            outsider.terminate()
            outsider.wait(timeout=3)

    def test_sigterm_to_watchdog_cleans_group(self):
        watchdog = subprocess.Popen(self.wrapper_command(10, "wait"), stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 5
            while not self.ready.exists() and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertTrue(self.ready.exists(), "fixture did not start")
            watchdog.send_signal(signal.SIGTERM)
            stdout, stderr = watchdog.communicate(timeout=5)
            self.assertEqual(watchdog.returncode, 128 + signal.SIGTERM, (stdout, stderr))
            self.assert_no_late_work()
        finally:
            if watchdog.poll() is None:
                watchdog.kill()
                watchdog.wait(timeout=3)

    def test_term_ignoring_leader_has_bounded_grace(self):
        start = time.monotonic()
        result = subprocess.run(
            [sys.executable, str(WATCHDOG), str(self.log), "1", sys.executable, "-c",
             "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(13)"],
            text=True, capture_output=True, timeout=15,
        )
        self.assertEqual(result.returncode, 124, result.stderr)
        self.assertLess(time.monotonic() - start, 14)


if __name__ == "__main__":
    unittest.main()
