# Basic editor verification

## Repeatable checks

| Check | Scope |
| --- | --- |
| `:editor-core:test` | v1–v5 resource and parameter round-trip, source hashes, unsupported/corrupt ZIP rejection, history branches, failed-write recovery, geometry bounds/crop handles, no-upscale export dimensions, independent projects, rename/duplicate/delete and migration |
| Native CTest | Neutral identity, known linear-light EV, saturation and warmth, clipping/invalid values, constant-image and impulse sharpening, radial vignette symmetry and strip equivalence, ASan/UBSan |
| `EditorIntegrationTest` | Real Android ContentResolver/decoders/JNI, self-contained reopen after source deletion, all eight EXIF orientations, geometry and PNG dimensions, grayscale JPEG/GPS omission, sharpening strip seams, cancellation, latest-preview ownership, provider failures and automatic-save/new-viewmodel recovery |
| `EditorUiTest` | Real system picker, pinch/pan/fit, draft cancel/apply, activity recreation, undo/redo, drag crop, flip, system Back with pending edits, landscape and portrait, PNG picker export and decoded pixels/dimensions, sharesheet, project duplicate/delete/rename/reopen |
| `LocalEditingTest` | Region pixels/overlay through crop, straightening, flips and rotation; portable export after private source deletion; approximate-to-reference settling; local draft history, save/reopen and real brush UI |
| `DetailRendererTest` / `DetailInspectorTest` | Region versus full rendering with halos, all EXIF orientations, crop/rotation/flip and vignette coordinates, 100 MP original texture without a full bitmap, real screenshot pixel scale, comparison, activity recreation and unchanged tool drafts/projects |
| `LargeImageTest` | 12 MP JPEG full-size output and EXIF, 48 MP editable round trip and local/crop coordinates, 100 MP bounded preview and process-owner restore, >32 MiB encoded image integrity, sampled-alpha rejection and memory refusal before truncating an output |
| `SofteningTest` | JNI/full-image versus halo-strip pixels, zero-strength and zero-mask identity, editable softening round trip and independent PNG decode |
| `PortraitRegionTest` | Real offline model inference with no face, failure/cancellation preserving existing edits, absence of network/upload components, persisted base + manual correction, history, PNG and detail agreement |
| `:app:lintDebug`, `:app:assembleDebug` | Android static analysis and both configured native ABIs |

Use a dedicated test installation: the Gradle runner installs/uninstalls APKs, so export any projects that must survive a test run.

The UI test uses synthetic images and the actual system picker; it does not inject fake activity results or disable rendering. Accessibility locators are refreshed at interaction boundaries to avoid using stale nodes after Compose/DocumentUI updates. It uses UIAutomator 2.4.0. API 34+ accessibility-cache clearing is confined to test code. Runtime behavior does not depend on testing switches.

GitHub Actions runs Linux build/native checks and API 29/36 Android jobs. Workflow reports, APKs, and available UI evidence are retained as artifacts for seven days. Always inspect checks on the commit being reviewed; a configuration file alone is not proof that remote CI passed.

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

Local checks use Linux x86_64 and Android 16/API 36 x86_64. Arm64 APK content is compiled; it has not been exercised on a phone. The declared API 26 minimum is not a tested device matrix. GitHub API 29/36 jobs provide separate hosted evidence when completed successfully. There are no Windows execution or physical-device performance claims.

The 100 MP / 64 MiB / 32,768-pixel-edge limits are current implementation bounds, not a promise of responsive operation on every phone. Native processing is cancellable between strips; platform decoder/provider I/O has platform-dependent cancellation latency. Saving uses synchronized file contents and atomic replacement, not a guarantee against all storage hardware failures. External providers can leave partial destinations on failure.

UI checks cover touch/accessibility labels and reconfiguration, not a complete TalkBack audit or real-user study. Images are SDR/sRGB and opaque; new formats need explicit decoder/color tests. Softening and geometric portrait suggestions remain experimental; feature implementation or a dependency license is not patent clearance.

The positive-face evaluation uses separately retained licensed samples and real UI flows; these portraits are not checked into the repository or shipped. On the local API 36 emulator, six of eight evaluation images produced suggestions, P02/P05 failed explicitly, and a two-face composite was rejected. This is a small regression set, not an accuracy benchmark or aesthetic acceptance. The portable CI test exercises actual no-face inference and synthetic coverage; it does not claim to test the positive portrait set.

Historical foundation verification is preserved in Git history. Current v3 region semantics and v2 crop coordinates and project-library behavior are documented in `project-format.md` and must be used for new regression fixtures.

Large-image tests use generated synthetic inputs, not user photographs. A 100 MP import passing does not establish that a 100 MP full-resolution export fits a phone: original-size export still uses full-image bitmaps, and current memory admission can refuse it. Lower-resolution exports are explicit choices and use the original source, not a display screenshot. See [large-image handling](large-images.md).
