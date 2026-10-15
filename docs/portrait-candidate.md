# Experimental portrait softening

0.4.0-dev adds a default-off, manually selected softening candidate. It is available for visual review, not a claim of finished portrait quality, automatic skin recognition or physical-phone performance.

## What it does

The local region controls both exposure and softening. A bounded bilateral neighborhood reduces small encoded-RGB variations; a separate detail-protection value reduces blending around local contrast. No model, network request, automatic face reshaping or whitening is involved. The user must select skin and exclude eyes, lips, hair, glasses and other content that should remain untouched. An empty region or zero strength leaves pixels unchanged at this stage.

The precise radius, weights, blending, rounding and order are part of the [v4 processing contract](project-format.md). A source strip contains the halo needed by neighboring pixels; cancellation occurs between strips. The old global/local-exposure pipelines retain their output when softening is zero. V1/v2/v3 projects migrate with softening disabled; v4 persists both controls and the source-coordinate region.

## Review before broader use

Compare the original, low and medium strength, then inspect pores and high-contrast details at native scale. Higher strength and lower protection can remove texture; the protection control is not semantic understanding and cannot guarantee preservation of every facial detail. The fast preview is approximate, so wait for the exact result before evaluating quality.

Research comparisons use separately licensed images outside this repository. Automatic-region failures remain failures; unchanged output on an empty mask is not counted as a successful retouch. New low-light and user-owned examples are still useful for evaluating this candidate, but user photographs must not be committed to the repository.

`SofteningProjectTest`, `SofteningTest` and native invariants check compatibility, neutral/masked identity, strip equivalence, persistence and output pixels; the local real-UI flow also exercises the softening control. These checks establish engineering behavior, not aesthetic acceptance. Related patent claims require separate implementation review; an independent implementation does not establish clearance.

A daily-use phone should be used only for short ordinary editing trials with copied inputs. Run malformed-input, stress and automated reinstall tests on dedicated environments. See [manual checks](manual-check.md).
