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

A full-resolution render reduced to display size is the reference preview. Large images also produce a labeled 800-pixel interactive approximation before settling to the reference; export always uses the authoritative processing definition. Effect radii must have explicit units independent from display scale.

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
