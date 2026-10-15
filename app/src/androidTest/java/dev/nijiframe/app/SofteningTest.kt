// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.nijiframe.editor.*
import dev.nijiframe.image.NativeTone
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class SofteningTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun texture(w: Int,h: Int): Bitmap {
        val pixels=IntArray(w*h){i->val v=100+((i%w+i/w)%2)*8;0xff000000.toInt() or (v shl 16) or (v shl 8) or v}
        return Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
    }
    private val region=Region(listOf(MaskStroke(listOf(MaskPoint(.5f,.5f)),.2f,1f)))
    @Test fun actualPipelineHasNoStripSeamsAndPreservesOutsidePixels() = runBlocking {
        val w=301;val h=99;val source=texture(w,h)
        val local=LocalAdjustment(region=region,soften=.65f,detailProtection=.75f)
        val pixels=IntArray(w*h);source.getPixels(pixels,0,w,0,0,w,h)
        val mask=MaskRaster.create(region,w,h)
        val coverage=ByteArray(w*h){i->mask.sample((i%w+.5f)/w,(i/w+.5f)/h).toByte()}
        val expected=IntArray(w*h)
        NativeTone.soften(pixels,expected,coverage,w,h,0,h,1,local.soften,local.detailProtection)
        val result=ImagePipeline.render(source,Edit(local=local))
        val actual=IntArray(w*h);result.getPixels(actual,0,w,0,0,w,h)
        assertArrayEquals(expected,actual)
        assertTrue(actual.indices.any {actual[it]!=pixels[it]})
        for(i in pixels.indices) if(coverage[i].toInt()==0) assertEquals(pixels[i],actual[i])
        val disabled=ImagePipeline.render(source,Edit(local=local.copy(soften=0f)))
        assertTrue(source.sameAs(disabled))
        val empty=ImagePipeline.render(source,Edit(local=local.copy(region=Region())))
        assertTrue(source.sameAs(empty))
        source.recycle();result.recycle();disabled.recycle();empty.recycle()
    }
    @Test fun softeningIsEditableAfterPortableRoundTripAndExport() = runBlocking {
        val root=File(context.cacheDir,"soften-${System.nanoTime()}").apply {mkdirs()}
        val repository=ProjectRepository(context,root);val source=texture(300,200)
        try {
            val bytes=ByteArrayOutputStream().also {source.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
            val edit=Edit(turns=1,flipHorizontal=true,crop=Crop(10,20,180,260),local=LocalAdjustment(.25f,region,.7f,.8f))
            val p=ProjectIO.create(bytes,300,200).withEdit(edit)
            val pkg=File(root,"portable.nijiproj");repository.exportProject(p,Uri.fromFile(pkg))
            val (loaded,decoded)=repository.open(Uri.fromFile(pkg))
            assertEquals(edit,loaded.manifest.edit);assertEquals(4,loaded.manifest.version);assertArrayEquals(bytes,loaded.original)
            val png=File(root,"portrait.png")
            repository.exportPhoto(loaded,decoded,Uri.fromFile(png),ExportSettings(format=PhotoFormat.PNG))
            val exported=BitmapFactory.decodeFile(png.path);val expected=ImagePipeline.render(source,edit)
            assertTrue(expected.sameAs(exported));assertEquals(180,exported.width);assertEquals(260,exported.height)
            val without=ImagePipeline.render(source,edit.copy(local=edit.local.copy(soften=0f)))
            assertFalse(expected.sameAs(without))
            val job=launch(Dispatchers.Default) {ImagePipeline.render(source,edit).recycle()};job.cancelAndJoin();assertTrue(job.isCancelled)
            decoded.recycle();exported.recycle();expected.recycle();without.recycle()
        } finally {source.recycle();root.deleteRecursively()}
    }
}
