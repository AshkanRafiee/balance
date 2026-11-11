#!/usr/bin/env python3
"""Run AndroidJUnitRunner on an emulator, recording complete, attributable results."""
import argparse
import fcntl
import hashlib
import json
from pathlib import Path
import queue
import re
import signal
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.ashkanrafiee.balance"
APKS = {
    PACKAGE: ROOT / "app/build/outputs/apk/debug/app-debug.apk",
    PACKAGE + ".test": ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
}


def validate_selection(serial, classes):
    # Reject physical serials before sending even a read-only command to a device.
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError("Only an explicitly selected emulator serial is allowed")
    if classes and not all(re.fullmatch(
        r"com\.ashkanrafiee\.balance\.[A-Za-z_$][\w$]*(?:#[A-Za-z_$][\w$]*)?", item
    ) for item in classes.split(",")):
        raise ValueError("Use comma-separated Balance test classes, optionally followed by #method")


class Results:
    def __init__(self):
        self.bundle = {}
        self.expected = None
        self.runner_count = None
        self.instrumentation_code = None
        self.running = {}
        self.completed = set()
        self.counts = {"passed": 0, "failed": 0, "skipped": 0}
        self.ignored = 0
        self.invalid = False

    def feed(self, line):
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if separator:
                self.bundle[key] = value.strip()
        match = re.fullmatch(r"OK \((\d+) tests?\)", line.strip())
        if match:
            self.runner_count = int(match[1])
        match = re.fullmatch(r"INSTRUMENTATION_CODE: (-?\d+)", line.strip())
        if match:
            self.instrumentation_code = int(match[1])
        match = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line.strip())
        if not match:
            return None
        status, self.bundle = self.bundle, {}
        code = int(match[1])
        if "numtests" in status:
            try:
                expected = int(status["numtests"])
                if self.expected is not None and self.expected != expected:
                    self.invalid = True
                self.expected = expected
            except ValueError:
                self.invalid = True
        identity = (status.get("class"), status.get("test"))
        if not all(identity):
            self.invalid = True
            return None
        event = {"class": identity[0], "method": identity[1], "code": code,
                 "current": status.get("current"), "total": self.expected}
        if code == 1:
            if identity in self.running or identity in self.completed:
                self.invalid = True
            self.running[identity] = time.monotonic()
            event["outcome"] = "RUN"
        elif code in (0, -1, -2, -3, -4):
            began = self.running.pop(identity, None)
            if (began is None and code != -3) or identity in self.completed:
                self.invalid = True
            if code == -3:
                self.ignored += 1
            event["seconds"] = time.monotonic() - began if began is not None else None
            key = "passed" if code == 0 else "skipped" if code in (-3, -4) else "failed"
            self.counts[key] += 1
            self.completed.add(identity)
            event["outcome"] = {"passed": "PASS", "skipped": "SKIP", "failed": "FAIL"}[key]
        else:
            self.invalid = True
            return None
        return event

    def complete(self):
        return (not self.invalid and not self.running and self.expected is not None
                and self.expected > 0 and len(self.completed) == self.expected
                and self.instrumentation_code == -1)

    def success(self, return_code):
        return (return_code == 0 and self.complete()
                and self.runner_count == self.expected - self.ignored
                and self.counts["failed"] == 0)


def preflight(serial):
    validate_selection(serial, None)
    def adb(*args):
        return subprocess.check_output(["adb", "-s", serial, *args], text=True,
                                       stderr=subprocess.STDOUT, timeout=30).strip()
    if adb("get-state") != "device" or adb("shell", "getprop", "ro.boot.qemu") != "1":
        raise ValueError("The selected device is not a ready Android emulator")
    artifacts = {}
    for package, path in APKS.items():
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        paths = adb("shell", "pm", "path", package).splitlines()
        base = [p.removeprefix("package:") for p in paths
                if re.fullmatch(r"package:/data/app/[A-Za-z0-9_~+/=.-]+/base\.apk", p)]
        if len(base) != 1:
            raise ValueError(f"Install the current APK for {package} first")
        installed_hash = adb("shell", "sha256sum", base[0]).split()
        if not installed_hash or installed_hash[0] != digest:
            raise ValueError(f"Installed APK differs from {path}; reinstall before testing")
        artifacts[package] = digest
    if not adb("shell", "pm", "path", "com.ashkanrafiee.smsinject"):
        raise ValueError("Install the smsinject helper before running the suite")
    for package, operation in (("com.ashkanrafiee.smsinject", "WRITE_SMS"),
                               (PACKAGE, "android:read_restricted_messages")):
        if not re.search(r": allow(?:\s|;|$)", adb("shell", "cmd", "appops", "get",
                                                   package, operation)):
            raise ValueError(f"Grant {operation} to {package} before testing")
    if not re.search(r"android\.permission\.READ_SMS: granted=true\b",
                     adb("shell", "dumpsys", "package", PACKAGE)):
        raise ValueError("Grant READ_SMS to Balance before testing")
    return artifacts


def stream(process, messages):
    try:
        for line in process.stdout:
            messages.put(line)
    finally:
        messages.put(None)


