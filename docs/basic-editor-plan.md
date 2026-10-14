# Basic mobile editor delivery

Status: in progress. This checklist defines the requested complete basic editor, not just the earlier import/export foundation. Advanced portrait, local repair, AI, elaborate animation and Windows execution are deferred. Keep portable edit semantics and processing boundaries for future platforms.

## Evidence and scope

Reviewed 2026-10-15: [Snapseed tools](https://support.google.com/snapseed/answer/6183571?hl=en), [Lightroom mobile editing](https://helpx.adobe.com/be_en/lightroom-cc/using/edit-photos-mobile-android.html), [Lightroom colors](https://helpx.adobe.com/au/lightroom/mobile/adjust-light-and-color/adjust-colors.html), [mobile export](https://www.adobe.com/learn/lightroom-cc/web/lightroom-mobile-share). These are official descriptions, not a claim of hands-on testing every competitor. The selected baseline is a product-design inference from their common workflow; no competitor source, icons or screen layouts are copied.

| Requirement | Implementation / acceptance evidence required |
| --- | --- |
| Home and project management | Import image/open package; recent projects with preview/name/date; reopen, rename, duplicate and confirmed delete; empty/loading/error states |
| Editor shell | Persistent canvas and reachable tool/action areas, portrait/landscape layouts, tool navigation, apply/cancel drafts, system Back behavior, progress/cancel/retry |
| Canvas and history | Pinch/pan, double-tap/fit view, original comparison, one undo entry per committed tool/gesture, redo branching, reset confirmation |
| Geometry | Drag a free crop or choose common aspect ratios, reposition crop, rotate quarter-turns, horizontal/vertical flip, straighten with no blank corners; correct source coordinates |
| Light and color | Exposure, contrast, highlights, shadows, whites, blacks, saturation, vibrance, warmth and tint; neutral identity, explicit persisted semantics |
| Details and simple looks | Sharpening and vignette, a small original set of editable built-in presets; no model downloads |
| Reliable editable storage | Multiple independent projects, automatic save after committed edits, last-project resume, save status/retry, v1 migration, self-contained portable export and corruption rejection |
| Export and sharing | JPEG/PNG choice, JPEG quality, original/reduced dimensions with no upscaling, save via picker, local Android share intent, clean metadata default, errors preserve project |
| Accessibility and continuity | Labels, adequate touch targets, readable errors, configuration changes preserve edits/drafts, unsaved-work protection |
| Version control | Baseline commit/tag; feature work in reviewable commits, clean diffs and no private assets; no destructive history rewriting |
| GitHub CI | Push and inspect real hosted checks; build/lint/JVM/native tests, Android integration/UI checks, retained reports/APK; no secrets in code |
| Verification | Actual picker and editor flows, rendered UI in multiple sizes, geometry/pixels, process restart/autosave, project isolation, failure/cancellation, final requirement audit |

## Implementation order

1. Version the existing working foundation, extend portable edit semantics and tested CPU operations.
2. Implement multi-project storage, automatic saves, export settings and share boundary.
3. Build home/editor/tool panels and crop interaction around those capabilities.
4. Exercise actual UI/storage/render paths, fix failures, publish changes to GitHub and inspect CI.
5. Audit every row above against current source and test/runtime evidence before marking complete.

A missing GitHub login delays hosted CI only; local implementation and checks continue. Physical-phone performance and Windows runtime checks are explicitly outside this delivery. The initial supported image-format/memory boundaries remain visible until separately validated.

## Patent and dependency boundary

The current scope uses explicit global adjustments, geometric transforms, a small unsharp mask and radial vignette. No face/skin detector, learned retouching, edge-snapping brush or server-controlled effect sharing is introduced. Existing patent research remains a list of engineering investigation leads, not clearance. Review the actual new processing and UI together with relevant claims/designs; independence of source code or a permissive dependency license is not a third-party patent opinion. New dependencies/assets require provenance and license review.
