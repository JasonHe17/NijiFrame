// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.*
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import dev.nijiframe.editor.Manifest
import java.nio.ByteBuffer

/** Source decoding policy for srgb-import-v6. Encoded originals are never rewritten. */
internal object PhotoInput {
    private val srgb=ColorSpace.get(ColorSpace.Named.SRGB)
    val mimeTypes=arrayOf("image/jpeg","image/png","image/webp","image/heif","image/heic","image/avif")
    fun options(sample: Int=1)=BitmapFactory.Options().apply {
        inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888;inPreferredColorSpace=srgb
    }
    fun inspect(bytes: ByteArray,honorExifColorSpace: Boolean): ImagePipeline.ImageInfo {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        val brand=String(bytes,0,minOf(64,bytes.size),Charsets.ISO_8859_1)
        val avif=brand.substring(4.coerceAtMost(brand.length)).startsWith("ftyp") && (brand.contains("avif") || brand.contains("avis"))
        val mime=if(avif) "image/avif" else bounds.outMimeType
        require(mime in mimeTypes) {"暂不支持此图片编码；可导入 JPEG、PNG、WebP、HEIC/HEIF 或 AVIF。RAW、TIFF 请先由相机软件导出"}
        require(mime!="image/avif" || Build.VERSION.SDK_INT>=31) {"AVIF 需要 Android 12 或更新系统及可用解码器，请改用 JPEG"}
        require(mime !in setOf("image/heif","image/heic") || Build.VERSION.SDK_INT>=28) {"HEIC/HEIF 需要 Android 9 或更新系统及可用解码器，请改用 JPEG"}
        var width=bounds.outWidth;var height=bounds.outHeight;var color=bounds.outColorSpace
        if(mime=="image/avif" && Build.VERSION.SDK_INT>=31) {
            val header=avifHeader(bytes)
            require(!header.animated) {"暂不支持动画 AVIF，请导出静态照片"}
            width=header.width;height=header.height;color=header.color
        }
        require(mime!="image/webp" || bytes.size<21 || String(bytes,12,4,Charsets.US_ASCII)!="VP8X" || bytes[20].toInt() and 2==0) {"暂不支持动画 WebP，请导出静态照片"}
        require(width>0 && height>0) {"此设备的解码器不支持这张照片，请尝试 JPEG 格式"}
        require(width<=Manifest.MAX_EDGE && height<=Manifest.MAX_EDGE && width.toLong()*height<=Manifest.MAX_PIXELS) {
            "图片超过当前 2.1 亿像素或单边 32768 像素限制"
        }
        require(!hasHdrTransfer(bytes,requireNotNull(mime)) && (Build.VERSION.SDK_INT<34 || (color!=ColorSpace.get(ColorSpace.Named.BT2020_HLG) && color!=ColorSpace.get(ColorSpace.Named.BT2020_PQ)))) {
            "这张照片是 HLG/PQ HDR 编码，尚不能可靠转换；请先由相册导出 SDR JPEG，普通广色域和 Ultra HDR JPEG 可直接导入"
        }
        val exif=ExifInterface(bytes.inputStream())
        val adobe=honorExifColorSpace && mime=="image/jpeg" && !hasJpegIcc(bytes) && (color==null || color==srgb) &&
            (exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE,-1)==2 || exif.getAttribute(ExifInterface.TAG_INTEROPERABILITY_INDEX)=="R03")
        val xmp=exif.getAttribute(ExifInterface.TAG_XMP).orEmpty()
        val gainmap=xmp.contains("hdrgm",true) || xmp.contains("hdrgainmap",true) || xmp.contains("hdr-gain-map",true)
        val orientation=if(mime=="image/avif") 1 else exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,1).takeIf {it in 1..8} ?: 1
        return ImagePipeline.ImageInfo(width,height,orientation,requireNotNull(mime),
            if(adobe) "Adobe RGB" else color?.name ?: "未标注（按 sRGB）",adobe,gainmap)
    }

    // Detect explicit HDR transfer tags even on systems that cannot report HDR ColorSpace names.
    // Conservatively reject an HDR color property in any HEIF/AVIF item, including auxiliary items.
    private fun hasHdrTransfer(bytes: ByteArray,mime: String): Boolean {
        fun u32(at: Int)=(0..3).fold(0L) {v,i->(v shl 8) or (bytes[at+i].toLong() and 255)}
        fun type(at: Int)=String(bytes,at,4,Charsets.US_ASCII)
        if(mime=="image/png") {
            var at=8
            while(at.toLong()+12<=bytes.size) {
                val length=u32(at);if(at+length+12>bytes.size) break
                if(type(at+4)=="cICP" && length==4L && (bytes[at+9].toInt() and 255) in setOf(16,18)) return true
                if(type(at+4)=="IDAT") break
                at+=(length+12).toInt()
            }
        }
        if(mime !in setOf("image/heif","image/heic","image/avif")) return false
        fun boxes(start: Int,end: Int,depth: Int): Boolean {
            if(depth>4) return false
            var at=start
            while(at.toLong()+8<=end) {
                var length=u32(at);var header=8
                if(length==1L) {
                    if(at.toLong()+16>end || u32(at+8)!=0L) return false
                    length=u32(at+12);header=16
                } else if(length==0L) length=(end-at).toLong()
                if(length<header || at+length>end) return false
                val next=(at+length).toInt();val data=at+header
                when(type(at+4)) {
                    "meta" -> if(data+4<=next && boxes(data+4,next,depth+1)) return true
                    "iprp", "ipco" -> if(boxes(data,next,depth+1)) return true
                    "colr" -> if(data+8<=next && type(data) in setOf("nclx","nclc")) {
                        val transfer=((bytes[data+6].toInt() and 255) shl 8) or (bytes[data+7].toInt() and 255)
                        if(transfer in setOf(16,18)) return true
                    }
                }
                at=next
            }
            return false
        }
        return boxes(0,bytes.size,0)
    }

    // EXIF Adobe RGB is used only when no embedded ICC takes precedence.
    private fun hasJpegIcc(bytes: ByteArray): Boolean {
        var at=2
        while(at+4<=bytes.size && bytes[at]==0xff.toByte()) {
            val marker=bytes[at+1].toInt() and 255
            if(marker==0xda || marker==0xd9) break
            val length=((bytes[at+2].toInt() and 255) shl 8) or (bytes[at+3].toInt() and 255)
            if(length<2 || at.toLong()+2+length>bytes.size) break
            if(marker==0xe2 && length>=14 && String(bytes,at+4,12,Charsets.ISO_8859_1)=="ICC_PROFILE\u0000") return true
            at+=length+2
        }
        return false
    }

    /** Takes ownership, returning a software 8-bit sRGB bitmap without a display-dependent gainmap. */
    fun toWorking(bitmap: Bitmap,info: ImagePipeline.ImageInfo): Bitmap {
        try {
            if(Build.VERSION.SDK_INT>=34 && bitmap.hasGainmap()) {
                require(Build.VERSION.SDK_INT<36 || bitmap.gainmap!!.gainmapDirection==Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR) {
                    "此 HDR 照片没有可直接编辑的 SDR 底图，请先导出 SDR JPEG"
                }
                bitmap.setGainmap(null)
            }
            if(info.assumeAdobeRgb) {
                // The untagged JPEG decoder returned its RGB numbers as sRGB; EXIF supplies their real meaning.
                val output=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888)
                try {
                    val connector=ColorSpace.connect(ColorSpace.get(ColorSpace.Named.ADOBE_RGB),srgb)
                    val row=IntArray(bitmap.width);val rgb=FloatArray(3)
                    for(y in 0 until bitmap.height) {
                        bitmap.getPixels(row,0,row.size,0,y,row.size,1)
                        for(x in row.indices) {
                            rgb[0]=Color.red(row[x])/255f;rgb[1]=Color.green(row[x])/255f;rgb[2]=Color.blue(row[x])/255f
                            connector.transform(rgb)
                            row[x]=Color.rgb((rgb[0]*255+.5f).toInt().coerceIn(0,255),(rgb[1]*255+.5f).toInt().coerceIn(0,255),(rgb[2]*255+.5f).toInt().coerceIn(0,255))
                        }
                        output.setPixels(row,0,row.size,0,y,row.size,1)
                    }
                } catch(error: Throwable) {output.recycle();throw error}
                bitmap.recycle();return output
            }
            if(bitmap.config==Bitmap.Config.ARGB_8888 && bitmap.colorSpace==srgb) return bitmap
            val output=Bitmap.createBitmap(bitmap.width,bitmap.height,Bitmap.Config.ARGB_8888,true,srgb)
            try {Canvas(output).drawBitmap(bitmap,0f,0f,null)} catch(error: Throwable) {output.recycle();throw error}
            bitmap.recycle();return output
        } catch(error: Throwable) {bitmap.recycle();throw error}
    }
    fun requireOpaque(bitmap: Bitmap,check: ()->Unit={}) {
        if(!bitmap.hasAlpha()) return
        val row=IntArray(bitmap.width)
        for(y in 0 until bitmap.height) {
            check();bitmap.getPixels(row,0,bitmap.width,0,y,bitmap.width,1)
            require(row.all {it ushr 24==255}) {"目前不支持透明像素，请先合成背景后导入"}
        }
    }
    @Suppress("DEPRECATION")
    fun decodeRegion(bytes: ByteArray,info: ImagePipeline.ImageInfo,rect: Rect): Bitmap {
        if(info.decoderOriented) return toWorking(decodeAvif(bytes,1,rect),info)
        val w=info.encodedWidth;val h=info.encodedHeight
        val l=rect.left;val t=rect.top;val r=rect.right;val b=rect.bottom
        val encoded=when(info.orientation) {
            2 -> Rect(w-r,t,w-l,b);3 -> Rect(w-r,h-b,w-l,h-t);4 -> Rect(l,h-b,r,h-t)
            5 -> Rect(t,l,b,r);6 -> Rect(t,h-r,b,h-l);7 -> Rect(w-b,h-r,w-t,h-l);8 -> Rect(w-b,l,w-t,r)
            else -> Rect(rect)
        }
        // libwebp snaps odd crop origins down to an even pixel. Decode that aligned region,
        // then trim it ourselves before applying EXIF so full and regional pixels agree.
        val aligned=if(info.mime=="image/webp") Rect(encoded.left and -2,encoded.top and -2,
            minOf(w,(encoded.right+1) and -2),minOf(h,(encoded.bottom+1) and -2)) else encoded
        val heif=info.mime in setOf("image/heif","image/heic")
        val decoder=try {BitmapRegionDecoder.newInstance(bytes,0,bytes.size,false)} catch(error: java.io.IOException) {
            if(!heif) throw error else null
        }
        if(decoder==null) {
            require(heif) {"此设备暂不支持这张照片的局部细节解码"}
            return fullFrameRegion(bytes,info,rect)
        }
        val tile=try {decoder.decodeRegion(aligned,options())} finally {decoder.recycle()}
        if(tile==null && heif) return fullFrameRegion(bytes,info,rect)
        var bitmap=requireNotNull(tile) {"无法读取原图细节"}
        try {
            if(aligned!=encoded) {
                val trimmed=Bitmap.createBitmap(bitmap,encoded.left-aligned.left,encoded.top-aligned.top,encoded.width(),encoded.height())
                if(trimmed!==bitmap) bitmap.recycle()
                bitmap=trimmed
            }
            bitmap=toWorking(bitmap,info)
            val normalized=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,ImagePipeline.orientationMatrix(info.orientation),false)
            if(normalized!==bitmap) bitmap.recycle()
            return normalized
        } catch(error: Throwable) {bitmap.recycle();throw error}
    }
    private fun fullFrameRegion(bytes: ByteArray,info: ImagePipeline.ImageInfo,rect: Rect): Bitmap {
        // Some HEIF decoders accept only origin-aligned subsets. Callers admit full-frame headroom first.
        val full=ImagePipeline.decode(bytes,1,info)
        try {
            val tile=Bitmap.createBitmap(full,rect.left,rect.top,rect.width(),rect.height())
            if(tile!==full) full.recycle()
            return tile
        } catch(error: Throwable) {full.recycle();throw error}
    }
    fun validateOpaque(bytes: ByteArray,info: ImagePipeline.ImageInfo,check: ()->Unit) {
        for(y in 0 until info.height step 256) for(x in 0 until info.width step 1024) {
            check()
            val tile=decodeRegion(bytes,info,Rect(x,y,minOf(x+1024,info.width),minOf(y+256,info.height)))
            try {requireOpaque(tile,check)} finally {tile.recycle()}
        }
    }
    private class Header(val width: Int,val height: Int,val color: ColorSpace?,val animated: Boolean): RuntimeException(null,null,false,false)
    @RequiresApi(31)
    private fun avifHeader(bytes: ByteArray): Header {
        // ImageDecoder applies AVIF container rotation/mirroring; stop at the header before allocating pixels.
        try {ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) {_,info,_->
            throw Header(info.size.width,info.size.height,info.colorSpace,info.isAnimated)
        }} catch(header: Header) {return header}
        error("无法读取 AVIF 图像信息")
    }
    fun decodeAvif(bytes: ByteArray,sample: Int,rect: Rect?=null): Bitmap {
        if(Build.VERSION.SDK_INT<31) throw IllegalArgumentException("AVIF 需要 Android 12 或更新系统")
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) {decoder,_,_->
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(srgb)
            decoder.setTargetSampleSize(sample)
            rect?.let {decoder.crop=it}
            decoder.setOnPartialImageListener {false}
        }
    }
}
