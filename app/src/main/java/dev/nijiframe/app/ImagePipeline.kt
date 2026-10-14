// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
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
        val crop = edit.crop
        val width = crop?.width ?: source.width
        val height = crop?.height ?: source.height
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            // Pointwise tone commutes with crop. Bounded strips make cancellation cooperative.
            val buffer = IntArray(width * minOf(32, height))
            for (y in 0 until height step 32) {
                currentCoroutineContext().ensureActive()
                val rows = minOf(32, height - y)
                source.getPixels(buffer, 0, width, crop?.x ?: 0, (crop?.y ?: 0) + y, width, rows)
                NativeTone.apply(buffer, width * rows, edit.exposureEv, edit.saturation)
                output.setPixels(buffer, 0, width, 0, y, width, rows)
            }
            currentCoroutineContext().ensureActive()
            if (edit.turns == 0) return output
            val rotated = Bitmap.createBitmap(output, 0, 0, width, height,
                Matrix().apply { setRotate(edit.turns * 90f) }, false)
            if (rotated !== output) output.recycle()
            return rotated
        } catch (error: Throwable) { output.recycle(); throw error }
    }

    fun preview(full: Bitmap): Bitmap {
        val scale = minOf(1f, 1600f / maxOf(full.width, full.height))
        if (scale == 1f) return full
        return Bitmap.createScaledBitmap(full, maxOf(1, (full.width * scale).toInt()),
            maxOf(1, (full.height * scale).toInt()), true).also { full.recycle() }
    }
}
