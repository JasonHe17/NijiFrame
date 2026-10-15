// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import kotlinx.serialization.Serializable

@Serializable
data class Crop(val x: Int, val y: Int, val width: Int, val height: Int)

@Serializable
data class Edit(val exposureEv: Float = 0f, val saturation: Float = 1f,
                val turns: Int = 0, val crop: Crop? = null,
                val contrast: Float = 0f, val highlights: Float = 0f, val shadows: Float = 0f,
                val whites: Float = 0f, val blacks: Float = 0f, val warmth: Float = 0f,
                val tint: Float = 0f, val vibrance: Float = 0f,
                val sharpen: Float = 0f, val vignette: Float = 0f,
                val flipHorizontal: Boolean = false, val flipVertical: Boolean = false,
                val straighten: Float = 0f, val local: LocalAdjustment = LocalAdjustment()) {
    fun validate(width: Int, height: Int, sourceCrop: Boolean = false) {
        local.validate();local.region.validateWork(width,height)
        require(exposureEv.isFinite() && exposureEv in -3f..3f) { "Invalid exposure" }
        require(saturation.isFinite() && saturation in 0f..2f) { "Invalid saturation" }
        require(listOf(contrast, highlights, shadows, whites, blacks, warmth, tint, vibrance, vignette)
            .all { it.isFinite() && it in -1f..1f }) { "Invalid adjustment" }
        require(sharpen.isFinite() && sharpen in 0f..2f) { "Invalid sharpening" }
        require(straighten.isFinite() && straighten in -45f..45f) { "Invalid straighten angle" }
        require(turns in 0..3) { "Invalid rotation" }
        val cropWidth = if (!sourceCrop && turns % 2 == 1) height else width
        val cropHeight = if (!sourceCrop && turns % 2 == 1) width else height
        crop?.let {
            require(it.x >= 0 && it.y >= 0 && it.width > 0 && it.height > 0 &&
                it.x.toLong() + it.width <= cropWidth && it.y.toLong() + it.height <= cropHeight) { "Invalid crop" }
        }
    }
}

@Serializable
data class Manifest(
    val version: Int = 4,
    val pipeline: String = "srgb-soften-v4",
    val source: String = "assets/original",
    val sourceBytes: Int,
    val sourceSha256: String,
    val width: Int,
    val height: Int,
    val edit: Edit = Edit(),
    val name: String = "Untitled",
    val localMask: MaskResource? = null
) {
    fun validate() {
        require((version == 1 && pipeline == "srgb-tone-crop-turn-v1") ||
            (version == 2 && pipeline == "srgb-basic-v2") ||
            (version == 3 && pipeline == "srgb-local-v3") ||
            (version == 4 && pipeline == "srgb-soften-v4")) { "Unsupported project version or operation" }
        if(version < 4) require(edit.local.soften == 0f && edit.local.detailProtection == .75f) { "Legacy project cannot contain softening" }
        if(version < 3) require(edit.local == LocalAdjustment() && localMask == null) { "Legacy project cannot contain local adjustments" }
        localMask?.let { require(it.path == "assets/local-mask.json" && it.bytes in 1..ProjectIO.MAX_MASK_BYTES &&
            it.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid mask resource" } }
        require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) { "Invalid project name" }
        if (version == 1) require(edit == Edit(edit.exposureEv, edit.saturation, edit.turns, edit.crop)) {
            "Version 1 cannot contain new operations"
        }
        require(source == "assets/original" && sourceBytes in 1..ProjectIO.MAX_SOURCE_BYTES) { "Invalid resource" }
        require(sourceSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid resource digest" }
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS) { "Unsupported image dimensions" }
        edit.validate(width, height, sourceCrop = version == 1)
    }
    fun current(): Manifest = copy(version = 4, pipeline = "srgb-soften-v4",
        edit = if(version == 1) edit.copy(crop = edit.crop?.let { Geometry.legacyCrop(it,width,height,edit.turns) }) else edit)
    companion object { const val MAX_PIXELS = 8_000_000L }
}

data class Project(val manifest: Manifest, val original: ByteArray) {
    fun withEdit(edit: Edit): Project = copy(manifest = manifest.copy(version = 4, pipeline = "srgb-soften-v4", edit = edit, localMask = null))
}

/** History contains parameters and immutable region descriptions, never rendered bitmaps. A completed gesture contributes one entry. */
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
