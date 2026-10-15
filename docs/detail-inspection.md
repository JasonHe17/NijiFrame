# Original-resolution detail inspection

0.6.0-dev adds **查看细节** on the canvas and **原尺寸细节** in the editor menu. The main large-image preview remains reduced; this separate view reads an original-resolution region so skin texture, softening and sharpening can be inspected without exporting the full image.

Tap the overview to choose a position. A white rectangle marks the window. The lower image uses one image pixel per screen pixel, up to 768 × 768 pixels; smaller images are not enlarged to fill the window. **查看原图／查看调整** compares effects while retaining the current geometry. Close or press Back to return. Inspection is read-only, including when a tool draft is pending: it does not apply, cancel, save or add an undo step. While editing a crop, the overview follows the uncropped canvas; apply the crop to inspect the cropped result.

## Rendering boundary

The renderer maps the output window back through crop, quarter-turn, flips and straightening, adds neighborhood halos, then decodes a bounded original region with `BitmapRegionDecoder`. EXIF orientation and source-coordinate color processing share the full-render helpers. The local mask and softening radius use original dimensions. JPEG also receives a 16-pixel decoding margin to avoid region-edge chroma differences. Sharpening receives neighboring output pixels; vignette uses the coordinates and dimensions of the complete cropped output.

No full-resolution source bitmap is created for inspection. Requested output windows are capped at 768 per edge and decoded regions at two million pixels. This bounds application pixel buffers, not every internal allocation of a platform decoder. Admission uses the encoded-source size plus conservative working headroom; memory refusal leaves the project intact. Reading a region is synchronous platform work; cancellation is checked around it and during color strips. A cancelled or obsolete frame cannot replace the selected view.

Without straightening, regression fixtures require region pixels to equal the corresponding full-render pixels, including eight JPEG EXIF orientations. Straightened fixtures allow limited Canvas interpolation differences: at most 6 per 8-bit channel and mean error at most 0.25 over RGBA samples for the tested combinations. This is a test tolerance, not a proof for every image, device and codec. The rendered output is still authoritative; JPEG encoding and explicitly reduced exports can also alter detail. The inspector does not change the existing original-size export memory limit.

## Evidence and sources

`DetailRendererTest` compares color, masks, geometry and image boundaries against full renders and reads original texture from a generated 100 MP PNG without a full bitmap. `DetailInspectorTest` checks actual screen pixels at 1:1, position changes, comparison, recreation, Back and preservation of pending drafts and saved originals. Native tests compare a vignette window against a whole image and reject out-of-bounds windows.

Platform behavior is documented in [BitmapRegionDecoder](https://developer.android.com/reference/android/graphics/BitmapRegionDecoder) and [Compose image scaling](https://developer.android.com/develop/ui/compose/graphics/images/customize). No runtime dependency, model or external image asset is added. Region decoding, display behavior and patent applicability require separate assessments; these references do not establish patent clearance.
