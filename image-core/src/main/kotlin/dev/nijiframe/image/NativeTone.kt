// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.image

/** Packed, opaque sRGB ARGB pixels. The supplied buffer is modified in place. */
object NativeTone {
    init { System.loadLibrary("nijiframe_pixels") }
    external fun apply(pixels: IntArray, count: Int, exposureEv: Float, saturation: Float)
    /** EV, saturation, contrast, highlights, shadows, whites, blacks, warmth, tint, vibrance. */
    external fun applyBasic(pixels: IntArray, count: Int, parameters: FloatArray)
    external fun sharpen(source: IntArray, destination: IntArray, width: Int, height: Int, amount: Float)
    external fun vignette(pixels: IntArray, width: Int, fullHeight: Int, firstRow: Int, rows: Int, amount: Float)
}
