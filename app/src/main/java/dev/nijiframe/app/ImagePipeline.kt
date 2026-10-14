// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Paint
import dev.nijiframe.editor.Geometry
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import dev.nijiframe.editor.Edit
import dev.nijiframe.editor.Manifest
import dev.nijiframe.image.NativeTone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object ImagePipeline {
    fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType in setOf("image/jpeg", "image/png")) { "目前仅支持 JPEG 和不透明 PNG" }
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight <= Manifest.MAX_PIXELS) { "图片超过当前 800 万像素限制" }
        require(bounds.outColorSpace == null || bounds.outColorSpace == ColorSpace.get(ColorSpace.Named.SRGB)) {
            "目前仅支持 sRGB 图片，请先转换色彩空间"
        }
        if (bounds.outMimeType == "image/png") {
            require(bytes.size > 25 && bytes[24].toInt() == 8) { "目前仅支持 8 位 PNG" }
        }
        val exif = ExifInterface(bytes.inputStream())
        val xmp = exif.getAttribute(ExifInterface.TAG_XMP).orEmpty()
        require(!xmp.contains("hdrgm:", ignoreCase = true) && !xmp.contains("hdrgainmap", ignoreCase = true)) {
            "目前不支持 HDR 增益图，请先导出 SDR 图片"
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888;
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB) })) { "无法解码图片" }
        try {
            require(Build.VERSION.SDK_INT < 34 || !bitmap.hasGainmap()) { "目前不支持 HDR 图片" }
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                require(row.all { (it ushr 24) == 255 }) { "目前不支持含透明像素的图片" }
            }
            val transform = Matrix()
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                2 -> transform.setScale(-1f, 1f)
                3 -> transform.setRotate(180f)
                4 -> transform.setScale(1f, -1f)
                5 -> { transform.setRotate(90f); transform.postScale(-1f, 1f) }
                6 -> transform.setRotate(90f)
                7 -> { transform.setRotate(90f); transform.postScale(1f, -1f) }
                8 -> transform.setRotate(270f)
            }
            val normalized = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, false)
            if (normalized !== bitmap) bitmap.recycle()
            return normalized
        } catch (error: Exception) { bitmap.recycle(); throw error }
    }

    suspend fun render(source: Bitmap, edit: Edit): Bitmap {
        edit.validate(source.width, source.height)
        val width = source.width
        val height = source.height
        var output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            // Bounded strips keep global tone processing cancellable without a full pixel-array copy.
            val parameters = floatArrayOf(edit.exposureEv, edit.saturation, edit.contrast, edit.highlights,
                edit.shadows, edit.whites, edit.blacks, edit.warmth, edit.tint, edit.vibrance)
            val buffer = IntArray(width * minOf(32, height))
            for (y in 0 until height step 32) {
                currentCoroutineContext().ensureActive()
                val rows = minOf(32, height - y)
                source.getPixels(buffer, 0, width, 0, y, width, rows)
                NativeTone.applyBasic(buffer, width * rows, parameters)
                output.setPixels(buffer, 0, width, 0, y, width, rows)
            }
            currentCoroutineContext().ensureActive()
            if (edit.straighten != 0f) {
                val straight = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val scale = Geometry.straightenScale(width, height, edit.straighten)
                val matrix = Matrix().apply {
                    postTranslate(-width/2f, -height/2f)
                    postRotate(edit.straighten)
                    postScale(scale, scale)
                    postTranslate(width/2f, height/2f)
                }
                Canvas(straight).drawBitmap(output, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                output.recycle(); output = straight
            }
            if (edit.flipHorizontal || edit.flipVertical || edit.turns != 0) {
                val matrix = Matrix().apply {
                    setScale(if (edit.flipHorizontal) -1f else 1f, if (edit.flipVertical) -1f else 1f)
                    postRotate(edit.turns * 90f)
                }
                val transformed = Bitmap.createBitmap(output, 0, 0, width, height, matrix, false)
                if (transformed !== output) { output.recycle(); output = transformed }
            }
            edit.crop?.let { crop ->
                val cropped = Bitmap.createBitmap(output,crop.x,crop.y,crop.width,crop.height)
                if (cropped !== output) { output.recycle(); output = cropped }
            }
            if (edit.sharpen != 0f) {
                val sharp = Bitmap.createBitmap(output.width, output.height, Bitmap.Config.ARGB_8888)
                try {
                    val w = output.width
                    val input = IntArray(w * minOf(34, output.height))
                    val destination = IntArray(input.size)
                    for (y in 0 until output.height step 32) {
                        currentCoroutineContext().ensureActive()
                        val first = maxOf(0, y-1)
                        val last = minOf(output.height, y+33)
                        val rows = minOf(32, output.height-y)
                        output.getPixels(input, 0, w, 0, first, w, last-first)
                        NativeTone.sharpen(input, destination, w, last-first, edit.sharpen)
                        sharp.setPixels(destination, (y-first)*w, w, 0, y, w, rows)
                    }
                } catch (error: Throwable) { sharp.recycle(); throw error }
                output.recycle(); output = sharp
            }
            if (edit.vignette != 0f) {
                val w = output.width
                val strip = IntArray(w * minOf(32, output.height))
                for (y in 0 until output.height step 32) {
                    currentCoroutineContext().ensureActive()
                    val rows = minOf(32, output.height-y)
                    output.getPixels(strip, 0, w, 0, y, w, rows)
                    NativeTone.vignette(strip, w, output.height, y, rows, edit.vignette)
                    output.setPixels(strip, 0, w, 0, y, w, rows)
                }
            }
            return output
        } catch (error: Throwable) { output.recycle(); throw error }
    }

    fun preview(full: Bitmap): Bitmap {
        val scale = minOf(1f, 1600f / maxOf(full.width, full.height))
        if (scale == 1f) return full
        return Bitmap.createScaledBitmap(full, maxOf(1, (full.width * scale).toInt()),
            maxOf(1, (full.height * scale).toInt()), true).also { full.recycle() }
    }
}
