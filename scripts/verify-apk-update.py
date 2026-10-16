#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Check package, certificate and version continuity before an in-place upgrade."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def inspect(apk, build_tools):
    signature = subprocess.check_output([str(build_tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", signature)
    if not certificates:
        raise ValueError("No verified signer certificate")
    badging = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package:
        raise ValueError("APK package identity is missing")
    with zipfile.ZipFile(apk) as archive:
        info = json.loads(archive.read("assets/build-info.json"))
    if info["versionCode"] != int(package[2]) or info["versionName"] != package[3]:
        raise ValueError("Embedded build identity disagrees with Android manifest")
    return dict(apk=apk.name, sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
                package=package[1], certificates=sorted(certificates), **info)


def verify(previous, candidate):
    if previous["package"] != candidate["package"]:
        raise ValueError("Package differs; this is not an in-place update")
    if previous["certificates"] != candidate["certificates"]:
        raise ValueError("Signing certificate differs; export projects before a separate installation")
    if candidate["versionCode"] <= previous["versionCode"]:
        raise ValueError("Candidate version code must increase for a new trial delivery")
    if not re.fullmatch(r"[0-9a-f]{40}", candidate["sourceRevision"]):
        raise ValueError("Candidate must identify an exact, clean source commit")
    return dict(previous=previous, candidate=candidate, packageMatches=True,
                signaturesMatch=True, versionIncreases=True,
                installationAndDataRetention="Not checked by this script; verify on a dedicated installation")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("previous", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--build-tools", type=Path,
                        default=Path(os.environ.get("ANDROID_HOME", "")) / "build-tools/36.0.0")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = verify(inspect(args.previous, args.build_tools), inspect(args.candidate, args.build_tools))
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
