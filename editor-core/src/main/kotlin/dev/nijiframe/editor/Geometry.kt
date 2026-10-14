// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import kotlin.math.*

object Geometry {
    /** Scale a center rotation enough that every destination pixel remains inside the source. */
    fun straightenScale(width: Int, height: Int, degrees: Float): Float {
        require(width > 0 && height > 0 && degrees.isFinite() && degrees in -45f..45f)
        val radians = Math.toRadians(abs(degrees).toDouble())
        val c = cos(radians); val s = sin(radians)
        return max(c + s * height / width, c + s * width / height).toFloat()
    }

    fun centeredCrop(width: Int, height: Int, aspect: Float?): Crop {
        require(width > 0 && height > 0 && (aspect == null || aspect.isFinite() && aspect > 0))
        if (aspect == null) return Crop(0, 0, width, height)
        val w = min(width, (height * aspect).roundToInt().coerceAtLeast(1))
        val h = min(height, (w / aspect).roundToInt().coerceAtLeast(1))
        return Crop((width-w)/2, (height-h)/2, w, h)
    }

    fun canvasSize(width: Int, height: Int, turns: Int): Pair<Int, Int> =
        if (turns % 2 == 0) width to height else height to width

    fun outputSize(width: Int, height: Int, edit: Edit): Pair<Int, Int> =
        edit.crop?.let { it.width to it.height } ?: canvasSize(width,height,edit.turns)

    fun legacyCrop(crop: Crop, width: Int, height: Int, turns: Int): Crop = when (turns) {
        0 -> crop
        1 -> Crop(height-crop.y-crop.height,crop.x,crop.height,crop.width)
        2 -> Crop(width-crop.x-crop.width,height-crop.y-crop.height,crop.width,crop.height)
        3 -> Crop(crop.y,width-crop.x-crop.width,crop.height,crop.width)
        else -> error("Invalid rotation")
    }

    fun rotate(edit: Edit, width: Int, height: Int): Edit {
        val (w,h)=canvasSize(width,height,edit.turns)
        return edit.copy(turns=(edit.turns+1)%4,crop=edit.crop?.let { legacyCrop(it,w,h,1) })
    }

    fun flip(edit: Edit, width: Int, height: Int, horizontal: Boolean): Edit {
        val (w,h)=canvasSize(width,height,edit.turns)
        val sourceHorizontal = horizontal == (edit.turns % 2 == 0)
        return edit.copy(
            flipHorizontal = if (sourceHorizontal) !edit.flipHorizontal else edit.flipHorizontal,
            flipVertical = if (!sourceHorizontal) !edit.flipVertical else edit.flipVertical,
            crop = edit.crop?.let { if (horizontal) it.copy(x=w-it.x-it.width) else it.copy(y=h-it.y-it.height) }
        )
    }

}

enum class PhotoFormat(val mime: String, val extension: String) { JPEG("image/jpeg", "jpg"), PNG("image/png", "png") }

data class ExportSettings(val format: PhotoFormat = PhotoFormat.JPEG, val quality: Int = 95, val maxEdge: Int? = null) {
    fun dimensions(width: Int, height: Int): Pair<Int, Int> {
        require(quality in 1..100 && width > 0 && height > 0)
        require(maxEdge == null || maxEdge in 64..16384)
        val scale = min(1.0, (maxEdge ?: max(width, height)).toDouble()/max(width,height))
        return max(1, (width*scale).roundToInt()) to max(1, (height*scale).roundToInt())
    }
}

enum class Look(val label: String) {
    NEUTRAL("原色"), NATURAL("自然"), WARM("暖调"), COOL("冷调"), MONO("黑白");
    /** Looks replace global tone/detail settings, while preserving the user's geometry. */
    fun apply(edit: Edit): Edit {
        val base = Edit(turns = edit.turns, crop = edit.crop, flipHorizontal = edit.flipHorizontal,
            flipVertical = edit.flipVertical, straighten = edit.straighten)
        return when (this) {
            NEUTRAL -> base
            NATURAL -> base.copy(contrast = 0.08f, shadows = 0.08f, vibrance = 0.18f, sharpen = 0.3f)
            WARM -> base.copy(warmth = 0.3f, contrast = 0.08f, saturation = 0.95f)
            COOL -> base.copy(warmth = -0.25f, shadows = 0.1f, saturation = 0.9f)
            MONO -> base.copy(saturation = 0f, contrast = 0.15f)
        }
    }
}


enum class CropHandle { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

object CropGesture {
    fun drag(start: Crop, dx: Float, dy: Float, width: Int, height: Int, handle: CropHandle, aspect: Float?): Crop {
        require(dx.isFinite() && dy.isFinite() && width>0 && height>0)
        if (handle==CropHandle.MOVE) return start.copy(
            x=(start.x+dx).roundToInt().coerceIn(0,width-start.width),
            y=(start.y+dy).roundToInt().coerceIn(0,height-start.height))
        val right=handle==CropHandle.TOP_RIGHT || handle==CropHandle.BOTTOM_RIGHT
        val bottom=handle==CropHandle.BOTTOM_LEFT || handle==CropHandle.BOTTOM_RIGHT
        val ax=if(right) start.x else start.x+start.width
        val ay=if(bottom) start.y else start.y+start.height
        val maxW=if(right) width-ax else ax
        val maxH=if(bottom) height-ay else ay
        val deltaW=dx*(if(right) 1 else -1)
        val deltaH=dy*(if(bottom) 1 else -1)
        var w=(start.width+deltaW).coerceIn(1f,maxW.toFloat())
        var h=(start.height+deltaH).coerceIn(1f,maxH.toFloat())
        if(aspect!=null) {
            require(aspect.isFinite() && aspect>0)
            val desired=if(abs(deltaW)>=abs(deltaH)*aspect) start.width+deltaW else (start.height+deltaH)*aspect
            val maximum=min(maxW.toFloat(),maxH*aspect)
            w=desired.coerceIn(min(1f,maximum),maximum);h=w/aspect
        }
        val iw=w.roundToInt().coerceIn(1,maxW);val ih=h.roundToInt().coerceIn(1,maxH)
        return Crop(if(right) ax else ax-iw,if(bottom) ay else ay-ih,iw,ih)
    }
}
