# Synthetic format fixtures

All image content here is generated from constant colors by this project, under GPL-3.0-only. There are no photographs, user images, camera samples or downloaded media. Assets enter the instrumentation test APK only, not the product APK.

`quadrants-*` are 192×128 images with four RGB quadrants: (180,60,70), (50,160,80), (60,80,180), (170,150,50). Suffixes 1/2/6 encode normal, mirrored and rotated orientation. HEIF quadrants use limited-range HEVC; `full-range.heic` retains the original full-range case that exposed the API 29 platform color-range defect. Its known limitation and warning are tested separately without claiming corrected colors. `pq.heic` uses the same synthetic content explicitly labeled with a PQ transfer for rejection testing; it is not an HDR quality reference. `animated.webp` is a two-frame rejection fixture.

Regenerate these with `scripts/generate-format-fixtures.py`, using Pillow 12.3.0 and pillow-heif 1.1.1. These are optional fixture-authoring tools; ordinary Gradle builds do not install them. Codec versions and profile timestamps can change encoded bytes while preserving fixture meaning.

`ultrahdr.jpg` and `ultrahdr-p3.jpg` were generated on Android API 36 by `PhotoFormatTest.ultraHdrJpegUsesSdrBaseAndKeepsOriginalGainmap`: 192×128 constant (100,120,140) encoded in sRGB or Display P3, a 96×64 constant (190,190,190) gainmap, maximum ratios 4 and full-HDR ratio 4, JPEG quality 100. The test regenerates them at runtime on API 34+ and tests these committed bytes on older systems. Regeneration outputs `cache/synthetic-ultrahdr*.jpg` in the test application, retrievable with `adb exec-out run-as dev.nijiframe.app cat ...` on a dedicated emulator.

The test also generates wide-gamut and 16-bit PNG fixtures in memory. `sha256.json` records the committed encoded fixture hashes. Third-party encoding tools and Android retain their own licenses; they are not bundled as product dependencies.

`large-201mp.jpg` is a generated 16384×12288 RGB JPEG (201,326,592 pixels), EXIF orientation 6. It has a gray background, a colored corner and a central one-pixel stripe patch. Regenerate with `scripts/generate-large-jpeg-fixture.py` (Pillow 12.3.0); this authoring step needs about 0.8 GiB host memory. The Android test uses sampled and region decoding only. This is a dimension/coordinate/texture regression, not a manufacturer photograph or a realistic compressed-file-size benchmark.
