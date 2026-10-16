# SPDX-License-Identifier: GPL-3.0-only
"""Fault-injection checks for the observed root transport failure."""
import unittest
from unittest.mock import patch
from importlib.util import spec_from_file_location, module_from_spec
from pathlib import Path

spec = spec_from_file_location("prepare_emulator", Path(__file__).with_name("prepare-test-emulator.py"))
module = module_from_spec(spec)
spec.loader.exec_module(module)


class RootReconnectTest(unittest.TestCase):
    def test_closed_request_succeeds_only_after_verified_root(self):
        adb = module.Adb("emulator-5554")
        with patch.object(adb, "run", side_effect=[(1, "adb: unable to connect for root: closed"),
                                                  (1, "device offline"), (0, "0")]) as run, \
                patch.object(module.time, "sleep"):
            adb.root()
        self.assertEqual([call.args for call in run.call_args_list],
                         [("root",), ("shell", "id", "-u"), ("shell", "id", "-u")])

    def test_success_response_without_root_is_not_a_pass(self):
        adb = module.Adb("emulator-5554")
        with patch.object(adb, "run", return_value=(0, "restarting adbd as root")) as run, \
                patch.object(adb, "wait", side_effect=RuntimeError("not root")):
            with self.assertRaisesRegex(RuntimeError, "three root requests"):
                adb.root()
        self.assertEqual(run.call_count, 3)

    def test_non_rootable_image_fails_immediately(self):
        adb = module.Adb("emulator-5554")
        with patch.object(adb, "run", return_value=(1, "adbd cannot run as root in production builds")) as run:
            with self.assertRaisesRegex(RuntimeError, "rootable"):
                adb.root()
        self.assertEqual(run.call_count, 1)

    def test_hung_adb_is_bounded(self):
        with patch.object(module.subprocess, "run", side_effect=module.subprocess.TimeoutExpired("adb", 15)):
            self.assertEqual(module.Adb("emulator-5554").run("root")[0], 124)

    def test_legacy_simulator_cannot_silently_use_the_wrong_collector(self):
        adb = module.Adb("emulator-5554")
        with patch.object(adb, "run", return_value=(0, "zygote64: Using CollectorTypeCMC GC.")):
            with self.assertRaisesRegex(RuntimeError, "Expected zygote collector CC"):
                adb.require_collector("CC")
        with patch.object(adb, "run", return_value=(0, "zygote64: Using CollectorTypeCC GC.")):
            adb.require_collector("CC")


if __name__ == "__main__":
    unittest.main()
