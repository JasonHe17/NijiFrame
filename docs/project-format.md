# Editable project format v3

Status: implemented experimental processing contract, not a stable cross-platform guarantee. File extension `.nijiproj`; Android document export uses `application/octet-stream` to preserve this extension.

## Container and validation

ZIP contains `project.json` (UTF-8 JSON, at most 16 KiB) and `assets/original` (unchanged encoded image, at most 32 MiB). An optional `assets/local-mask.json` contains the v3 region (at most 512 KiB). Complete archive limit: 33.5 MiB. Reading validates the central directory and paths, regular-file modes, payload CRCs, length, SHA-256, operation version and numerical bounds. Duplicate/unexpected paths, comments, ZIP64, encryption, missing resources and incomplete archives fail. No archive paths are extracted to the filesystem.

The manifest contains `version: 3`, `pipeline: "srgb-local-v3"`, `source: "assets/original"`, `sourceBytes`, lowercase `sourceSha256`, source-oriented `width` / `height`, a project `name` (1–80 characters, no control characters), and `edit`. Unknown fields and unsupported operations are rejected. Original source dimensions are at most 8,000,000 pixels after EXIF normalization; the decoded resource must match. Platform URIs and filesystem paths are never portable identifiers.

## Edit parameters and order

| Parameter | Default / range | Meaning |
| --- | --- | --- |
| exposureEv | 0 / [-3,3] | Linear sRGB gain `2^EV` |
| contrast | 0 / [-1,1] | Linear contrast gain `2^contrast`, pivot 0.18 |
| shadows, highlights, whites, blacks | 0 / [-1,1] each | Smooth luminance-weighted lifts described below |
| warmth, tint | 0 / [-1,1] each | Relative channel gains, not a calibrated Kelvin temperature |
| saturation | 1 / [0,2] | Linear-light chroma multiplier |
| vibrance | 0 / [-1,1] | Saturation multiplier weighted toward lower existing chroma |
| straighten | 0 / [-45,45] degrees | Center rotation with automatic scale to cover the full source frame |
| flipHorizontal, flipVertical | false | Source-axis flips after straightening |
| turns | 0 / integer 0..3 | Clockwise quarter-turns after flips |
| crop | null | Half-open integer rectangle in the full transformed image; null means full image |
| sharpen | 0 / [0,2] | Unsharp amount using a 3×3 Gaussian stencil in encoded RGB |
| vignette | 0 / [-1,1] | Radial exposure falloff; positive darkens corners |

1. Decode opaque 8-bit SDR/sRGB, normalize EXIF orientation once and retain original encoded bytes. Current supported input remains JPEG/opaque PNG; transparency, detected HDR and unsupported color spaces fail explicitly.
2. Decode each sRGB channel to linear light with the standard 0.04045/12.92, 0.055/1.055, 2.4 transfer function, then apply exposure.
3. Let `Y = clamp(0.2126 R + 0.7152 G + 0.0722 B, 0, 1)`, `S=(1-Y)^2`, `H=Y^2`. The additive lift is `0.25*(shadows*S + highlights*H + whites*H^2) + 0.15*blacks*S^2`. Apply lift and contrast about 0.18, flooring negative values at zero.
4. Multiply R/G/B respectively by `2^(0.4*warmth)`, `2^(-0.3*tint)`, `2^(-0.4*warmth)`. With `chroma=(max-min)/max` (zero at black), effective saturation is `saturation*(1+vibrance*(1-chroma))`. Mix each channel around its linear luminance, clip [0,1], apply inverse sRGB transfer, and round to nearest 8-bit value. If all new tone parameters are zero, use the exact v1 tone path; neutral tone is decoded-pixel identity.
5. Apply local exposure as defined below, then straighten around the full image center. With `a=abs(angle)`, cover scale is `max(cos(a)+sin(a)*height/width, cos(a)+sin(a)*width/height)`. Output size remains source width/height; resampling is bilinear. Apply the two source-axis flips, then quarter-turn rotation.
6. Apply `crop` in these transformed coordinates. Changing rotation or display-axis flip in the UI transforms the crop rectangle correspondingly to preserve the selected content. A new crop selection is made directly over the transformed image.
7. Sharpen encoded RGB with `source + amount*(source - gaussian3x3(source))`, clamped and rounded. Gaussian weights are the outer product of [1,2,1]/4; edge neighbors clamp. Strip processing includes a one-row halo and matches whole-image processing.
8. For vignette, normalize x/y to [-1,1] (0 on a singleton axis), `r=(x^2+y^2)/2`; apply linear-light gain `2^(-1.5*vignette*r^2)`, then encode. Its center and extent are the final cropped frame.

