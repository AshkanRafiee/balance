"""Host-only runner checks; no test in this module contacts adb."""
import argparse
import fcntl
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import run_instrumentation as runner


def status(method="example", code=1, total=1):
    return (f"INSTRUMENTATION_STATUS: class={runner.PACKAGE}.ExampleTest\n"
            f"INSTRUMENTATION_STATUS: test={method}\n"
            "INSTRUMENTATION_STATUS: current=1\n"
            f"INSTRUMENTATION_STATUS: numtests={total}\n"
            f"INSTRUMENTATION_STATUS_CODE: {code}\n")


def parse(text):
    result = runner.Results()
    for line in text.splitlines():
        result.feed(line)
    return result


ENDING = "OK (1 test)\nINSTRUMENTATION_CODE: -1\n"


class ResultsTest(unittest.TestCase):
    def test_complete_success_requires_every_signal(self):
        text = status() + status(code=0) + ENDING
        self.assertTrue(parse(text).success(0))
        self.assertFalse(parse(text).success(1))
        for fragment in (status(), status(code=0), "OK (1 test)\n",
                         "INSTRUMENTATION_CODE: -1\n"):
            with self.subTest(fragment=fragment):
                self.assertFalse(parse(text.replace(fragment, "")).success(0))

    def test_failures_and_incomplete_runs_cannot_pass(self):
        for code in (-1, -2):
            result = parse(status() + status(code=code) + ENDING)
            self.assertTrue(result.complete())
            self.assertFalse(result.success(0))
        for text in (ENDING, "OK (0 tests)\nINSTRUMENTATION_CODE: -1\n",
                     status() + status(code=0, total=2) + ENDING,
                     status() + status(code=0) * 2 + ENDING,
                     status() * 2 + status(code=0) + ENDING,
                     status() + status(code=0) + ENDING.replace("-1", "0")):
            with self.subTest(text=text):
                self.assertFalse(parse(text).success(0))

    def test_assumption_and_ignored_results_are_counted(self):
        for code in (-3, -4):
            ending = ENDING.replace("1 test", "0 tests") if code == -3 else ENDING
            result = parse(status() + status(code=code) + ending)
            self.assertTrue(result.success(0))
            self.assertEqual({"passed": 0, "failed": 0, "skipped": 1}, result.counts)

    def test_ignored_test_without_start_is_supported_but_missing_tests_are_not(self):
        ending = "OK (0 tests)\nINSTRUMENTATION_CODE: -1\n"
        self.assertTrue(parse(status(code=-3) + ending).success(0))
        self.assertFalse(parse(status(code=-3, total=2) + ending).success(0))

    def test_multiline_failure_does_not_hide_failure(self):
        text = status() + status(code=-2).replace(
            "INSTRUMENTATION_STATUS_CODE:",
            "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError\n"
            "at example.java:1\nINSTRUMENTATION_STATUS_CODE:") + ENDING
        self.assertEqual(1, parse(text).counts["failed"])


