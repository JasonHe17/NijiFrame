# Basic editor verification

## Repeatable checks

| Check | Scope |
| --- | --- |
| `:editor-core:test` | v1–v6 resource and parameter round-trip, source hashes, unsupported/corrupt ZIP rejection, history branches, failed-write recovery, geometry bounds/crop handles, no-upscale export dimensions, independent projects, rename/duplicate/delete and migration |
| Native CTest | Neutral identity, known linear-light EV, saturation and warmth, clipping/invalid values, constant-image and impulse sharpening, radial vignette symmetry and strip equivalence, ASan/UBSan |
| `EditorIntegrationTest` | Real Android ContentResolver/decoders/JNI, self-contained reopen after source deletion, all eight EXIF orientations, geometry and PNG dimensions, grayscale JPEG/GPS omission, sharpening strip seams, cancellation, latest-preview ownership, provider failures and automatic-save/new-viewmodel recovery |
| `ExportSafetyTest` | Preparation failure, real PNG encoding cancellation, commit-boundary cancellation, completed copy after cancellation, cache/provider errors, fully prepared JPEG/PNG/project output before destination open, and editor cancellation controls |
| `EditorExperienceTest` | Export settings cancellation preserves drafts, save-state explanation, shared brush settings, region/effect workflow and recreation, read-only comparison, large-text landscape controls and stable idle detail frames |
| `EditorUiTest` | Real system picker, pinch/pan/fit, draft cancel/apply, activity recreation, undo/redo, drag crop, flip, system Back with pending edits, landscape and portrait, PNG picker export and decoded pixels/dimensions, sharesheet, project duplicate/delete/rename/reopen |
| `LocalEditingTest` | Region pixels/overlay through crop, straightening, flips and rotation; portable export after private source deletion; approximate-to-reference settling; local draft history, save/reopen, real brush UI, edge slider drags under gesture navigation and Back outside the control |
| `DetailRendererTest` / `DetailInspectorTest` | Region versus full rendering with halos, all EXIF orientations, crop/rotation/flip and vignette coordinates, 100 MP original texture without a full bitmap, real screenshot pixel scale, comparison, activity recreation and unchanged tool drafts/projects |
| `DetailRegionTest` | Native overlay geometry, real detail brush over a base mask, padding/cancelled gesture rejection, compare, undo/redo/cancel, recreation, portable reopen/PNG agreement and stale frame rejection |
| `FileProjectTest`, `BoundedHeapProjectTest` | File/provider reads, v1–v6, malformed directory and payload rejection, staging cleanup, and a 64 MiB original import and project read in a separate 96 MiB JVM heap; the latter runs automatically with `:editor-core:test` |
| `LargeImageTest` | 12 MP JPEG full-size output and EXIF, 48 MP editable round trip and local/crop coordinates, 100 MP bounded preview and process-owner restore, 201 MP JPEG native detail/round-trip/reduced export, >32 MiB encoded image edit/save/library reopen/provider reopen/export integrity, project-read memory refusal preserving saved data, sampled-alpha rejection and memory refusal before truncating an output |
| `SofteningTest` | JNI/full-image versus halo-strip pixels, zero-strength and zero-mask identity, editable softening round trip and independent PNG decode |
| `PortraitRegionTest` | Real offline model inference with no face, failure/cancellation preserving existing edits, absence of network/upload components, synthetic non-face rejection and fallback cancellation, persisted base + manual correction, history, PNG and detail agreement |
| `:app:lintDebug`, `:app:assembleDebug` | Android static analysis and both configured native ABIs |

Use a dedicated test installation: the Gradle runner installs/uninstalls APKs, so export any projects that must survive a test run.

The UI test uses synthetic images and the actual system picker; it does not inject fake activity results or disable rendering. Accessibility locators are refreshed at interaction boundaries to avoid using stale nodes after Compose/DocumentUI updates. It uses UIAutomator 2.4.0. API 34+ accessibility-cache clearing is confined to test code. Runtime behavior does not depend on testing switches.

GitHub Actions runs Linux build/native checks and API 29/31/34/36/37.0 Android jobs plus an API 35 16 KiB-page job. Workflow reports, APKs, and available UI evidence are retained as artifacts for seven days. Always inspect checks on the commit being reviewed; a configuration file alone is not proof that remote CI passed.

## Manual acceptance and process recovery

Use synthetic or appropriately licensed images. Never commit private photographs or local machine records.

