// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.image

/** Packed, opaque sRGB ARGB pixels. The supplied buffer is modified in place. */
object NativeTone {
    init { System.loadLibrary("nijiframe_pixels") }
    external fun apply(pixels: IntArray, count: Int, exposureEv: Float, saturation: Float)
}
