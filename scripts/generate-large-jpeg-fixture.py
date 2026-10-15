#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Generate a synthetic 200 MP-class JPEG; optional authoring tool, Pillow 12.3.0.

Uses about 0.8 GiB on the authoring host. Android tests only decode sampled/region
pixels. No camera photograph, private metadata or downloaded media is used.
"""
from pathlib import Path
import hashlib
import json
from PIL import Image, ImageDraw

out = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/formats"
with Image.new("RGB", (16384, 12288), (80, 80, 80)) as image:
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 511, 511), fill=(180, 60, 70))
    # At the center, one-pixel stripes disappear in an 8x sampled preview.
    # Detail tests must retrieve these from the encoded original.
    for x in range(8064, 8320):
        draw.line((x, 6016, x, 6271), fill=(255, 255, 255) if x % 2 else (0, 0, 0))
    exif = Image.Exif()
    exif[274] = 6
    image.save(out / "large-201mp.jpg", quality=95, subsampling=0, exif=exif)
checksums = out / "sha256.json"
data = json.loads(checksums.read_text())
data["large-201mp.jpg"] = hashlib.sha256((out / "large-201mp.jpg").read_bytes()).hexdigest()
checksums.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")
