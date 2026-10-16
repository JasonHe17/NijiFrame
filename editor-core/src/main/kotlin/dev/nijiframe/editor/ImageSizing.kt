// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

/** Resource planning only; the project always retains original-resolution coordinates. */
object ImageSizing {
    const val REFERENCE_PREVIEW_PIXELS = 8_000_000L
    const val PROXY_EDGE = 2048

    fun previewSample(width: Int, height: Int): Int {
        if (width.toLong() * height <= REFERENCE_PREVIEW_PIXELS && maxOf(width, height) <= 8192) return 1
        var sample = 1
        while (maxOf(ceilDiv(width, sample), ceilDiv(height, sample)) > PROXY_EDGE) sample *= 2
        return sample
    }

    /** Small exports of large sources decode enough pixels for the requested crop, never the preview. */
    fun exportSample(manifest: Manifest, settings: ExportSettings): Int {
        if (previewSample(manifest.width, manifest.height) == 1 || settings.maxEdge == null) return 1
        val (w, h) = Geometry.outputSize(manifest.width, manifest.height, manifest.edit)
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= settings.maxEdge) sample *= 2
        return sample
    }

    fun ceilDiv(value: Int, divisor: Int): Int = ((value.toLong() + divisor - 1) / divisor).toInt()

    /** Conservative peak of simultaneously live decode/render/transform buffers, plus encoder scratch. */
    fun exportWorkingBytes(manifest: Manifest, sample: Int, sourceAlreadyLoaded: Boolean, resizing: Boolean = false): Long {
        val pixels = ceilDiv(manifest.width, sample).toLong() * ceilDiv(manifest.height, sample)
        val e = manifest.edit
        val extra = if (e.straighten != 0f || e.turns != 0 || e.flipHorizontal || e.flipVertical ||
            e.crop != null || e.sharpen != 0f) 1 else 0
        // Even without a transform, resizing/encoding can overlap the output with another bitmap.
        val buffers = (if (sourceAlreadyLoaded) 0 else 1) + 1 + maxOf(extra, if (resizing) 1 else 0)
        val localCount=e.localRegions.count {it.enabled && it.adjustment.hasEffect && !it.adjustment.region.isEmpty}
        // The original scratch allowance covers one mask. Additional regions retain
        // a 1024-edge coverage grid and one source-width strip simultaneously.
        val extraMasks=maxOf(0,localCount-1).toLong()*(1024L*1024+ceilDiv(manifest.width,sample).toLong()*32)
        return pixels * 4 * buffers + 24L * 1024 * 1024 + extraMasks
    }
}
