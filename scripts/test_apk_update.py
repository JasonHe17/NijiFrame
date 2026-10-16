# SPDX-License-Identifier: GPL-3.0-only
import copy
from importlib.util import spec_from_file_location, module_from_spec
from pathlib import Path
import unittest

spec = spec_from_file_location("apk_update", Path(__file__).with_name("verify-apk-update.py"))
module = module_from_spec(spec)
spec.loader.exec_module(module)


class UpgradeIdentityTest(unittest.TestCase):
    def setUp(self):
        self.previous = dict(package="dev.nijiframe.app", certificates=["certificate"], versionCode=16)
        self.candidate = dict(self.previous, versionCode=17, sourceRevision="a" * 40)

    def test_compatible_identity_does_not_claim_data_retention(self):
        result = module.verify(self.previous, self.candidate)
        self.assertTrue(result["signaturesMatch"])
        self.assertIn("Not checked", result["installationAndDataRetention"])

    def test_different_key_package_or_non_increasing_version_is_rejected(self):
        for change in (dict(certificates=["different"]), dict(package="another.app"), dict(versionCode=16)):
            with self.subTest(change=change), self.assertRaises(ValueError):
                module.verify(self.previous, dict(self.candidate, **change))

    def test_dirty_or_unknown_source_cannot_be_delivered_as_exact_commit(self):
        for revision in ("unknown", "a" * 40 + "-dirty"):
            candidate = copy.copy(self.candidate)
            candidate["sourceRevision"] = revision
            with self.subTest(revision=revision), self.assertRaises(ValueError):
                module.verify(self.previous, candidate)


if __name__ == "__main__":
    unittest.main()
