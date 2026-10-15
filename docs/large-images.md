# High-resolution photos

The current input ceiling is **210 MP / 64 MiB**, with a maximum edge of 32,768 pixels. These are independent limits. Current input compatibility, including wide-gamut conversion and HEIF/AVIF restrictions, is described in [photo input](photo-input.md).

## Editing and saved originals

The unchanged encoded original stays in the project. EXIF-normalized original dimensions define all crop and region coordinates. Opening, saving, exporting a project and reopening it do not replace the original with a thumbnail.

Sources above 8 MP or an 8192-pixel edge use a power-of-two decoder sample with an edge no larger than 2048 pixels. The screen preview is at most 1600 pixels and remains labeled as reduced. Zooming into the main canvas does not reveal original-resolution detail. In 0.6, **查看细节** opens a separate original-resolution window with region correction in the Local tool; see [detail inspection](detail-inspection.md). Smaller sources retain the existing reference-preview path. Sampled PNG inputs with potential alpha are checked in bounded original-resolution regions, so an isolated transparent pixel cannot disappear into the sample and bypass the opaque-input requirement.

## Photo export

- **Original size:** re-decode the original and run the full-resolution processing contract. Crop and rotation still determine the output dimensions.
- **Long edge 4000 / 2048 / 1280:** for large inputs, choose a decoder sample from the original that supplies at least the requested cropped-output edge, render at that working resolution, then resize to the displayed dimensions. No enlargement beyond the logical original/crop dimensions is requested. Rounding of sampled crop boundaries can cause a subpixel rescale; softening, sharpening and mask boundaries can differ from rendering full size and reducing afterward.
- The screen-preview bitmap is never the source for large-photo export. Output size is explicit and never silently changed after memory refusal.

Export still uses full working bitmaps rather than a streaming tiled encoder. Before decoding or opening the output, the app estimates peak source/output/transform buffers plus codec headroom and compares them with app and system memory estimates. If necessary it requests one collection of unreachable old buffers on a worker and checks again. A refusal leaves existing destination contents and saved project data intact. Device memory can change after admission, so this does not guarantee that all allocations or codecs will succeed.

A 200 MP-class import therefore does **not** promise a 200 MP original-size export on every device. A smaller export often helps; a tight crop can still require a high-resolution decode to preserve the selected detail. Save the editable project when a desired export does not fit. Very large encoded files also need memory for their bytes and project validation; the app can refuse import below the 64 MiB format limit under memory pressure. A memory-budget read failure requests collection and retries the read once with a fresh budget before reporting refusal.

V4 processing parameters are unchanged. Older app versions with smaller resource limits can reject these projects even though they understand v4. Back up projects before changing versions; do not uninstall an unbacked-up installation to resolve a signature conflict.

## Verification and implementation references

`ImageSizingTest` covers resource boundaries, crop-dependent decode planning and bounded reads. `LargeImageTest` exercises real Android codecs, JNI, project storage and output with synthetic 12 MP, 48 MP, 100 MP and 201 MP images, an encoded photo above 32 MiB, and memory refusal before destination truncation. These are emulator tests, not physical-phone performance measurements. The everyday-phone checks in [manual checks](manual-check.md) remain short normal editing trials.

Platform references: [Android large-bitmap decoding](https://developer.android.com/topic/performance/graphics/load-bitmap), [BitmapFactory.Options](https://developer.android.com/reference/android/graphics/BitmapFactory.Options), and [ActivityManager.MemoryInfo](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo). No new runtime dependency or model is introduced. Resource limits and preview scheduling are not patent-clearance arguments; relevant implementation comparisons remain necessary when changing the rendering architecture.

HEIF/AVIF require additional whole-frame codec headroom. A small requested ROI or sampled preview does not guarantee low internal codec memory. HEIF can fall back to full-frame decode and crop; AVIF uses the platform crop decoder. The original bounded JPEG/PNG path remains available.

The 210 MP ceiling includes 200 MP-class dimensions such as 16384×12288 (201,326,592 pixels). The new synthetic JPEG regression checks EXIF orientation, original-resolution one-pixel texture, editable round-trip, reduced export and memory refusal without full-size Android decode. It does not certify a particular camera or raise the 64 MiB byte limit. Earlier builds may reject a project above their pixel ceiling even if they understand its v6 schema. See the [device compatibility survey](device-compatibility.md).
