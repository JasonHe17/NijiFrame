// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Paint
import dev.nijiframe.editor.*
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import dev.nijiframe.editor.Edit
import dev.nijiframe.editor.Manifest
import dev.nijiframe.image.NativeTone
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

object ImagePipeline {
    data class ImageInfo(val encodedWidth: Int, val encodedHeight: Int, val orientation: Int, val mime: String) {
        val width get() = if (orientation in 5..8) encodedHeight else encodedWidth
        val height get() = if (orientation in 5..8) encodedWidth else encodedHeight
    }

    fun inspect(bytes: ByteArray): ImageInfo {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType in setOf("image/jpeg", "image/png")) { "目前仅支持 JPEG 和不透明 PNG" }
        require(bounds.outWidth in 1..Manifest.MAX_EDGE && bounds.outHeight in 1..Manifest.MAX_EDGE &&
            bounds.outWidth.toLong() * bounds.outHeight <= Manifest.MAX_PIXELS) {
            "图片超过当前 1 亿像素或单边 32768 像素限制"
        }
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
        return ImageInfo(bounds.outWidth, bounds.outHeight,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL), bounds.outMimeType!!)
    }

    fun decode(bytes: ByteArray, sample: Int = 1, info: ImageInfo = inspect(bytes), check: () -> Unit = {}): Bitmap {
        require(sample > 0 && sample and (sample - 1) == 0)
        check()
        // Subsampling can hide an isolated transparent pixel. Validate the original PNG in bounded regions.
        if (sample > 1 && info.mime == "image/png" && pngMayHaveAlpha(bytes)) validateOpaquePng(bytes, info, check)
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888;
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB) })) { "无法解码图片" }
        try {
            require(Build.VERSION.SDK_INT < 34 || !bitmap.hasGainmap()) { "目前不支持 HDR 图片" }
            val row = IntArray(bitmap.width)
            for (y in 0 until if (info.mime == "image/png" && sample == 1) bitmap.height else 0) {
                check()
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                require(row.all { (it ushr 24) == 255 }) { "目前不支持含透明像素的图片" }
            }
            val transform = orientationMatrix(info.orientation)
            val normalized = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, false)
            if (normalized !== bitmap) bitmap.recycle()
            return normalized
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }

    internal fun orientationMatrix(orientation: Int)=Matrix().apply {
        when(orientation) {
            2 -> setScale(-1f,1f)
            3 -> setRotate(180f)
            4 -> setScale(1f,-1f)
            5 -> {setRotate(90f);postScale(-1f,1f)}
            6 -> setRotate(90f)
            7 -> {setRotate(90f);postScale(1f,-1f)}
            8 -> setRotate(270f)
        }
    }

    private fun pngMayHaveAlpha(bytes: ByteArray): Boolean {
        if (bytes.size <= 25 || bytes[25].toInt() in setOf(4, 6)) return true
        var offset = 8
        while (offset.toLong() + 12 <= bytes.size) {
            val length = (0..3).fold(0L) { n, i -> (n shl 8) or (bytes[offset+i].toLong() and 255) }
            if (offset.toLong() + length + 12 > bytes.size) return true
            val type = String(bytes, offset+4, 4, Charsets.US_ASCII)
            if (type == "tRNS") return true
            if (type == "IDAT") return false
            offset += length.toInt() + 12
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun validateOpaquePng(bytes: ByteArray, info: ImageInfo, check: () -> Unit) {
        val decoder = requireNotNull(BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)) { "无法解码图片" }
        try {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val row = IntArray(minOf(info.encodedWidth, 1024))
            for (y in 0 until info.encodedHeight step 256) for (x in 0 until info.encodedWidth step 1024) {
                check()
                val tile = requireNotNull(decoder.decodeRegion(Rect(x, y, minOf(x+1024, info.encodedWidth),
                    minOf(y+256, info.encodedHeight)), options)) { "无法解码图片" }
                try {
                    for (r in 0 until tile.height) {
                        tile.getPixels(row, 0, tile.width, 0, r, tile.width, 1)
                        require((0 until tile.width).all { row[it] ushr 24 == 255 }) { "目前不支持含透明像素的图片" }
                    }
                } finally { tile.recycle() }
            }
        } finally { decoder.recycle() }
    }

    suspend fun render(source: Bitmap, edit: Edit): Bitmap {
        edit.validate(source.width, source.height)
        val width = source.width
        val height = source.height
        var output = colorRegion(source, edit, width, height, 0, 0)
        try {
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

    /** Source-coordinate color stages; surrounding source pixels must be supplied for neighborhood halos. */
    internal suspend fun colorRegion(source: Bitmap, edit: Edit, fullWidth: Int, fullHeight: Int, left: Int, top: Int): Bitmap {
        val width=source.width; val height=source.height
        val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            // Bounded strips keep global tone processing cancellable without a full pixel-array copy.
            val parameters = floatArrayOf(edit.exposureEv, edit.saturation, edit.contrast, edit.highlights,
                edit.shadows, edit.whites, edit.blacks, edit.warmth, edit.tint, edit.vibrance)
            val context=currentCoroutineContext()
            val mask=if((edit.local.exposureEv!=0f || edit.local.soften!=0f) && !edit.local.region.isEmpty)
                MaskRaster.create(edit.local.region,fullWidth,fullHeight) {context.ensureActive()} else null
            val buffer = IntArray(width * minOf(32, height))
            val maskStrip=if(mask!=null) ByteArray(buffer.size) else null
            val radius=(minOf(fullWidth,fullHeight)/600f).roundToInt().coerceIn(1,6)
            val softenInput=if(mask!=null && edit.local.soften>0f) IntArray(width*minOf(32+2*radius,height)) else null
            for (y in 0 until height step 32) {
                currentCoroutineContext().ensureActive()
                val rows = minOf(32, height - y)
                if(mask!=null && maskStrip!=null) {
                    for(row in 0 until rows) for(x in 0 until width)
                        maskStrip[row*width+x]=mask.sample((left+x+.5f)/fullWidth,(top+y+row+.5f)/fullHeight).toByte()
                }
                if(softenInput!=null && maskStrip!=null) {
                    val first=maxOf(0,y-radius);val last=minOf(height,y+rows+radius)
                    source.getPixels(softenInput,0,width,0,first,width,last-first)
                    NativeTone.soften(softenInput,buffer,maskStrip,width,last-first,y-first,rows,radius,
                        edit.local.soften,edit.local.detailProtection)
                } else source.getPixels(buffer, 0, width, 0, y, width, rows)
                NativeTone.applyBasic(buffer, width * rows, parameters)
                if(maskStrip!=null) NativeTone.localExposure(buffer,maskStrip,width*rows,edit.local.exposureEv)
                output.setPixels(buffer, 0, width, 0, y, width, rows)
            }
            return output
        } catch(error: Throwable) { output.recycle(); throw error }
    }

    fun interactiveSource(source: Bitmap): Bitmap? {
        if(maxOf(source.width,source.height)<=1600) return null
        val scale=800f/maxOf(source.width,source.height)
        return Bitmap.createScaledBitmap(source,maxOf(1,(source.width*scale).toInt()),maxOf(1,(source.height*scale).toInt()),true)
    }
    fun scaledEdit(edit: Edit,width: Int,height: Int,scaledWidth: Int,scaledHeight: Int): Edit {
        val (w,h)=Geometry.canvasSize(width,height,edit.turns)
        val (sw,sh)=Geometry.canvasSize(scaledWidth,scaledHeight,edit.turns)
        val crop=edit.crop?.let {
            val x=(it.x.toLong()*sw/w).toInt().coerceIn(0,sw-1)
            val y=(it.y.toLong()*sh/h).toInt().coerceIn(0,sh-1)
            val right=((it.x.toLong()+it.width)*sw/w).toInt().coerceIn(x+1,sw)
            val bottom=((it.y.toLong()+it.height)*sh/h).toInt().coerceIn(y+1,sh)
            Crop(x,y,right-x,bottom-y)
        }
        return edit.copy(crop=crop)
    }

    suspend fun regionOverlay(width: Int,height: Int,edit: Edit): Bitmap {
        val context=currentCoroutineContext()
        val mask=MaskRaster.create(edit.local.region,width,height) {context.ensureActive()}
        val pixels=IntArray(mask.coverage.size) { ((mask.coverage[it].toInt() and 255)*110/255 shl 24) or 0x00ff4060 }
        val input=Bitmap.createBitmap(pixels,mask.width,mask.height,Bitmap.Config.ARGB_8888)
        val (ow,oh)=Geometry.outputSize(width,height,edit)
        val scale=minOf(1f,1600f/maxOf(ow,oh))
        val output=Bitmap.createBitmap(maxOf(1,(ow*scale).toInt()),maxOf(1,(oh*scale).toInt()),Bitmap.Config.ARGB_8888)
        val mapping=SourceGeometry(width,height,edit)
        val points=listOf(MaskPoint(0f,0f),MaskPoint(1f,0f),MaskPoint(0f,1f)).map(mapping::toOutput)
        val target=floatArrayOf(points[0].x*output.width/ow,points[0].y*output.height/oh,
            points[1].x*output.width/ow,points[1].y*output.height/oh,points[2].x*output.width/ow,points[2].y*output.height/oh)
        val matrix=Matrix().apply {setPolyToPoly(floatArrayOf(0f,0f,mask.width.toFloat(),0f,0f,mask.height.toFloat()),0,target,0,3)}
        try {Canvas(output).drawBitmap(input,matrix,Paint(Paint.FILTER_BITMAP_FLAG))} finally {input.recycle()}
        return output
    }

    fun preview(full: Bitmap): Bitmap {
        val scale = minOf(1f, 1600f / maxOf(full.width, full.height))
        if (scale == 1f) return full
        return Bitmap.createScaledBitmap(full, maxOf(1, (full.width * scale).toInt()),
            maxOf(1, (full.height * scale).toInt()), true).also { full.recycle() }
    }
}
