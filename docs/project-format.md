# Editable project format v2

Status: implemented experimental processing contract, not a stable cross-platform guarantee. File extension `.nijiproj`; Android document export uses `application/octet-stream` to preserve this extension.

## Container and validation

ZIP has exactly `project.json` (UTF-8 JSON, at most 16 KiB) and `assets/original` (unchanged encoded image, at most 32 MiB). Complete archive limit: 33 MiB. Reading validates the central directory and paths, regular-file modes, payload CRCs, length, SHA-256, operation version and numerical bounds. Duplicate/unexpected paths, comments, ZIP64, encryption, missing resources and incomplete archives fail. No archive paths are extracted to the filesystem.

The manifest contains `version: 2`, `pipeline: "srgb-basic-v2"`, `source: "assets/original"`, `sourceBytes`, lowercase `sourceSha256`, source-oriented `width` / `height`, a project `name` (1–80 characters, no control characters), and `edit`. Unknown fields and unsupported operations are rejected. Original source dimensions are at most 8,000,000 pixels after EXIF normalization; the decoded resource must match. Platform URIs and filesystem paths are never portable identifiers.

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
5. Straighten around the full image center. With `a=abs(angle)`, cover scale is `max(cos(a)+sin(a)*height/width, cos(a)+sin(a)*width/height)`. Output size remains source width/height; resampling is bilinear. Apply the two source-axis flips, then quarter-turn rotation.
6. Apply `crop` in these transformed coordinates. Changing rotation or display-axis flip in the UI transforms the crop rectangle correspondingly to preserve the selected content. A new crop selection is made directly over the transformed image.
7. Sharpen encoded RGB with `source + amount*(source - gaussian3x3(source))`, clamped and rounded. Gaussian weights are the outer product of [1,2,1]/4; edge neighbors clamp. Strip processing includes a one-row halo and matches whole-image processing.
8. For vignette, normalize x/y to [-1,1] (0 on a singleton axis), `r=(x^2+y^2)/2`; apply linear-light gain `2^(-1.5*vignette*r^2)`, then encode. Its center and extent are the final cropped frame.

Preview reduces the full authoritative result to a maximum side of 1600 pixels. Export optionally downsizes after all operations without upscaling; supports JPEG quality 1–100 and lossless PNG. Source metadata is not copied into rendered exports; portable projects retain it in the original resource.

## v1 migration

Read the v1 `srgb-tone-crop-turn-v1` pipeline with its original source-coordinate crop, exposure, saturation and turns. All v2-only controls must be neutral in a v1 manifest. Convert the old crop rectangle into the quarter-turned full-image coordinate system, then upgrade to v2. This gives the same output for the supported v1 operations; never silently change a crop or drop an effect. New saves use v2. Older apps are expected to reject v2 rather than pretend it is editable.

## Local persistence boundary

The platform-independent library assigns private UUID filenames and stores one self-contained archive per project. Listing reads bounded manifest metadata, while opening validates the full package. Renaming, duplicating and deleting projects operate on these private identifiers, not user-controlled paths. An old single recovery file is migrated with a deterministic identity and marker; migration preserves the old file and is retry-safe.

Each archive save writes a same-directory temporary file, finishes ZIP output, syncs the file and atomically replaces the destination. A failed write preserves the previous complete archive. The last-project pointer is updated separately and can fall back to existing projects. This is process-interruption recovery, not a universal device power-loss guarantee. Android automatic-save/UI integration is tracked in `basic-editor-plan.md` until implemented and verified.

System document providers do not guarantee atomic replacement. Keep a valid private project before external export; errors may leave a partial destination, which must not be reported as success. History stores parameters only; cross-session full undo history remains optional. Actual Windows execution and cross-decoder numerical comparisons are deferred.
