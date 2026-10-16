# Independent local regions and pipeline v7

Each region stores a stable identifier, a name, an effect switch, source-coordinate
coverage and exposure, relative warmth/tint, saturation, softening and protection.
Regions compose in creation order; selection never reorders them or consumes an
edit undo step. Empty or disabled regions leave pixels unchanged. The cap is four
regions: a local synthetic 1/4/8-region comparison found that eight approximately
doubled four-region processing time, while four covers a three-region workflow
with one spare slot. These are emulator observations, not physical-phone budgets.

The processing order is source softening, global color, then each region's local
color, followed by geometry, sharpening and vignette. Each softening candidate
reads the immutable original neighborhood and blends into the accumulated pixel;
it never filters another region's result. Thus the detail window needs one
softening halo even where regions overlap. Local color blends in linear sRGB
before clamping; relative temperature is not a Kelvin measurement. Later regions
see the previous region's color output. Overlap can compound color adjustments.

Legacy `local` remains the single-region representation for v1–v6 migration.
Adding a second region promotes it to the first named region without changing
its parameters, stroke rasterization, or rendering order. v7 introduces a region
list and versioned mask resource payload. Older readers reject v7. The original
source bytes and EXIF color interpretation remain unchanged.

Legacy strokes retain their bounded 1024-edge raster. New precise corrections
are appended separately and sampled at source pixel centers, after that legacy
coverage. Their order is add/erase order and their coordinates remain normalized
to the untransformed original. Requested windows are rasterized with bounded
scratch storage instead of allocating a full-resolution image mask. Fine marks
may be invisible in a small overview; the original-resolution detail view and
export use the same coverage. Work, point, resource and region limits reject an
edit before replacing the last valid project.

The existing bilateral softening candidate remains experimental. Evaluation
separates coverage errors, texture/edge damage and subjective preference. Automated
measurements cannot supply an aesthetic blind-review result. Real evaluation
photographs and reports stay outside the public repository; public regression
tests use generated color, texture and edge fixtures.

The export admission budget includes additional live coverage grids and strips
for enabled regions. Per-region work caps also apply to disabled saved data, so
hiding a region does not make an invalid archive acceptable. Selection and naming
are part of the draft and committed archive; cancel discards the whole session.

The implementation continues to use explicit coverage and parameters. It does
not add edge-snapped brush input, a pre-applied whole-image multi-effect brush
or removal of effects outside a detected brush boundary. These are relevant
implementation differences for the previously reviewed US8692847B2/US8687015B2
claims, not a patent-clearance conclusion. The original bilateral filter,
landmark model and geometric suggestions are retained; the existing comparison
in [portrait-region patents](portrait-region-patents.md) still applies. GUI,
complete related claims/families and official status checks remain incomplete.