Settled preview reduces the full authoritative result to a maximum side of 1600 pixels. Sources above 1600 pixels may first render an 800-pixel approximation, explicitly labeled in the UI; after a 250ms settling interval the full reference replaces it. Crop edges scale with bounded integer rounding for the approximation; sharpening and region boundaries can differ at this scale. Source coordinates and stored parameters never use preview coordinates. The approximation is not used for export, durable thumbnails or authoritative pixel comparisons. Export optionally downsizes after all operations without upscaling; supports JPEG quality 1–100 and lossless PNG. Source metadata is not copied into rendered exports; portable projects retain it in the original resource.

## Local region and exposure

`edit.local.exposureEv` is in [-3,3], default 0. Nonempty regions are serialized separately from the manifest. The manifest's nullable `localMask` contains `path: "assets/local-mask.json"`, exact byte length and lowercase SHA-256. A descriptor requires that resource, and a resource without a descriptor is rejected. Mask JSON is `{ "strokes": [...] }`; each stroke has `points` (`x`, `y` in [0,1]), `radius` [.005,.25] relative to the shorter source dimension, `hardness` [0,1], and `erase` (false by default). Points use pixel-edge coordinates of the EXIF-normalized original. Limits: 128 strokes, 4096 total points; every stroke must have at least one point, and all floats must be finite.

Coverage is rasterized on a source-aspect grid: scale = min(1, 1024/max(width,height)); grid dimensions round to nearest integer, at least one. At each grid pixel center, compute source-space distance to the closest segment of each stroke (a singleton is a circle). Weight is 1 inside hardness×radius, zero at/outside radius and linear between. Quantize to nearest 8-bit weight. Segments within one stroke use maximum coverage. Add strokes take max(previous, stroke); erase strokes take min(previous, 255-stroke). Repeated brushing does not accumulate exposure. Validation rejects a conservative estimate above 64 million segment bounding-box pixel visits, and raster work also enforces this ceiling and is cancellable between rows.

For each full-resolution source pixel, bilinearly sample the coverage grid at the normalized pixel center, clamp grid edges and round to an 8-bit weight `m`. After global tone has been encoded to 8-bit sRGB, decode to linear light and multiply each channel by `1 + (2^localEV - 1)*m/255`, clip, encode and round. Zero EV or zero coverage is exact identity. This stage precedes all geometry, sharpening and vignette; subsequent global sharpening can affect neighborhoods crossing the region boundary. It is not an effect painted repeatedly into the original pixels.

The source-to-output mapping is: center straighten with cover scale → source-axis flips → clockwise quarter-turn → crop translation. Screen fit, zoom and pan are presentation only; input uses the inverse mapping. The colored selection overlay is separate from rendered output and never exported. History retains immutable region descriptions rather than full-resolution bitmaps; local tool history is transient until applied.

## v1 / v2 migration

Read the v1 `srgb-tone-crop-turn-v1` pipeline with its original source-coordinate crop, exposure, saturation and turns. All v2-only controls must be neutral in a v1 manifest. Convert the old crop rectangle into the quarter-turned full-image coordinate system, then upgrade to v3. This gives the same output for the supported v1 operations; never silently change a crop or drop an effect. V2 keeps its geometry and global adjustment semantics, with an empty local region and zero local exposure. Legacy versions cannot contain local operations or mask resources. New current projects save as v3. Older apps are expected to reject v3 rather than pretend it is editable.

## Local persistence boundary

The platform-independent library assigns private UUID filenames and stores one self-contained archive per project. Listing reads bounded manifest metadata, while opening validates the full package. Renaming, duplicating and deleting projects operate on these private identifiers, not user-controlled paths. An old single recovery file is migrated with a deterministic identity and marker; migration preserves the old file and is retry-safe.

Each archive save writes a same-directory temporary file, finishes ZIP output, syncs the file and atomically replaces the destination. A failed write preserves the previous complete archive. The last-project pointer is updated separately and can fall back to existing projects. This is process-interruption recovery, not a universal device power-loss guarantee. Android automatically saves committed edits after a short debounce, saves before opening export destinations, and exposes durable-save status/retry. Tool drafts remain transient until applied.

System document providers do not guarantee atomic replacement. Keep a valid private project before external export; errors may leave a partial destination, which must not be reported as success. History stores parameters only; cross-session full undo history remains optional. Actual Windows execution and cross-decoder numerical comparisons are deferred.
