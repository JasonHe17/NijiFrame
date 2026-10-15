# High-resolution photos

0.5.0-dev expands input from 8 MP / 32 MiB to **100 MP / 64 MiB**, with a maximum edge of 32,768 pixels. These are independent limits. Supported encodings remain SDR/sRGB JPEG and 8-bit opaque PNG; increasing dimensions does not add RAW, HEIF, HDR or wide-gamut support.

## Editing and saved originals

The unchanged encoded original stays in the project. EXIF-normalized original dimensions define all crop and region coordinates. Opening, saving, exporting a project and reopening it do not replace the original with a thumbnail.

Sources above 8 MP or an 8192-pixel edge use a power-of-two decoder sample with an edge no larger than 2048 pixels. The screen preview is at most 1600 pixels and remains labeled as reduced. Zooming into the main canvas does not reveal original-resolution detail. In 0.6, **查看细节** opens a separate, read-only original-resolution window; see [detail inspection](detail-inspection.md). Smaller sources retain the existing reference-preview path. Sampled PNG inputs with potential alpha are checked in bounded original-resolution regions, so an isolated transparent pixel cannot disappear into the sample and bypass the opaque-input requirement.

## Photo export

- **Original size:** re-decode the original and run the full-resolution processing contract. Crop and rotation still determine the output dimensions.
- **Long edge 4000 / 2048 / 1280:** for large inputs, choose a decoder sample from the original that supplies at least the requested cropped-output edge, render at that working resolution, then resize to the displayed dimensions. No enlargement beyond the logical original/crop dimensions is requested. Rounding of sampled crop boundaries can cause a subpixel rescale; softening, sharpening and mask boundaries can differ from rendering full size and reducing afterward.
- The screen-preview bitmap is never the source for large-photo export. Output size is explicit and never silently changed after memory refusal.

Export still uses full working bitmaps rather than a streaming tiled encoder. Before decoding or opening the output, the app estimates peak source/output/transform buffers plus codec headroom and compares them with app and system memory estimates. If necessary it requests one collection of unreachable old buffers on a worker and checks again. A refusal leaves existing destination contents and saved project data intact. Device memory can change after admission, so this does not guarantee that all allocations or codecs will succeed.

A 100 MP import therefore does **not** promise a 100 MP original-size export on every device. A smaller export often helps; a tight crop can still require a high-resolution decode to preserve the selected detail. Save the editable project when a desired export does not fit. Very large encoded files also need memory for their bytes and project validation; the app can refuse import below the 64 MiB format limit under memory pressure. A memory-budget read failure requests collection and retries the read once with a fresh budget before reporting refusal.

V4 processing parameters are unchanged. Older app versions with smaller resource limits can reject these projects even though they understand v4. Back up projects before changing versions; do not uninstall an unbacked-up installation to resolve a signature conflict.

## Verification and implementation references

`ImageSizingTest` covers resource boundaries, crop-dependent decode planning and bounded reads. `LargeImageTest` exercises real Android codecs, JNI, project storage and output with synthetic 12 MP, 48 MP and 100 MP images, an encoded photo above 32 MiB, and memory refusal before destination truncation. These are emulator tests, not physical-phone performance measurements. The everyday-phone checks in [manual checks](manual-check.md) remain short normal editing trials.

Platform references: [Android large-bitmap decoding](https://developer.android.com/topic/performance/graphics/load-bitmap), [BitmapFactory.Options](https://developer.android.com/reference/android/graphics/BitmapFactory.Options), and [ActivityManager.MemoryInfo](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo). No new runtime dependency or model is introduced. Resource limits and preview scheduling are not patent-clearance arguments; relevant implementation comparisons remain necessary when changing the rendering architecture.
