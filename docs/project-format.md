# Editable project format v1

Status: implemented experimental format, not a stable interchange guarantee. Extension: `.nijiproj`. This formal format is independent of research prototypes.

## Container

ZIP containing exactly two regular files, without a ZIP comment, encryption, ZIP64 or multiple disks:

- `project.json`: UTF-8 JSON, at most 16 KiB.
- `assets/original`: unchanged encoded JPEG or PNG, at most 32 MiB.

The complete ZIP is limited to 33 MiB. Reading validates its central directory, local paths, payload CRCs, exact entry set and SHA-256. Duplicate paths, special-file modes, traversal, incomplete archives, missing resources and unexpected entries are rejected. Nothing is extracted to filesystem paths from the package.

Example manifest (the digest is a placeholder):

```json
{
  "version": 1,
  "pipeline": "srgb-tone-crop-turn-v1",
  "source": "assets/original",
  "sourceBytes": 12345,
  "sourceSha256": "<64 lowercase hexadecimal characters>",
  "width": 1600,
  "height": 1200,
  "edit": {
    "exposureEv": 0.5,
    "saturation": 1.1,
    "turns": 1,
    "crop": {"x": 200, "y": 0, "width": 1200, "height": 1200}
  }
}
```

Dimensions describe the image **after EXIF orientation**, at most 8,000,000 pixels. They must match the decoded resource. Unknown fields, versions and pipeline identifiers fail instead of being silently ignored. No platform URI, device identifier or absolute path is stored.

## Pipeline semantics

1. Decode opaque 8-bit sRGB SDR source. Normalize all eight EXIF orientations once; retain the encoded original unmodified. Wide-gamut input, transparent pixels, non-8-bit PNG and detected HDR gain maps are rejected. Android decoder behavior on additional formats/devices is not a compatibility promise.
2. Convert each sRGB channel `s` in [0,1] to linear light: `s/12.92` when `s <= 0.04045`, otherwise `((s+0.055)/1.055)^2.4`.
3. Multiply linear RGB by `2^exposureEv`, where EV is [-3,3]. Compute `Y = 0.2126 R + 0.7152 G + 0.0722 B`. Saturation in [0,2] maps each channel to `Y + saturation * (channel-Y)`.
4. Clip to [0,1], apply the inverse sRGB transfer function, round to the nearest 8-bit value. Neutral tone is bit-exact on decoded pixels.
5. Crop a half-open integer rectangle `[x,y,width,height]` in the source-oriented coordinates; `null` means full image. Then rotate clockwise by `turns * 90°`, where turns is 0..3. Pointwise tone may be evaluated only inside the crop because it commutes with this crop.

The UI currently exposes a centered square crop; the format and renderer accept any validated integer rectangle. No masks, portrait effects or required models exist in v1. Adding these requires a documented version/compatibility decision.

The full-resolution render is authoritative. Preview is reduced from that render to a maximum side of 1600 pixels. Photo export separately encodes full output dimensions as JPEG quality 95. Export intentionally does not transfer original metadata; the project still contains the original metadata in its embedded source.

## Save and recovery

One current project is stored privately by the Android app. Save writes a same-directory temporary file, finishes the ZIP, syncs file contents and atomically replaces the destination. Failed writes leave the previous complete file; leftover temporary files are not used for recovery. This provides process-interruption recovery, not a guarantee against every filesystem or device power-loss failure.

Startup restores the last successful save. Editing or importing without saving does not promise crash recovery. Undo/redo history is in-memory, contains only parameters, and is not persisted; restored parameters remain editable. Saving a new project replaces the single recovery slot, so use portable exports to retain multiple projects.

System document providers do not promise atomic replacement. Both export actions first commit an internal recoverable project and then write the newly chosen document. A failed provider write can leave a partial destination; the UI reports failure and the saved internal project remains available for retry. The original external photo is never overwritten by application code.

Windows compatibility and format stabilization require independent runtime checks. A Linux JVM or C++ build is not evidence of Windows handoff.
