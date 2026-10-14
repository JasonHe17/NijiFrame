// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import kotlinx.serialization.Serializable

@Serializable
data class Crop(val x: Int, val y: Int, val width: Int, val height: Int)

@Serializable
data class Edit(val exposureEv: Float = 0f, val saturation: Float = 1f,
                val turns: Int = 0, val crop: Crop? = null) {
    fun validate(width: Int, height: Int) {
        require(exposureEv.isFinite() && exposureEv in -3f..3f) { "Invalid exposure" }
        require(saturation.isFinite() && saturation in 0f..2f) { "Invalid saturation" }
        require(turns in 0..3) { "Invalid rotation" }
        crop?.let {
            require(it.x >= 0 && it.y >= 0 && it.width > 0 && it.height > 0 &&
                it.x.toLong() + it.width <= width && it.y.toLong() + it.height <= height) { "Invalid crop" }
        }
    }
}

@Serializable
data class Manifest(
    val version: Int = 1,
    val pipeline: String = "srgb-tone-crop-turn-v1",
    val source: String = "assets/original",
    val sourceBytes: Int,
    val sourceSha256: String,
    val width: Int,
    val height: Int,
    val edit: Edit = Edit()
) {
    fun validate() {
        require(version == 1 && pipeline == "srgb-tone-crop-turn-v1") { "Unsupported project version or operation" }
        require(source == "assets/original" && sourceBytes in 1..ProjectIO.MAX_SOURCE_BYTES) { "Invalid resource" }
        require(sourceSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid resource digest" }
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS) { "Unsupported image dimensions" }
        edit.validate(width, height)
    }
    companion object { const val MAX_PIXELS = 8_000_000L }
}

data class Project(val manifest: Manifest, val original: ByteArray)

/** History contains parameters only. A completed gesture contributes one entry. */
class History(initial: Edit = Edit()) {
    var current: Edit = initial
        private set
    private val past = ArrayDeque<Edit>()
    private val future = ArrayDeque<Edit>()
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun commit(edit: Edit) {
        if (edit == current) return
        past.addLast(current)
        if (past.size > 100) past.removeFirst()
        current = edit
        future.clear()
    }
    fun undo(): Edit { if (canUndo) { future.addLast(current); current = past.removeLast() }; return current }
    fun redo(): Edit { if (canRedo) { past.addLast(current); current = future.removeLast() }; return current }
}
