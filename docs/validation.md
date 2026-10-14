# Foundation validation

The foundation covers import → parameter edits → project save → reopen → full-size export. It does not validate portrait retouching quality, Windows handoff, or phone performance.

## Automated checks

- `:editor-core:test`: resource and parameter round-trip, undo/redo branching, unsupported versions and operations, hashes, path rejection, truncated ZIP and special entries, bounds, and failed-write recovery.
- Native CTest: neutral identity, a known linear-light EV value, grayscale saturation, endpoint clipping and invalid inputs under AddressSanitizer/UndefinedBehaviorSanitizer.
- `:app:connectedDebugAndroidTest`: synthetic images through Android ContentResolver, actual decoding and JNI, saving and reopening after external-source deletion, portable project reading, JPEG output dimensions and grayscale pixels, metadata omission, all eight EXIF orientations, cancellation and transparency rejection; rapid edit/undo/redo verifies the latest preview wins, and a failed provider export/import preserves the recovery copy and current session. These tests require API 29+ storage APIs and currently run on API 36.
- `:app:lintDebug` and `:app:assembleDebug`: Android static checks and both configured ABIs. Dependency-update and KTX-style suggestions are not suppressed.

The checked-in GitHub Actions workflow runs these on Linux and an API 36 x86_64 emulator. A remote CI result exists only after the workflow is actually triggered in a hosted repository.

## Manual acceptance

Use synthetic or appropriately licensed images; do not add personal photographs to the repository.

1. Import a JPEG/opaque PNG using the system document picker. Test an unsupported file and verify that the previous editing session survives.
2. Pinch and pan the preview, then reset its view. Drag exposure/saturation; the preview updates after a short debounce, and completing a gesture creates one undo entry. Undo/redo, change direction after undo, compare to the original, crop and rotate.
3. Save the project. Force-stop the application and launch it again. Verify parameters and output geometry survive. Unsaved changes must be clearly indicated.
4. Export `.nijiproj` through the system picker. Import another photo, then open the saved archive. Verify the previous source and parameters return, even after removing the external source file.
5. Export JPEG through the system picker. Decode the exported file independently and compare dimensions and representative pixels. Check that GPS metadata is absent.
6. Cancel pickers and provoke a denied/unwritable destination. Verify no success is reported and the saved project remains recoverable. A provider may leave a partial document; retry using a new destination.

## Coverage limits

The first local run uses Linux x86_64 and Android 16/API 36 x86_64. Arm64 is built, not executed on a phone. API 26 is the provisional minimum configuration, not a tested device matrix. The current 8 MP / 32 MiB input limits are conservative implementation limits pending real-device measurements; memory exhaustion is reported but cannot guarantee that Android will never kill the process under pressure.

UI appearance, accessibility and provider behavior need broader device/user review. Only a centered square crop is exposed. Portrait algorithms, masks, automatic regions and Windows UI are future work. Patent review continues when concrete portrait algorithms or distinctive interactions are selected; the dependency inventory is not an FTO opinion.

## Local foundation result (2026-10-14–15)

- Nine JVM tests, four API 36 instrumentation tests and one native sanitizer test passed.
- APK build and Android Lint succeeded from both the working repository and an independent source directory using documented SDK/JDK environment variables. Lint reported zero errors; remaining suggestions concern dependency versions and KTX style.
- Manual system-picker flow passed: photo import, slider edits, crop/rotation, private save, force-stop/relaunch, portable export/open after deleting the external source, and JPEG export. Original bytes and saved parameters matched independently inspected archives.
- The synthetic 960×640 fixture produced a 640×640 crop/rotation export. Against an independent linear-sRGB calculation, JPEG mean absolute channel error was 0.517/255, 99th percentile 2/255, maximum 91/255 at sharp color edges (lossy JPEG); GPS metadata was absent. These numbers describe this fixture, not a general image-quality guarantee.
- The system picker initially appended `.zip`; the final export uses a binary MIME type and was verified to preserve `.nijiproj`, then reopened successfully.
- The final manifest has no Internet or broad storage permissions. The generated Gradle wrapper JAR hash matches the official 8.13 wrapper checksum; distribution downloads also verify their pinned SHA-256.
- GitHub-hosted CI, physical phones, other Android API levels and Windows were not run.