def stop_instrumentation(process, serial):
    """Stop the remote runner even when one cleanup command or adb itself fails."""
    errors = []
    for package in (PACKAGE, PACKAGE + ".test"):
        try:
            result = subprocess.run(
                ["adb", "-s", serial, "shell", "am", "force-stop", package],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
            if result.returncode:
                errors.append(f"force-stop {package}: exit {result.returncode}")
        except (OSError, subprocess.SubprocessError) as error:
            errors.append(f"force-stop {package}: {type(error).__name__}")
    if process.poll() is None:
        try:
            process.terminate()
        except OSError as error:
            errors.append(f"adb terminate: {type(error).__name__}")
        try:
            process.wait(timeout=5)
        except (OSError, subprocess.SubprocessError):
            try:
                process.kill()
            except OSError as error:
                errors.append(f"adb kill: {type(error).__name__}")
            try:
                process.wait(timeout=5)
            except (OSError, subprocess.SubprocessError) as error:
                errors.append(f"adb reap: {type(error).__name__}")
    return errors


def run(args, artifacts):
    validate_selection(args.serial, args.classes)
    args.output.mkdir(parents=True, exist_ok=True)
    prefix = args.output / f"instrumentation-{time.time_ns()}"
    command = ["adb", "-s", args.serial, "shell", "am", "instrument", "-w", "-r"]
    # The listener touches BalanceData; it would invalidate fresh-entry-order checks.
    startup_probe = args.classes in (
        PACKAGE + ".ParserRevisionInitializationTest#balanceDataInitializedFirst_keepsTheHistoryRevisionCurrent",
        PACKAGE + ".ParserRevisionInitializationTest#bankRulesInitializedFirst_keepsTheHistoryRevisionCurrent")
    if not startup_probe:
        command += ["-e", "listener", PACKAGE + ".CommitmentJobIsolationListener"]
    if args.classes:
        command += ["-e", "class", args.classes]
    command += [PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
    summary = {"commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT,
                                                text=True).strip(),
               "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"],
                                                     cwd=ROOT, text=True).strip()),
               "command": command, "artifacts": artifacts, "complete": False, "success": False}
    summary_path = prefix.with_suffix(".summary.json")
    summary_path.write_text(json.dumps(summary, indent=2) + "\n")
    print(f"Live log: {prefix.with_suffix('.log')}", flush=True)
    print(f"Summary: {summary_path}", flush=True)
    results = Results()
    began = time.monotonic()
    process = None
    reader = None
    try:
        with prefix.with_suffix(".log").open("w") as log, \
                prefix.with_suffix(".results.jsonl").open("w") as events:
            process = subprocess.Popen(command, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True, bufsize=1)
            messages = queue.Queue()
            reader = threading.Thread(target=stream, args=(process, messages), daemon=True)
            reader.start()
            while True:
                remaining = args.timeout - (time.monotonic() - began)
                if remaining <= 0:
                    raise TimeoutError("Instrumentation exceeded the overall timeout")
                try:
                    line = messages.get(timeout=remaining)
                except queue.Empty:
                    raise TimeoutError("Instrumentation exceeded the overall timeout") from None
                if line is None:
                    break
                log.write(line)
                log.flush()
                event = results.feed(line)
                if event:
                    events.write(json.dumps(event) + "\n")
                    events.flush()
                    print(f"{event['outcome']} [{event['current']}/{event['total']}] "
                          f"{event['class']}#{event['method']}", flush=True)
            return_code = process.wait(timeout=max(1, args.timeout - (time.monotonic() - began)))
            summary.update(complete=results.complete(), process_return_code=return_code,
                           success=results.success(return_code))
    except (KeyboardInterrupt, OSError, TimeoutError, subprocess.SubprocessError) as error:
        summary["error"] = type(error).__name__
    finally:
        if process is not None:
            if not summary["success"]:
                summary["cleanup_errors"] = stop_instrumentation(process, args.serial)
            if reader is not None:
                reader.join(timeout=0.1)
            # A stuck reader owns the pipe lock; closing it here could defeat the watchdog.
            if reader is None or not reader.is_alive():
                process.stdout.close()
            else:
                summary.setdefault("cleanup_errors", []).append("adb output reader did not stop")
                summary["success"] = False
        summary.update(results.counts, expected=results.expected,
                       completed=len(results.completed), seconds=time.monotonic() - began)
        summary_path.write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2), flush=True)
    return 0 if summary["success"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--classes", help="Comma-separated fully qualified classes or class#method")
    parser.add_argument("--output", type=Path, default=ROOT / "app/build/test-results/instrumentation")
    parser.add_argument("--timeout", type=float, default=14400, help="Overall seconds; no per-test limit")
    args = parser.parse_args()
    try:
        validate_selection(args.serial, args.classes)
        if not 0 < args.timeout < float("inf"):
            raise ValueError("The overall timeout must be finite and positive")
        # Cooperating invocations must never share app data or the real emulator SMS inbox.
        lock_path = ROOT / "app/build/test-results" / f"{args.serial}.lock"
        lock_path.parent.mkdir(parents=True, exist_ok=True)
        with lock_path.open("w") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ValueError("Another instrumentation run owns this emulator") from None
            artifacts = preflight(args.serial)
            return run(args, artifacts)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(2, f"Preflight failed: {error}\n")


if __name__ == "__main__":
    def interrupted(signum, frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, interrupted)
    raise SystemExit(main())