class SafetyTest(unittest.TestCase):
    def test_concurrent_run_is_refused_before_preflight(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock_path = root / "app/build/test-results/emulator-5554.lock"
            lock_path.parent.mkdir(parents=True)
            with lock_path.open("w") as lock:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                with patch.object(runner, "ROOT", root), \
                        patch("sys.argv", ["run_instrumentation.py"]), \
                        patch("sys.stderr", new_callable=io.StringIO), \
                        patch.object(runner, "preflight") as preflight, \
                        self.assertRaises(SystemExit) as exit_status:
                    runner.main()
                self.assertEqual(2, exit_status.exception.code)
                preflight.assert_not_called()
    def test_physical_or_injected_serial_rejected_before_adb(self):
        with patch.object(runner.subprocess, "check_output") as adb:
            for serial in ("phone", "", "emulator-5554;reboot", "emulator-5554\n"):
                with self.subTest(serial=serial), self.assertRaises(ValueError):
                    runner.preflight(serial)
            adb.assert_not_called()

    def test_class_selection_is_not_a_shell_command(self):
        runner.validate_selection("emulator-5554", runner.PACKAGE + ".ExampleTest#example")
        for classes in ("OtherTest", runner.PACKAGE + ".Test;reboot", "-e debug true"):
            with self.assertRaises(ValueError):
                runner.validate_selection("emulator-5554", classes)

    def test_stale_apk_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "example.apk"
            apk.write_bytes(b"local apk")
            responses = ["device", "1", "package:/data/app/example/base.apk", "bad-hash  path"]
            with patch.object(runner, "APKS", {runner.PACKAGE: apk}), \
                    patch.object(runner.subprocess, "check_output", side_effect=responses), \
                    self.assertRaisesRegex(ValueError, "Installed APK differs"):
                runner.preflight("emulator-5554")

    def test_emulator_property_is_required(self):
        with patch.object(runner.subprocess, "check_output", side_effect=["device", "0"]), \
                self.assertRaisesRegex(ValueError, "not a ready Android emulator"):
            runner.preflight("emulator-5554")

    def test_preflight_requires_helper_and_sms_grants(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "example.apk"
            apk.write_bytes(b"local apk")
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            good = ["device", "1", "package:/data/app/example/base.apk", digest + "  path",
                    "package:/data/app/helper/base.apk", "WRITE_SMS: allow",
                    "READ_RESTRICTED_MESSAGES: allow; time=now",
                    "android.permission.READ_SMS: granted=true, flags=[]"]
            with patch.object(runner, "APKS", {runner.PACKAGE: apk}):
                with patch.object(runner.subprocess, "check_output", side_effect=good):
                    self.assertEqual({runner.PACKAGE: digest}, runner.preflight("emulator-5554"))
                for index, missing in ((4, ""), (5, "WRITE_SMS: ignore"),
                                       (6, "No operations."), (7, "denied")):
                    responses = good.copy()
                    responses[index] = missing
                    with self.subTest(index=index), \
                            patch.object(runner.subprocess, "check_output", side_effect=responses), \
                            self.assertRaises(ValueError):
                        runner.preflight("emulator-5554")

    def test_cleanup_continues_after_adb_timeout_and_kills_stuck_client(self):
        process = Mock()
        process.poll.return_value = None
        process.wait.side_effect = [subprocess.TimeoutExpired("adb", 5), 0]
        with patch.object(runner.subprocess, "run", side_effect=[
                subprocess.TimeoutExpired("adb", 10), Mock(returncode=0)]) as adb:
            errors = runner.stop_instrumentation(process, "emulator-5554")
        self.assertEqual(2, adb.call_count)
        self.assertEqual(1, len(errors))
        process.terminate.assert_called_once()
        process.kill.assert_called_once()
        for call in adb.call_args_list:
            self.assertEqual(["adb", "-s", "emulator-5554"], call.args[0][:3])

    def test_termination_error_still_attempts_kill_and_reap(self):
        process = Mock()
        process.poll.return_value = None
        process.terminate.side_effect = OSError("client termination failed")
        process.wait.side_effect = [subprocess.TimeoutExpired("adb", 5),
                                    subprocess.TimeoutExpired("adb", 5)]
        with patch.object(runner.subprocess, "run", return_value=Mock(returncode=0)):
            errors = runner.stop_instrumentation(process, "emulator-5554")
        self.assertEqual(2, len(errors))
        process.kill.assert_called_once()
        self.assertEqual(2, process.wait.call_count)


class ExecutionTest(unittest.TestCase):
    def execute_fake(self, text, timeout=10, classes=None):
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(output=Path(directory), serial="emulator-5554",
                                      classes=classes, timeout=timeout)
            process = Mock(stdout=io.StringIO(text))
            process.wait.return_value = 0
            with patch.object(runner.subprocess, "Popen", return_value=process), \
                    patch.object(runner.subprocess, "check_output", side_effect=["commit", ""]), \
                    patch.object(runner, "stop_instrumentation", return_value=[]) as stop, \
                    patch("sys.stdout", new_callable=io.StringIO):
                code = runner.run(args, {"test": "hash"})
                summary = json.loads(next(Path(directory).glob("*.summary.json")).read_text())
            return code, summary, stop.call_count

    def test_complete_run_writes_success_and_does_not_force_stop(self):
        code, summary, stops = self.execute_fake(status() + status(code=0) + ENDING)
        self.assertEqual(0, code)
        self.assertTrue(summary["success"])
        self.assertEqual(1, summary["passed"])
        self.assertEqual(0, stops)

    def test_truncated_run_is_failure_even_when_adb_exits_zero(self):
        code, summary, stops = self.execute_fake(status())
        self.assertEqual(1, code)
        self.assertFalse(summary["complete"])
        self.assertFalse(summary["success"])
        self.assertEqual(1, stops)

    def test_startup_probes_omit_listener_that_initializes_parser(self):
        for method in ("balanceDataInitializedFirst", "bankRulesInitializedFirst"):
            selection = (runner.PACKAGE + ".ParserRevisionInitializationTest#" + method
                         + "_keepsTheHistoryRevisionCurrent")
            code, summary, _ = self.execute_fake(status() + status(code=0) + ENDING,
                                                classes=selection)
            self.assertEqual(0, code)
            self.assertNotIn("listener", summary["command"])
            self.assertIn(selection, summary["command"])

    def test_overall_timeout_records_failure_and_stops_remote_runner(self):
        code, summary, stops = self.execute_fake("", timeout=0)
        self.assertEqual(1, code)
        self.assertFalse(summary["complete"])
        self.assertEqual("TimeoutError", summary["error"])
        self.assertEqual(1, stops)

    def test_interrupt_does_not_close_pipe_owned_by_stuck_reader(self):
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(output=Path(directory), serial="emulator-5554",
                                      classes=None, timeout=10)
            process = Mock()
            with patch.object(runner.subprocess, "Popen", return_value=process), \
                    patch.object(runner.subprocess, "check_output", side_effect=["commit", ""]), \
                    patch.object(runner.threading, "Thread") as thread, \
                    patch.object(runner.queue.Queue, "get", side_effect=KeyboardInterrupt), \
                    patch.object(runner, "stop_instrumentation", return_value=[]) as stop, \
                    patch("sys.stdout", new_callable=io.StringIO):
                thread.return_value.is_alive.return_value = True
                self.assertEqual(1, runner.run(args, {}))
                summary = json.loads(next(Path(directory).glob("*.summary.json")).read_text())
            stop.assert_called_once_with(process, "emulator-5554")
            process.stdout.close.assert_not_called()
            self.assertEqual("KeyboardInterrupt", summary["error"])
            self.assertIn("adb output reader did not stop", summary["cleanup_errors"])


if __name__ == "__main__":
    unittest.main()
