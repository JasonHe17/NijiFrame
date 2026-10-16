#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Prepare a dedicated, rootable emulator. Never retry application tests."""
import argparse
import subprocess
import time


class Adb:
    def __init__(self, serial):
        self.serial = serial

    def run(self, *args):
        try:
            result = subprocess.run(["adb", "-s", self.serial, *args], text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
            return result.returncode, result.stdout.strip()
        except subprocess.TimeoutExpired:
            return 124, "ADB command timed out"

    def require(self, *args):
        code, output = self.run(*args)
        if code:
            raise RuntimeError(f"adb {' '.join(args)}: {output}")
        return output

    def wait(self, args, predicate, seconds=90):
        deadline = time.monotonic() + seconds
        while True:
            code, output = self.run(*args)
            if code == 0 and predicate(output):
                return output
            if time.monotonic() >= deadline:
                raise RuntimeError(f"Readiness timed out: {' '.join(args)}: {output}")
            time.sleep(1)

    def root(self):
        for attempt in range(3):
            code, output = self.run("root")
            print(f"root request {attempt + 1}: exit={code}, {output}", flush=True)
            if "cannot run as root" in output:
                raise RuntimeError("A rootable test image is required")
            # adbd can close the root request's connection while restarting. Its
            # response code alone does not establish the resulting privilege.
            try:
                self.wait(("shell", "id", "-u"), lambda value: value == "0", seconds=20)
                return
            except RuntimeError:
                pass
        raise RuntimeError("adbd did not reconnect as uid 0 after three root requests")

    def require_collector(self, expected):
        output = self.require("logcat", "-d", "-s", "zygote64:I")
        if f"Using CollectorType{expected} GC." not in output:
            raise RuntimeError(f"Expected zygote collector {expected}; check the test kernel: {output}")


def prepare(adb, page_size, expected_gc=None):
    adb.wait(("shell", "getprop", "sys.boot_completed"), lambda value: value == "1", seconds=180)
    if adb.require("shell", "getconf", "PAGE_SIZE") != str(page_size):
        raise RuntimeError("Unexpected emulator page size")
    adb.root()
    adb.require("shell", "stop", "zygote")
    adb.wait(("shell", "getprop", "init.svc.zygote"), lambda value: value == "stopped", seconds=60)
    for name in ("dalvik.vm.heapsize", "dalvik.vm.heapgrowthlimit"):
        adb.require("shell", "setprop", name, "512m")
    adb.require("shell", "setprop", "sys.boot_completed", "0")
    adb.require("shell", "start", "zygote")
    adb.wait(("shell", "getprop", "sys.boot_completed"), lambda value: value == "1", seconds=180)
    adb.wait(("shell", "pm", "path", "android"), lambda value: value.startswith("package:"))
    adb.wait(("shell", "cmd", "activity", "get-current-user"), lambda value: value == "0")
    if adb.require("shell", "getprop", "dalvik.vm.heapgrowthlimit") != "512m":
        raise RuntimeError("Test heap budget was not applied")
    if expected_gc:
        adb.require_collector(expected_gc)
    adb.require("shell", "wm", "dismiss-keyguard")
    for name in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        adb.require("shell", "settings", "put", "global", name, "0")
    # Start and wait for HOME without injecting a key into a starting launcher.
    home = adb.require("shell", "am", "start", "-W", "-a", "android.intent.action.MAIN",
                       "-c", "android.intent.category.HOME")
    if "Status: ok" not in home or "Error:" in home:
        raise RuntimeError(f"Launcher did not become ready: {home}")
    print("Emulator framework ready; application tests have not run", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--page-size", type=int, choices=(4096, 16384), default=4096)
    parser.add_argument("--expect-gc", choices=("CC", "CMC"))
    args = parser.parse_args()
    prepare(Adb(args.serial), args.page_size, args.expect_gc)
