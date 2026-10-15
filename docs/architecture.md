# Initial architecture proposal

Status: basic editor implemented; final delivery checks are tracked separately. Android app, plain JVM editor-core and Android/JNI image-core are separate Gradle modules. Optional analysis and desktop integration remain planned.

## Responsibilities

| Area | Responsibility | Boundary |
| --- | --- | --- |
| Android app | Kotlin + Jetpack Compose UI, photo selection, lifecycle, export destination | Android framework types stay at platform boundaries |
| Editor state | Parameters, tool gestures, undo/redo, project schema and validation | Plain Kotlin data and logic; no Bitmap, Activity, or content URI as persistent identity |
| Pixel processing | CPU reference rendering through a small C-compatible boundary, initially C++17 | Explicit dimensions, stride, color representation, ownership and error handling; no UI objects |
| Analysis | Optional local region/landmark inference | Produces versioned data; separate from pixel effects and model download management |
| Project storage | Original resources, edit state, masks, atomic persistence and import validation | Portable resource IDs and relative paths; no machine-specific references |

The current modules are `app`, `editor-core` and `image-core` (JNI and standalone C++ source). Split further when ownership or build dependencies justify it. Android and desktop need not share their entire UI. Kotlin Multiplatform is a later option if actual non-JVM targets justify its build and dependency constraints.

The CPU implementation establishes reference behavior. GPU acceleration requires output comparisons and device evidence before replacing it. Analysis models remain replaceable; region failure must be visible and permit deliberate manual editing. A zero mask caused by failed detection must not be reported as a successful retouch.

## Rendering and responsiveness

Edits update one authoritative project state. A continuous slider or brush gesture creates one undo entry when committed. A new edit after undo discards the obsolete redo branch. Automatic analysis and rendering run away from the UI thread; cancellation and generation IDs prevent an old job from replacing a newer result.

Rendering, image decoding, masks and previews have a shared memory budget. Initial support limits must be established using decoded pixel counts and physical-device measurements, not compressed file size. Do not keep a full-resolution bitmap for every history entry.

The first color scope is SDR/sRGB. Normalize EXIF orientation once for the editing coordinate system and keep original bytes separately. Convert supported source profiles deliberately; reject unsupported formats or color representations with an actionable explanation. Experimental tone operations use linear sRGB; individual operations must state their own working space and version.

Sources up to 8 MP (and 8192 pixels per edge) retain the reference-preview path. Larger originals use a bounded decoded proxy; editor coordinates, masks and saved originals remain at source resolution. The UI labels this settled preview as approximate. Original-size export re-decodes the original after memory admission; explicitly reduced large-image exports choose a decode sample for the requested crop and output size. They never upscale the screen preview. The full-resolution processing definition is unchanged; lower-resolution detail filters can differ. See [large-image handling](large-images.md).

## Persistence and product behavior

Save projects separately from rendered photo exports. Save status means the durable state is committed; failed writes must preserve the previous valid project. A project carries its resources so moving or losing the original external file does not silently invalidate the saved work. Large-resource storage and deduplication can evolve behind this contract.

Local editing does not require an account. Downloaded models need a version, digest, source, license, progress and retry behavior. A missing model must not prevent basic editing. Export should make resolution, format and metadata policy explicit. Precise location metadata should be omitted by default, with original metadata retained inside the private project where applicable.

Accessibility starts with labeled actions, readable states, adequate touch targets and undoable destructive gestures. Portrait presets must not silently reshape faces or alter identity-related features.

## Implementation sequence

1. Bootstrap the Android app, reproducible Gradle wrapper, editor state and CI checks.
2. Import, display and adjust one image; persist a project and reopen it after process termination.
3. Export with verified orientation, crop, resolution and color behavior.
4. Explicit local masks and local exposure are implemented; a manually selected softening candidate is available experimentally; automatic analysis remains separate.
5. Preserve portable operation semantics; perform Windows read/modify/return validation when a Windows implementation is scheduled.
6. Improve performance and visual quality with physical-device measurements and user feedback.

Before packaging a release, review dependency and model notices, source distribution obligations, applicable patent questions and the behavior of unsupported project versions. These tasks do not turn exploratory results into release-quality guarantees.


## Basic editor state and navigation

The home screen lists independent UUID-backed projects. EditorViewModel owns the active project, parameter history, a transient tool draft and the current preview. Applying or switching tools commits a single parameter transaction; cancelling drops the draft. Configuration recreation retains that view model. Committed edits schedule an atomic save, and transitions/exports flush pending committed edits. Restoring a project reconstructs its parameters and original without restoring in-memory history.

Rendering uses cancellable strips and a monotonically increasing generation; older jobs cannot publish a preview over a newer state. Source decode/render work and project saves use separate mutexes. The crop tool draws an overlay over the full transformed source; dragging the rectangle updates only that overlay, and applying it renders the crop. Width/height, aspect and corner/move constraints live in editor-core rather than Android gesture coordinates.

The platform layer handles file pickers, project thumbnails, format/size choices and a non-exported FileProvider restricted to generated shared photos. Share grants read access only to the selected output. Neither the portable manifest nor the native processing boundary contains Android URIs. Windows UI and decoder integration remain separate future work.


## Local region editing

One source-coordinate region consists of bounded immutable add/erase strokes. It is stored as a separate checksummed resource in v3 packages. Geometry mapping is shared by input and the selection overlay; exposure is applied before geometry, so subsequent crop/rotation does not move the region relative to source content. The UI explicitly switches between drawing and moving the canvas. The local tool keeps a draft history for completed strokes and exposure gestures; applying creates one main-history transaction, cancelling discards it. Presets preserve the region; original comparison suppresses all adjustments, including local exposure.

The 1024-side coverage grid and source-unit brush radii are processing semantics, not a physical-device performance promise. Explicit limits bound resources and raster work. Automatic segmentation and edge snapping are not enabled. V4 adds a default-off softening candidate before global tone, using the same explicit region; its visual quality still needs user acceptance.

## Original-resolution detail inspection

A read-only inspector captures the effective editor parameters without committing the tool draft. The overview chooses a window in output coordinates; reverse crop, quarter-turn and flip locate the straightened region, and inverse straightening locates the source region. JPEG decoding and neighborhood filters receive their needed halos. Region decode normalizes EXIF using the same orientation matrix as full decode, then reuses the full pipeline color stages with original dimensions and offsets. Sharpening has an output halo; vignette coordinates refer to the whole cropped output.

Only a bounded completed frame is published. The inspector does not composite successive high-resolution patches into the main preview, maintain a tile cache or schedule different quality levels based on dragging. Pixel work shares the editor mutex; cancellation and generation checks prevent obsolete selection/comparison results from appearing. Region decoding itself is a synchronous platform operation, so cancellation cannot interrupt its native implementation. Memory admission is conservative and not a universal codec-allocation guarantee. Persistent project data and original-size export behavior are unchanged.
