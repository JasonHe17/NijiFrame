#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Write checksummed provenance next to an APK using its embedded build metadata."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

apk = Path(sys.argv[1])
with zipfile.ZipFile(apk) as archive:
    metadata = json.loads(archive.read('assets/build-info.json'))
metadata.update(apk=apk.name, sha256=hashlib.sha256(apk.read_bytes()).hexdigest())
apk.with_suffix('.provenance.json').write_text(json.dumps(metadata, indent=2) + '\n')
apk.with_suffix(apk.suffix + '.sha256').write_text(f"{metadata['sha256']}  {apk.name}\n")
print(json.dumps(metadata, indent=2))
