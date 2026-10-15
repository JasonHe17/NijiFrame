// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.nijiframe.editor.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.Deflater

class LargeImageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun twoHundredMegapixelJpegKeepsNativeDetailAndEditableOriginal() = runBlocking {
        val root=File(context.cacheDir,"phone-large-${System.nanoTime()}").apply {mkdirs()}
        val bytes=instrumentation.context.assets.open("formats/large-201mp.jpg").use {it.readBytes()}
        val file=File(root,"phone.jpg").apply {writeBytes(bytes)}
        val repo=ProjectRepository(context,File(root,"library"))
        val (project,proxy)=repo.importPhoto(Uri.fromFile(file))
        try {
            assertEquals(12288,project.manifest.width);assertEquals(16384,project.manifest.height)
            assertEquals(1536,proxy.width);assertEquals(2048,proxy.height)
            assertArrayEquals(bytes,project.original)
            val detail=DetailRenderer.render(project,Edit(),MaskPoint(.5f,.5f),64,64)
            try {
                // EXIF 6 turns the original vertical one-pixel stripes into horizontal ones.
                assertTrue(kotlin.math.abs(Color.red(detail.bitmap.getPixel(32,32))-
                    Color.red(detail.bitmap.getPixel(32,33)))>180)
            } finally {detail.bitmap.recycle()}
            val edit=Edit(exposureEv=.5f,turns=1,crop=Crop(100,100,16000,12000))
            val portable=File(root,"large.nijiproj")
            repo.exportProject(project.withEdit(edit),Uri.fromFile(portable));file.delete()
            val (reopened,decoded)=repo.open(Uri.fromFile(portable))
            try {
                assertArrayEquals(bytes,reopened.original);assertEquals(edit,reopened.manifest.edit)
                // A constrained device must refuse before touching an existing output.
                val limited=ProjectRepository(context,File(root,"limited")) {96L*1024*1024}
                val output=File(root,"output.png").apply {writeText("keep")}
                try {limited.exportPhoto(reopened,decoded,Uri.fromFile(output));fail("Expected memory refusal")}
                catch(error: IllegalArgumentException) {assertTrue(error.message!!.contains("内存"))}
                assertEquals("keep",output.readText())
                limited.exportPhoto(reopened,decoded,Uri.fromFile(output),ExportSettings(PhotoFormat.PNG,maxEdge=1280))
                val rendered=BitmapFactory.decodeFile(output.path)
                try {
                    assertEquals(1280,rendered.width);assertEquals(960,rendered.height)
                    assertTrue(Color.red(rendered.getPixel(200,200))>80)
                } finally {rendered.recycle()}
                assertArrayEquals(bytes,reopened.original)
            } finally {decoded.recycle()}
        } finally {proxy.recycle();root.deleteRecursively()}
    }

    // Generate large, lossless synthetic inputs without a full-sized test bitmap allocation.
    private fun png(width: Int, height: Int, transparent: Boolean = false, alphaChannel: Boolean = transparent): ByteArray {
        val encoded=ByteArrayOutputStream()
        val output=DataOutputStream(encoded)
        output.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type: String, bytes: ByteArray) {
            val name=type.toByteArray(Charsets.US_ASCII)
            output.writeInt(bytes.size);output.write(name);output.write(bytes)
            output.writeInt(CRC32().apply {update(name);update(bytes)}.value.toInt())
        }
        val header=ByteArrayOutputStream()
        DataOutputStream(header).apply {writeInt(width);writeInt(height);writeByte(8);writeByte(if(alphaChannel) 6 else 2);write(byteArrayOf(0,0,0))}
        chunk("IHDR",header.toByteArray())
        val compressed=ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { zip ->
            val channels=if(alphaChannel) 4 else 3
            val row=ByteArray(1+width*channels) { if(it==0) 0 else if(alphaChannel && (it-1)%4==3) 255.toByte() else 80 }
            for(y in 0 until height) {
                if(transparent && y==height-1) row[row.lastIndex]=0 // A single pixel missed by many sampled decodes.
                zip.write(row)
            }
        }
        chunk("IDAT",compressed.toByteArray());chunk("IEND",byteArrayOf())
        return encoded.toByteArray()
    }

    @Test fun fortyEightMegapixelProjectKeepsOriginalAndCoordinatesAcrossExport() = runBlocking {
        val root=File(context.cacheDir,"large-${System.nanoTime()}").apply {mkdirs()}
        val repo=ProjectRepository(context,File(root,"library"))
        val original=png(8000,6000)
        val file=File(root,"48mp.png").apply {writeBytes(original)}
        val (project,proxy)=repo.importPhoto(Uri.fromFile(file))
        try {
            assertEquals(8000,project.manifest.width);assertEquals(6000,project.manifest.height)
            assertTrue(proxy.width<=2048 && proxy.height<=2048)
            assertArrayEquals(original,project.original)
            val region=Region(listOf(MaskStroke(listOf(MaskPoint(.25f,.5f)),.12f,1f)))
            val edit=Edit(turns=1,crop=Crop(500,1000,5000,6000),local=LocalAdjustment(1f,region,.4f,.75f))
            val saved=project.withEdit(edit)
            val portable=File(root,"large.nijiproj")
            repo.exportProject(saved,Uri.fromFile(portable))
            file.delete()
            val (reopened,decoded)=repo.open(Uri.fromFile(portable))
            try {
                assertEquals(edit,reopened.manifest.edit);assertArrayEquals(original,reopened.original)
                val output=File(root,"reduced.png")
                repo.exportPhoto(reopened,decoded,Uri.fromFile(output),ExportSettings(PhotoFormat.PNG,maxEdge=1280))
                val rendered=BitmapFactory.decodeFile(output.path)
                try {
                    assertEquals(1067,rendered.width);assertEquals(1280,rendered.height)
                    assertTrue(Color.red(rendered.getPixel(533,213))>Color.red(rendered.getPixel(533,1066))+20)
                } finally {rendered.recycle()}
                // Refusal precedes destination truncation, and the same original can still export small.
                val limited=ProjectRepository(context,File(root,"limited")) { 80L*1024*1024 }
                val destination=File(root,"untouched.png").apply {writeText("keep")}
                try {limited.exportPhoto(reopened,decoded,Uri.fromFile(destination));fail("Expected memory admission failure")}
                catch(error: IllegalArgumentException) {assertTrue(error.message!!.contains("内存"))}
                assertEquals("keep",destination.readText())
                limited.exportPhoto(reopened,decoded,Uri.fromFile(destination),ExportSettings(PhotoFormat.PNG,maxEdge=1280))
                assertTrue(destination.length()>4)
            } finally {decoded.recycle()}
        } finally {proxy.recycle();root.deleteRecursively()}
    }

    @Test fun hundredMegapixelImportUsesBoundedPreviewAndNewViewModelRestores() = runBlocking {
        val root=File(context.cacheDir,"large-model-${System.nanoTime()}").apply {mkdirs()}
        val repo=ProjectRepository(context,File(root,"library"))
        val file=File(root,"100mp.png").apply {writeBytes(png(10000,10000))}
        val store=ViewModelStore()
        lateinit var model: EditorViewModel
        fun main(block: ()->Unit)=instrumentation.runOnMainSync(block)
        suspend fun idle() = withTimeout(30_000) {while(model.ui.value.busy || model.ui.value.rendering || model.ui.value.saving) delay(20)}
        try {
            main {model=EditorViewModel(context.applicationContext as Application,repo);store.put("editor",model)}
            idle();main {model.importPhoto(Uri.fromFile(file))};idle()
            assertNull(model.ui.value.error)
            assertEquals(10000,model.ui.value.project!!.manifest.width)
            assertTrue(model.ui.value.approximatePreview)
            assertTrue(model.ui.value.preview!!.width<=1600)
            val edit=Edit(turns=1,crop=Crop(100,200,8000,6000),exposureEv=.5f)
            main {model.commit(edit)};idle()
            assertEquals(edit,repo.restore().also {it.second.recycle()}.first.manifest.edit)
            main {store.clear();model=EditorViewModel(context.applicationContext as Application,repo);store.put("editor",model)}
            idle();assertNull(model.ui.value.error)
            assertEquals(edit,model.ui.value.project!!.manifest.edit)
            assertTrue(model.ui.value.approximatePreview)
        } finally {main {store.clear()};root.deleteRecursively()}
    }

    @Test fun twelveMegapixelJpegExportsFullResolutionWithExifAndColorIntact() = runBlocking {
        val root=File(context.cacheDir,"large-jpeg-${System.nanoTime()}").apply {mkdirs()}
        val file=File(root,"phone.jpg")
        val source=Bitmap.createBitmap(4000,3000,Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(80,80,80))
        val detail=IntArray(100*100) { if((it/100+it%100)%2==0) Color.BLACK else Color.WHITE }
        source.setPixels(detail,0,100,2000,1500,100,100)
        file.outputStream().use {source.compress(Bitmap.CompressFormat.JPEG,90,it)}
        source.recycle()
        ExifInterface(file).apply {setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes()}
        val repo=ProjectRepository(context,File(root,"library"))
        val (project,proxy)=repo.importPhoto(Uri.fromFile(file))
        try {
            assertEquals(3000,project.manifest.width);assertEquals(4000,project.manifest.height)
            assertEquals(1500,proxy.width);assertEquals(2000,proxy.height)
            val output=File(root,"full.jpg")
            repo.exportPhoto(project.withEdit(Edit(exposureEv=1f)),proxy,Uri.fromFile(output))
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(output.path,bounds)
            assertEquals(3000,bounds.outWidth);assertEquals(4000,bounds.outHeight)
            val small=BitmapFactory.decodeFile(output.path,BitmapFactory.Options().apply {inSampleSize=8})
            try {assertTrue(Color.red(small.getPixel(100,100)) in 110..115)} finally {small.recycle()}
            // A 1-pixel checker survives original decoding; enlarging the sampled preview would erase it.
            @Suppress("DEPRECATION")
            val decoder=BitmapRegionDecoder.newInstance(output.path,false)!!
            try {
                val patch=decoder.decodeRegion(Rect(1400,2000,1500,2100),BitmapFactory.Options())!!
                try {assertTrue(kotlin.math.abs(Color.red(patch.getPixel(40,40))-Color.red(patch.getPixel(41,40)))>100)}
                finally {patch.recycle()}
            } finally {decoder.recycle()}
            assertEquals(ExifInterface.ORIENTATION_UNDEFINED,ExifInterface(output).getAttributeInt(ExifInterface.TAG_ORIENTATION,0))
        } finally {proxy.recycle();root.deleteRecursively()}
    }

    @Test fun sampledInputStillRejectsAnIsolatedTransparentPixel() {
        val opaque=ImagePipeline.decode(png(3001,3000,alphaChannel=true),2)
        try { assertEquals(255,Color.alpha(opaque.getPixel(opaque.width-1,opaque.height-1))) }
        finally { opaque.recycle() }
        val bytes=png(3001,3000,true)
        assertThrows(IllegalArgumentException::class.java) {ImagePipeline.decode(bytes,2)}
    }

    @Test fun encodedPhotoAboveThirtyTwoMiBImportsAndReopensWithoutTruncation() {
        val root=File(context.cacheDir,"large-file-${System.nanoTime()}").apply {mkdirs()}
        val file=File(root,"36mb.png")
        // RGB PNG with uncompressed deflate blocks: a real 12 MP, ~36 MB image, generated row by row.
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
            fun chunk(type: String, bytes: ByteArray, length: Int = bytes.size) {
                val name=type.toByteArray(Charsets.US_ASCII)
                output.writeInt(length);output.write(name);output.write(bytes,0,length)
                output.writeInt(CRC32().apply {update(name);update(bytes,0,length)}.value.toInt())
            }
            val header=ByteArrayOutputStream()
            DataOutputStream(header).apply {writeInt(4000);writeInt(3000);write(byteArrayOf(8,2,0,0,0))}
            chunk("IHDR",header.toByteArray())
            val chunks=object: OutputStream() {
                private val buffer=ByteArray(64*1024)
                private var size=0
                override fun write(b: Int) {buffer[size++]=b.toByte();if(size==buffer.size) flush()}
                override fun write(b: ByteArray, off: Int, len: Int) {
                    var cursor=off
                    while(cursor<off+len) {
                        val count=minOf(buffer.size-size,off+len-cursor)
                        b.copyInto(buffer,size,cursor,cursor+count);size+=count;cursor+=count
                        if(size==buffer.size) flush()
                    }
                }
                override fun flush() {if(size>0) {chunk("IDAT",buffer,size);size=0}}
                override fun close()=flush()
            }
            val deflater=Deflater(Deflater.NO_COMPRESSION)
            try {
                DeflaterOutputStream(chunks,deflater).use {zip ->
                    val row=ByteArray(1+4000*3) {if(it==0) 0 else 80}
                    repeat(3000) {zip.write(row)}
                }
            } finally {deflater.end()}
            chunk("IEND",byteArrayOf())
        }
        try {
            assertTrue(file.length()>32L*1024*1024)
            val repo=ProjectRepository(context,File(root,"library"))
            val (project,proxy)=repo.importPhoto(Uri.fromFile(file))
            try {
                assertEquals(file.length(),project.original.size.toLong())
                val id=repo.library.create(project)
                val (reopened,preview)=repo.read(id)
                try {
                    assertEquals(project.manifest.sourceSha256,reopened.manifest.sourceSha256)
                    assertArrayEquals(project.original,reopened.original)
                    assertEquals(2000,preview.width)
                } finally {preview.recycle()}
            } finally {proxy.recycle()}
        } finally {root.deleteRecursively()}
    }
}
