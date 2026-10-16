# Local editing milestone

The 0.3.0-dev scope is one manually edited source-coordinate region with local exposure, plus bounded quality work on the existing editor. It does not include automatic face/skin analysis, skin smoothing, edge snapping, multiple local layers, GPU processing or Windows execution.

## Interaction and reference workflow

The user paints a region with neutral local exposure, can inspect/hide the colored coverage, then adjusts exposure independently. Add/erase change region coverage, not baked photo pixels. A separate move mode avoids confusing brush strokes with canvas pan/zoom. Brush diameter and hardness configure subsequent strokes. Local-tool undo/redo covers completed gestures; Apply creates a single main-history entry, Cancel drops the draft. Region data survives save, source removal, reopen and photo/project export.

The workflow was informed by official [Snapseed Brush](https://support.google.com/snapseed/answer/6157715?hl=en) and [Lightroom mask management](https://helpx.adobe.com/lightroom/mobile/masks-and-selective-adjustments/manage-masks-in-lightroom.html) documentation, reviewed 2026-10-15. These describe regional adjustments, erasing and mask visibility; this is a workflow reference, not a claim of hands-on parity, source reuse or patent clearance. Source, platform behavior and the actual implemented controls determine NijiFrame's scope.

## Acceptance checks

| Area | Evidence in this repository |
| --- | --- |
| Coverage | `LocalAdjustmentTest`: union, erase, feather, bounds and cancellation |
| Coordinates | `SourceGeometry` round trips; Android rendered pixels and overlay under crop, flips, quarter turns and straightening |
| Storage | v1/v2 migration and v3 mask integrity tests; portable export/reopen after private project deletion |
| Editing UI | Real touch strokes, movement mode zoom, local undo/redo, Apply/Cancel, main undo/redo and reopening |
| Preview | Large-image approximation settles to the authoritative full render; local state survives a new view model |
| Output | Native zero-mask identity and linear-light exposure; PNG decode compared with rendered pixels |

The [v3 contract](project-format.md) defines the coverage grid, brush units, resource limits and processing order. A 1024-side coverage grid is intended for local photo adjustments, not hair-level extraction. No performance promise is made for phones; the 210 MP input bound is a resource ceiling, not a device-performance guarantee. Fast previews are transient approximations and are visibly labeled; original-size export uses the full reference pipeline; explicitly reduced large-image exports use a sampled original, as described in [large-image handling](large-images.md). Preview quality and latency depend on the content and operations.

Relevant patent claims and actual interaction/dataflow must be reviewed together before adopting additional brush or automatic-region behavior. Independent implementation and source licenses do not grant unrelated third-party patent rights.

Current v7 supports four independently named regions, each with exposure, warmth, tint, saturation and experimental softening/protection. Selection changes only the editing target. Source-pixel corrections in the detail window are separate from legacy strokes; see [local regions](local-regions.md) and the [format contract](project-format.md).
