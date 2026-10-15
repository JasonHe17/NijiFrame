// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import dev.nijiframe.editor.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

class DetailRendererTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun texture(w: Int,h: Int)=Bitmap.createBitmap(IntArray(w*h) {i ->
        Color.rgb(40+(i%w*17+i/w*13)%160,40+(i%w*7+i/w*11)%160,40+(i%w*3+i/w*19)%160)
    },w,h,Bitmap.Config.ARGB_8888)
    private fun bytes(bitmap: Bitmap)=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()

    private fun compare(full: Bitmap, frame: DetailRenderer.Frame, tolerance: Int) {
        val b=frame.bounds;val expected=IntArray(b.width()*b.height());val actual=IntArray(expected.size)
        full.getPixels(expected,0,b.width(),b.left,b.top,b.width(),b.height())
        frame.bitmap.getPixels(actual,0,b.width(),0,0,b.width(),b.height())
        var maximum=0;var sum=0L
        for(i in actual.indices) for(shift in listOf(0,8,16,24)) {
            val difference=abs(((actual[i] ushr shift) and 255)-((expected[i] ushr shift) and 255))
            maximum=maxOf(maximum,difference);sum+=difference
        }
        assertTrue("max=$maximum, mean=${sum.toDouble()/actual.size/4}",maximum<=tolerance)
        assertTrue("Mean pixel error",sum.toDouble()/actual.size/4<=.25)
    }

    @Test fun detailMatchesReferenceThroughRegionsGeometryAndImageBorders() = runBlocking {
        val source=texture(241,179);val project=ProjectIO.create(bytes(source),241,179)
        val region=Region(listOf(MaskStroke(listOf(MaskPoint(.15f,.1f),MaskPoint(.7f,.8f)),.2f,.4f)))
        try {
            for(turns in 0..3) for(flip in listOf(false,true)) for(angle in listOf(0f,17f,-23f)) {
                val (w,h)=Geometry.canvasSize(241,179,turns)
                val edit=Edit(exposureEv=.3f,warmth=.2f,turns=turns,flipHorizontal=flip,flipVertical=flip,
                    straighten=angle,crop=Crop(8,9,w-17,h-21),sharpen=.7f,vignette=.6f,
                    local=LocalAdjustment(.4f,region,.6f,.75f))
                val full=ImagePipeline.render(source,edit)
                try {
                    for(center in listOf(MaskPoint(0f,0f),MaskPoint(.5f,.5f),MaskPoint(1f,1f))) {
                        val tile=DetailRenderer.render(project,edit,center,63,59)
                        try {compare(full,tile,if(angle==0f) 0 else 6)} finally {tile.bitmap.recycle()}
                    }
                } finally {full.recycle()}
            }
        } finally {source.recycle()}
    }

    @Test fun allExifOrientationsSelectTheSameOriginalPixels() = runBlocking {
        val file=File(context.cacheDir,"detail-orientation.jpg")
        val input=texture(111,87)
        try {
            file.outputStream().use {input.compress(Bitmap.CompressFormat.JPEG,95,it)}
            for(orientation in 1..8) {
                ExifInterface(file).apply {setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString());saveAttributes()}
                val encoded=file.readBytes();val source=ImagePipeline.decode(encoded)
                try {
                    val project=ProjectIO.create(encoded,source.width,source.height)
                    val edit=Edit(turns=1,flipHorizontal=true,exposureEv=.5f,vignette=.4f)
                    val full=ImagePipeline.render(source,edit)
                    val tile=DetailRenderer.render(project,edit,MaskPoint(.8f,.2f),31,29)
                    try {compare(full,tile,0)} finally {full.recycle();tile.bitmap.recycle()}
                } finally {source.recycle()}
            }
        } finally {input.recycle();file.delete()}
    }

    @Test fun hundredMegapixelDetailUsesOriginalPixelTextureAndCancellation() = runBlocking {
        // A 100 MP RGB PNG generated a row at a time; no full-resolution bitmap exists in this test.
        val encoded=ByteArrayOutputStream();val output=DataOutputStream(encoded)
        output.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
        fun chunk(type: String, data: ByteArray) {
            val tag=type.toByteArray();output.writeInt(data.size);output.write(tag);output.write(data)
            output.writeInt(CRC32().apply {update(tag);update(data)}.value.toInt())
        }
        val header=ByteArrayOutputStream()
        DataOutputStream(header).apply {writeInt(10000);writeInt(10000);write(byteArrayOf(8,2,0,0,0))}
        chunk("IHDR",header.toByteArray())
        val compressed=ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use {zip ->
            val row=ByteArray(30001) {i -> if(i==0) 0 else if(((i-1)/3)%2==0) 40 else 180.toByte()}
            repeat(10000) {zip.write(row)}
        }
        chunk("IDAT",compressed.toByteArray());chunk("IEND",byteArrayOf())
        val project=ProjectIO.create(encoded.toByteArray(),10000,10000)
        val frame=DetailRenderer.render(project,Edit(),MaskPoint(.8f,.9f),256,192)
        try {
            assertEquals(256,frame.bitmap.width);assertEquals(192,frame.bitmap.height)
            assertEquals(140,abs(Color.red(frame.bitmap.getPixel(40,40))-Color.red(frame.bitmap.getPixel(41,40))))
        } finally {frame.bitmap.recycle()}
        val task=launch(Dispatchers.Default) {
            DetailRenderer.render(project,Edit(sharpen=1f),MaskPoint(.1f,.1f),768,768).bitmap.recycle()
        }
        task.cancelAndJoin();assertTrue(task.isCancelled)
        assertEquals(10000,project.manifest.width)
    }
}
