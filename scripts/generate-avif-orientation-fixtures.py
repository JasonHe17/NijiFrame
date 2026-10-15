#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Generate limited-range AVIF orientations; optional authoring with Pillow 12.3.0.
Existing full-range failure fixtures are retained unchanged.
"""
from pathlib import Path
import hashlib
import json
from PIL import Image, ImageDraw

out = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/formats"
with Image.new("RGB", (192, 128)) as image:
    draw = ImageDraw.Draw(image)
    for box, color in [((0, 0, 95, 63), (180, 60, 70)),
                       ((96, 0, 191, 63), (50, 160, 80)),
                       ((0, 64, 95, 127), (60, 80, 180)),
                       ((96, 64, 191, 127), (170, 150, 50))]:
        draw.rectangle(box, fill=color)
    for orientation in range(1, 9):
        exif = Image.Exif()
        exif[274] = orientation
        image.save(out / f"limited-{orientation}.avif", quality=95, range="limited", exif=exif)
checksums = out / "sha256.json"
data = json.loads(checksums.read_text())
for path in out.glob("limited-*.avif"):
    data[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
checksums.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")