1. Import through the system picker. Cancel selection, try unsupported content, and ensure the current saved project remains available.
2. Drag a slider, cancel the tool, then repeat and apply. Undo/redo; create a new branch after undo. Check before/after comparison and reset confirmation.
3. Zoom, pan and fit the view. Crop with corner handles, move the selection, use free/fixed ratios, rotate/flip and straighten. Inspect output corners and ensure the crop tracks transformed coordinates.
4. Wait for “已保存”, force-stop the process, then launch again. The original and committed parameters must return. A draft labeled “调整待应用” is intentionally not durable.
5. Return home; rename, duplicate and edit a copy. Check that the original project remains unchanged. Cancel and then confirm deletion; external originals and exports must survive.
6. Export portable project and JPEG/PNG photos through the picker; inspect outputs independently. Change quality/size and verify no upscaling. Reopen a project after removing the original external file.
7. Share the rendered photo through Android's chooser; only the generated cache photo receives temporary read access. Force a provider failure or cancel work and verify the private project remains recoverable.
8. Check short/wide layouts, larger text, labels and touch targets. Rotate/recreate the activity while a tool draft exists. Confirm the draft remains available for application or cancellation.

## Coverage and limitations

Local checks use Linux x86_64 and Android 16/API 36 x86_64. Arm64 APK content is compiled; it has not been exercised on a phone. The declared API 26 minimum is not a tested device matrix. GitHub API 29/31/34/36 jobs provide separate hosted evidence when completed successfully. There are no Windows execution or physical-device performance claims.

The 210 MP / 64 MiB / 32,768-pixel-edge limits are current implementation bounds, not a promise of responsive operation on every phone. Native processing is cancellable between strips; platform decoder/provider I/O has platform-dependent cancellation latency. Saving uses synchronized file contents and atomic replacement, not a guarantee against all storage hardware failures. External providers can leave partial destinations on failure.

UI checks cover touch/accessibility labels and reconfiguration, not a complete TalkBack audit or real-user study. Working pixels and exports are opaque SDR/sRGB; input conversion and codec boundaries are described in photo-input.md. Softening and geometric portrait suggestions remain experimental; feature implementation or a dependency license is not patent clearance.

The positive-face evaluation uses separately retained licensed samples and real UI flows; these portraits are not checked into the repository or shipped. On the local API 36 emulator, six of eight evaluation images produced suggestions, P02/P05 failed explicitly, and a two-face composite was rejected. This is a small regression set, not an accuracy benchmark or aesthetic acceptance. The portable CI test exercises actual no-face inference and synthetic coverage; it does not claim to test the positive portrait set.

Historical foundation verification is preserved in Git history. Current v3 region semantics and v2 crop coordinates and project-library behavior are documented in `project-format.md` and must be used for new regression fixtures.

Large-image tests use generated synthetic inputs, not user photographs. A 100 MP import passing does not establish that a 100 MP full-resolution export fits a phone: original-size export still uses full-image bitmaps, and current memory admission can refuse it. Lower-resolution exports are explicit choices and use the original source, not a display screenshot. See [large-image handling](large-images.md).

`FaceSearchTest` checks odd-sized search-window coverage, source-coordinate remapping, duplicate-location versus separate-location decisions, and subsequent edit geometry. These are synthetic geometry checks, not face-recognition accuracy or a labeled portrait benchmark. Private licensed evaluations remain outside the repository and hosted CI.

`PhotoFormatTest` verifies common wide-gamut and SDR-base HDR input conversion, JPEG/PNG/HEIF/WebP/AVIF orientation and round trips, exact original preservation, detail/export color agreement, large sampled wide JPEG, codec memory refusal, and HDR-transfer/animation refusal. Its fixtures are generated colors; it is not a physical-phone or arbitrary-camera-format certification.

Phone compatibility jobs use a 6 GiB userdata partition and a 512 MiB application heap for positive full-output workflows, configured only in dedicated test emulators. Default 192 MiB API 31/34 images hit memory refusal during the combined suite; an isolated API 31 12 MP export passes. This is a test-resource distinction, not a product memory optimization or a low-memory certification. Budget-refusal assertions stay enabled. See [the device matrix](device-compatibility.md), including 16 KiB-page runtime and static ELF/ZIP checks.

The bounded-heap JVM test validates the project resource-loading path only, without Android codecs or bitmaps. It does not establish an Android heap requirement. Reports are under `editor-core/build/test-results/boundedHeapTest/` and `editor-core/build/reports/tests/boundedHeapTest/`; ordinary tests retain their existing report directories.

UI brush checks wait for both the actual completed detail frame and the accessibility canvas before injecting a touch. Accessibility node existence alone can describe the preceding frame while decoding/rendering is still in progress. Failure evidence retains a bounded chronology of frame readiness, brush mode and synthetic touch positions; real touch, coordinate, undo and persisted-output assertions remain enabled. See [Android test synchronization guidance](https://developer.android.com/develop/ui/compose/testing/synchronization).

CI unlocks dedicated emulators through `wm dismiss-keyguard`, without delivering a Menu key to the newly started launcher. A launcher ANR dialog can otherwise obscure unrelated UI checks. SDK installation has at most three attempts for download/archive errors; application tests are not automatically retried. Preserve bootstrap logs when distinguishing infrastructure and product failures.
