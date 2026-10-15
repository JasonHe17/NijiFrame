// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.*
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import dev.nijiframe.editor.*
import dev.nijiframe.editor.Region
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

class PhotoFormatTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private fun encode(bitmap: Bitmap,format: Bitmap.CompressFormat)=ByteArrayOutputStream().also {
        assertTrue(bitmap.compress(format,100,it))
    }.toByteArray()
    private fun closeColor(expected: Int,actual: Int,tolerance: Int=2) {
        for(shift in listOf(0,8,16)) assertTrue("Color $expected != $actual (channel $shift)",
            abs((expected shr shift and 255)-(actual shr shift and 255))<=tolerance)
    }
    private suspend fun roundTrip(bytes: ByteArray,expectedWidth: Int,expectedHeight: Int): Bitmap {
        val root=File(context.cacheDir,"formats-${System.nanoTime()}").apply {mkdirs()}
        val repository=ProjectRepository(context,File(root,"library"))
        val input=File(root,"input").apply {writeBytes(bytes)}
        val (project,decoded)=repository.importPhoto(Uri.fromFile(input))
        try {
            assertEquals(expectedWidth,project.manifest.width);assertEquals(expectedHeight,project.manifest.height)
            assertEquals(6,project.manifest.version);assertTrue(project.manifest.honorExifColorSpace);assertArrayEquals(bytes,project.original)
            assertEquals(ColorSpace.get(ColorSpace.Named.SRGB),decoded.colorSpace)
            assertEquals(Bitmap.Config.ARGB_8888,decoded.config)
            if(Build.VERSION.SDK_INT>=34) assertFalse(decoded.hasGainmap())
            val region=Region(listOf(MaskStroke(listOf(MaskPoint(.4f,.5f)),.12f,1f)))
            val edit=Edit(turns=1,flipHorizontal=true,local=LocalAdjustment(.25f,region,.2f))
            val saved=project.withEdit(edit)
            val archive=File(root,"test.nijiproj");repository.exportProject(saved,Uri.fromFile(archive));input.delete()
            val (reopened,source)=repository.open(Uri.fromFile(archive))
            try {
                assertArrayEquals(bytes,reopened.original);assertEquals(edit,reopened.manifest.edit)
                val output=File(root,"output.png")
                repository.exportPhoto(reopened,source,Uri.fromFile(output),ExportSettings(PhotoFormat.PNG))
                val exported=BitmapFactory.decodeFile(output.path)
                val frame=DetailRenderer.render(reopened,edit,MaskPoint(.5f,.5f),80,64)
                try {
                    assertEquals(expectedHeight,exported.width);assertEquals(expectedWidth,exported.height)
                    assertEquals(ColorSpace.get(ColorSpace.Named.SRGB),exported.colorSpace)
                    for(y in 4 until frame.bitmap.height-4 step 7) for(x in 4 until frame.bitmap.width-4 step 7)
                        closeColor(exported.getPixel(frame.bounds.left+x,frame.bounds.top+y),frame.bitmap.getPixel(x,y),3)
                } finally {exported.recycle();frame.bitmap.recycle()}
            } finally {source.recycle()}
            return decoded.copy(Bitmap.Config.ARGB_8888,false)
        } finally {decoded.recycle();root.deleteRecursively()}
    }

    @Test fun wideGamutJpegAndPngConvertPixelsInsteadOfRelabeling() = runBlocking {
        for(space in listOf(ColorSpace.Named.DISPLAY_P3,ColorSpace.Named.ADOBE_RGB)) {
            val colorSpace=ColorSpace.get(space)
            val bitmap=Bitmap.createBitmap(192,128,Bitmap.Config.ARGB_8888,false,colorSpace)
            bitmap.eraseColor(Color.pack(.7f,.3f,.2f,1f,colorSpace))
            val rgb=ColorSpace.connect(colorSpace,ColorSpace.get(ColorSpace.Named.SRGB)).transform(.7f,.3f,.2f)
            val expected=Color.rgb((rgb[0]*255+.5f).toInt(),(rgb[1]*255+.5f).toInt(),(rgb[2]*255+.5f).toInt())
            try {
                for(format in listOf(Bitmap.CompressFormat.JPEG,Bitmap.CompressFormat.PNG)) {
                    val bytes=encode(bitmap,format)
                    val info=ImagePipeline.inspect(bytes)
                    assertFalse("Fixture must really carry a wide profile",info.colorName.contains("sRGB"))
                    val decoded=roundTrip(bytes,192,128)
                    try {closeColor(expected,decoded.getPixel(40,40),3)} finally {decoded.recycle()}
                }
            } finally {bitmap.recycle()}
        }
    }

    @Test fun cameraExifAdobeRgbAndHighBitDepthPngImport() = runBlocking {
        val file=File(context.cacheDir,"adobe-camera.jpg")
        val raw=Bitmap.createBitmap(192,128,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(178,76,51))}
        try {
            // Bitmap.compress embeds an sRGB ICC; create a genuinely untagged camera-style JPEG.
            val encoded=encode(raw,Bitmap.CompressFormat.JPEG)
            val withoutIcc=ByteArrayOutputStream().apply {write(encoded,0,2)}
            var at=2
            while(at+4<encoded.size) {
                val marker=encoded[at+1].toInt() and 255
                if(marker==0xda) break
                val size=((encoded[at+2].toInt() and 255) shl 8) or (encoded[at+3].toInt() and 255)
                if(marker!=0xe2) withoutIcc.write(encoded,at,size+2)
                at+=size+2
            }
            withoutIcc.write(encoded,at,encoded.size-at)
            file.writeBytes(withoutIcc.toByteArray())
            ExifInterface(file).apply {setAttribute(ExifInterface.TAG_COLOR_SPACE,"2");saveAttributes()}
            val bytes=file.readBytes();assertTrue(ImagePipeline.inspect(bytes).assumeAdobeRgb)
            val plain=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            val pixel=plain.getPixel(40,40);plain.recycle()
            val rgb=ColorSpace.connect(ColorSpace.get(ColorSpace.Named.ADOBE_RGB),ColorSpace.get(ColorSpace.Named.SRGB))
                .transform(Color.red(pixel)/255f,Color.green(pixel)/255f,Color.blue(pixel)/255f)
            val expected=Color.rgb((rgb[0]*255+.5f).toInt(),(rgb[1]*255+.5f).toInt(),(rgb[2]*255+.5f).toInt())
            val decoded=roundTrip(bytes,192,128)
            try {closeColor(expected,decoded.getPixel(40,40))} finally {decoded.recycle()}
            // The old decoder ignored this EXIF hint. Migration must retain those saved colors.
            val legacyRoot=File(context.cacheDir,"legacy-exif-${System.nanoTime()}").apply {mkdirs()}
            try {
                val repo=ProjectRepository(context,File(legacyRoot,"library"))
                val current=ProjectIO.create(bytes,192,128)
                val old=current.copy(manifest=current.manifest.copy(version=5,pipeline="srgb-region-v5",honorExifColorSpace=false))
                val archive=File(legacyRoot,"old.nijiproj");repo.exportProject(old,Uri.fromFile(archive))
                val (loaded,legacyPixels)=repo.open(Uri.fromFile(archive))
                try {
                    assertFalse(loaded.manifest.honorExifColorSpace)
                    closeColor(pixel,legacyPixels.getPixel(40,40),0)
                    assertFalse(loaded.withEdit(Edit(exposureEv=.2f)).manifest.honorExifColorSpace)
                } finally {legacyPixels.recycle()}
            } finally {legacyRoot.deleteRecursively()}
            val high=roundTrip(png16(),192,128)
            try {closeColor(Color.rgb(128,64,32),high.getPixel(40,40))} finally {high.recycle()}
        } finally {raw.recycle();file.delete()}
    }

    @Test fun platformPhoneFormatsRetainOrientationAndEditableOriginals() = runBlocking {
        for(extension in listOf("heic","webp","avif")) for(orientation in if(extension=="avif") (1..8).toList() else listOf(1,2,6)) {
            val prefix=if(extension=="avif") "limited" else "quadrants"
            val bytes=ins.context.assets.open("formats/$prefix-$orientation.$extension").use {it.readBytes()}
            if(extension=="avif" && Build.VERSION.SDK_INT<31) {
                try {ImagePipeline.inspect(bytes);fail("Old system must explain unavailable AVIF")}
                catch(error: IllegalArgumentException) {assertTrue(error.message!!.contains("Android 12"))}
                continue
            }
            val width=if(orientation>=5) 128 else 192;val height=if(orientation>=5) 192 else 128
            val decoded=try {roundTrip(bytes,width,height)} catch(error: Throwable) {
                throw AssertionError("Failed $extension orientation $orientation",error)
            }
            val expected=when(orientation) {
                2,8->Color.rgb(50,160,80);3,7->Color.rgb(170,150,50)
                4,6->Color.rgb(60,80,180);else->Color.rgb(180,60,70)
            }
            try {closeColor(expected,decoded.getPixel(20,20),6)}
            catch(error: AssertionError) {throw AssertionError("$extension orientation $orientation",error)}
            finally {decoded.recycle()}
        }
    }

    @Test fun sampledWidePhotoUsesSameColorPolicyInPreviewDetailAndExport() = runBlocking {
        val root=File(context.cacheDir,"wide-large-${System.nanoTime()}").apply {mkdirs()}
        val space=ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val image=Bitmap.createBitmap(3840,2160,Bitmap.Config.ARGB_8888,false,space)
        image.eraseColor(Color.pack(.7f,.3f,.2f,1f,space))
        val expected=image.getPixel(40,40)
        val bytes=encode(image,Bitmap.CompressFormat.JPEG);image.recycle()
        val file=File(root,"wide.jpg").apply {writeBytes(bytes)}
        val repo=ProjectRepository(context,File(root,"library"))
        val (project,proxy)=repo.importPhoto(Uri.fromFile(file))
        try {
            assertEquals(1920,proxy.width);closeColor(expected,proxy.getPixel(40,40),3)
            val frame=DetailRenderer.render(project,Edit(),MaskPoint(.4f,.6f),128,128)
            try {closeColor(expected,frame.bitmap.getPixel(40,40),3)} finally {frame.bitmap.recycle()}
            val out=File(root,"export.png")
            repo.exportPhoto(project,proxy,Uri.fromFile(out),ExportSettings(PhotoFormat.PNG,maxEdge=1280))
            val result=BitmapFactory.decodeFile(out.path)
            try {assertEquals(1280,result.width);closeColor(expected,result.getPixel(40,40),3)} finally {result.recycle()}
            assertArrayEquals(bytes,project.original)
        } finally {proxy.recycle();root.deleteRecursively()}
    }

    @Test fun codecMemoryRefusalLeavesExistingLibraryIntact() {
        val root=File(context.cacheDir,"codec-memory-${System.nanoTime()}").apply {mkdirs()}
        val repo=ProjectRepository(context,File(root,"library")) {32L*1024*1024+64*1024}
        val original=ProjectIO.create(byteArrayOf(1),1,1)
        val id=repo.library.create(original)
        try {
            val file=File(root,"photo.heic").apply {writeBytes(ins.context.assets.open("formats/quadrants-1.heic").use {it.readBytes()})}
            try {repo.importPhoto(Uri.fromFile(file));fail("Expected codec memory refusal")}
            catch(error: IllegalArgumentException) {assertTrue(error.message!!.contains("解码需要更多内存"))}
            assertArrayEquals(original.original,repo.library.read(id).original)
            assertEquals(1,repo.library.entries().size)
        } finally {root.deleteRecursively()}
    }

    @Test fun ultraHdrJpegUsesSdrBaseAndKeepsOriginalGainmap() = runBlocking {
        for(p3 in listOf(false,true)) {
            val space=ColorSpace.get(if(p3) ColorSpace.Named.DISPLAY_P3 else ColorSpace.Named.SRGB)
            val filename=if(p3) "ultrahdr-p3.jpg" else "ultrahdr.jpg"
            val bytes=if(Build.VERSION.SDK_INT>=34) {
                val base=Bitmap.createBitmap(192,128,Bitmap.Config.ARGB_8888,false,space).apply {
                    eraseColor(Color.pack(100/255f,120/255f,140/255f,1f,space))
                }
                val map=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(190,190,190))}
                try {
                    base.setGainmap(Gainmap(map).apply {setRatioMax(4f,4f,4f);setDisplayRatioForFullHdr(4f)})
                    encode(base,Bitmap.CompressFormat.JPEG).also {
                        // Generated solely from uniform colors; retained as an older-platform regression fixture.
                        File(context.cacheDir,"synthetic-$filename").writeBytes(it)
                    }
                } finally {base.recycle();map.recycle()}
            } else ins.context.assets.open("formats/$filename").use {it.readBytes()}
            val source=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            if(Build.VERSION.SDK_INT>=34) assertTrue("Fixture must contain a gainmap",source.hasGainmap())
            source.recycle()
            val rgb=ColorSpace.connect(space,ColorSpace.get(ColorSpace.Named.SRGB)).transform(100/255f,120/255f,140/255f)
            val expected=Color.rgb((rgb[0]*255+.5f).toInt(),(rgb[1]*255+.5f).toInt(),(rgb[2]*255+.5f).toInt())
            val decoded=roundTrip(bytes,192,128)
            try {closeColor(expected,decoded.getPixel(40,40),3)} finally {decoded.recycle()}
        }
    }

    @Test fun fullRangeHeifKeepsKnownOlderCodecLimitationVisible() = runBlocking {
        val bytes=ins.context.assets.open("formats/full-range.heic").use {it.readBytes()}
        val info=ImagePipeline.inspect(bytes)
        val decoded=roundTrip(bytes,192,128)
        try {
            val expected=Color.rgb(180,60,70);val actual=decoded.getPixel(20,20)
            if(Build.VERSION.SDK_INT<=32) {
                // API 29/31 AOSP expand full-range HEVC as limited-range. Preserve the failing
                // fixture and expose the limitation; do not claim the codec's colors are fixed.
                assertTrue(info.notice.contains("色偏"))
                val platform=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                try {assertEquals(platform.getPixel(20,20),actual)} finally {platform.recycle()}
                println("Full-range HEIF codec reference=$expected platform=$actual; known older-system limitation")
            } else closeColor(expected,actual,6)
        } finally {decoded.recycle()}
    }

    @Test fun fullRangeAvifKeepsOriginalFailuresVisibleAndNormalizesMirrors() = runBlocking {
        if(Build.VERSION.SDK_INT<31) {
            val bytes=ins.context.assets.open("formats/quadrants-2.avif").use {it.readBytes()}
            assertThrows(IllegalArgumentException::class.java) {ImagePipeline.inspect(bytes)}
            return@runBlocking
        }
        for(orientation in listOf(1,2,6)) {
            // Preserve original full-range fixtures: API 31 misinterprets their range.
            val bytes=ins.context.assets.open("formats/quadrants-$orientation.avif").use {it.readBytes()}
            val info=ImagePipeline.inspect(bytes)
            assertEquals(orientation,info.orientation)
            val decoded=roundTrip(bytes,if(orientation==6) 128 else 192,if(orientation==6) 192 else 128)
            try {
                if(Build.VERSION.SDK_INT<=32) {
                    assertTrue(info.notice.contains("色偏"))
                    val raw=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                    // Pixel coordinates are independently mapped here; do not reuse the product transform.
                    val reference=when(orientation) {2->raw.getPixel(171,20);6->raw.getPixel(20,107);else->raw.getPixel(20,20)}
                    try {assertEquals(reference,decoded.getPixel(20,20))} finally {raw.recycle()}
                } else {
                    val expected=when(orientation) {2->Color.rgb(50,160,80);6->Color.rgb(60,80,180);else->Color.rgb(180,60,70)}
                    closeColor(expected,decoded.getPixel(20,20),6)
                }
            } finally {decoded.recycle()}
        }
    }

    @Test fun unsupportedHdrTransferAndAnimationAreExplicitlyRefused() {
        for((name,reason) in listOf("pq.heic" to "HLG/PQ","animated.webp" to "动画")) {
            val bytes=ins.context.assets.open("formats/$name").use {it.readBytes()}
            try {ImagePipeline.inspect(bytes);fail("Must reject $name")}
            catch(error: IllegalArgumentException) {assertTrue(error.message.orEmpty(),error.message!!.contains(reason))}
        }
        try {ImagePipeline.inspect(png16(hdr=true));fail("PQ PNG must not be clipped into SDR")}
        catch(error: IllegalArgumentException) {assertTrue(error.message!!.contains("HLG/PQ"))}
    }

    private fun png16(hdr: Boolean=false): ByteArray {
        val out=ByteArrayOutputStream();val data=DataOutputStream(out)
        data.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type:String,bytes:ByteArray) {
            val name=type.toByteArray();data.writeInt(bytes.size);data.write(name);data.write(bytes)
            data.writeInt(CRC32().apply {update(name);update(bytes)}.value.toInt())
        }
        val header=ByteArrayOutputStream();DataOutputStream(header).apply {writeInt(192);writeInt(128);write(byteArrayOf(16,2,0,0,0))}
        chunk("IHDR",header.toByteArray())
        if(hdr) chunk("cICP",byteArrayOf(9,16,0,1))
        val packed=ByteArrayOutputStream()
        DeflaterOutputStream(packed).use {zip->
            val row=ByteArrayOutputStream();val values=DataOutputStream(row);values.writeByte(0)
            repeat(192) {values.writeShort(128*257);values.writeShort(64*257);values.writeShort(32*257)}
            repeat(128) {zip.write(row.toByteArray())}
        }
        chunk("IDAT",packed.toByteArray());chunk("IEND",byteArrayOf());return out.toByteArray()
    }
}
