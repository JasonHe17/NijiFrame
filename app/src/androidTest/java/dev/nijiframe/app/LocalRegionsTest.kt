// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.nijiframe.editor.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class LocalRegionsTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private fun circle(x: Float)=Region(listOf(MaskStroke(listOf(MaskPoint(x,.5f)),.12f,1f)))
    private fun encode(bitmap: Bitmap)=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
    private fun layers()=listOf(
        LocalRegion("face","脸部",LocalAdjustment(region=circle(.2f),warmth=.7f)),
        LocalRegion("clothes","衣服",LocalAdjustment(region=circle(.5f),tint=.8f,saturation=.2f)),
        LocalRegion("background","背景",LocalAdjustment(-1f,circle(.8f))))

    @Test fun threeRegionsHaveIndependentColorScopeAndSwitches() = runBlocking {
        val source=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(100,100,100))}
        val edit=Edit(regions=layers(),selectedRegionId="face")
        val result=ImagePipeline.render(source,edit)
        val switched=ImagePipeline.render(source,edit.selectRegion("clothes"))
        val disabled=ImagePipeline.render(source,edit.changeActiveRegion {it.copy(enabled=false)})
        try {
            assertTrue(Color.red(result.getPixel(60,100))>Color.blue(result.getPixel(60,100)))
            assertTrue(Color.green(result.getPixel(150,100))<Color.red(result.getPixel(150,100)))
            assertTrue(Color.red(result.getPixel(240,100))<100)
            assertEquals(source.getPixel(60,100),disabled.getPixel(60,100))
            for(y in 0 until 200) for(x in 0 until 300) {
                assertEquals(result.getPixel(x,y),switched.getPixel(x,y))
                if(y<60 || y>140) assertEquals(source.getPixel(x,y),result.getPixel(x,y))
            }
            assertEquals(result.getPixel(150,100),disabled.getPixel(150,100))
            val sequential=ImagePipeline.render(source,Edit(regions=layers().map {it.copy(adjustment=it.adjustment.copy(region=circle(.5f)))}))
            val reversed=ImagePipeline.render(source,Edit(regions=layers().reversed().map {it.copy(adjustment=it.adjustment.copy(region=circle(.5f)))}))
            try {assertNotEquals("Color overlap order is defined",sequential.getPixel(150,100),reversed.getPixel(150,100))}
            finally {sequential.recycle();reversed.recycle()}
        } finally {source.recycle();result.recycle();switched.recycle();disabled.recycle()}
    }

    @Test fun overlappingSofteningColorAndFineProtectionMatchFullPngAfterGeometry() = runBlocking {
        val w=321;val h=239
        val source=Bitmap.createBitmap(IntArray(w*h) {i ->
            val v=90+(i%w+i/w)%2*12
            Color.rgb(v,v+20,v+8)
        },w,h,Bitmap.Config.ARGB_8888)
        val project=ProjectIO.create(encode(source),w,h)
        val base=BaseCoverage(1,1,"ff")
        val region=Region(base=base,refinements=listOf(MaskStroke(listOf(MaskPoint(.5f,.5f)),2f/h,1f,true)))
        val regions=listOf(LocalRegion("face","脸部",LocalAdjustment(.2f,region,.6f,.7f,.1f,-.1f,.9f)),
            LocalRegion("clothes","衣服",LocalAdjustment(-.2f,circle(.5f),.3f,.9f,-.2f,.1f,1.1f)))
        try {
            for(turn in 0..3) for(flip in listOf(false,true)) for(angle in listOf(0f,13f)) {
                val (cw,ch)=Geometry.canvasSize(w,h,turn)
                val edit=Edit(turns=turn,flipHorizontal=flip,flipVertical=!flip,straighten=angle,crop=Crop(9,7,cw-20,ch-18),
                    sharpen=.4f,vignette=.2f,regions=regions)
                val p=project.withEdit(edit)
                val bytes=ByteArrayOutputStream().also {ProjectIO.write(p,it)}.toByteArray()
                val restored=ProjectIO.read(bytes.inputStream())
                assertEquals(edit,restored.manifest.edit);assertArrayEquals(project.original,restored.original)
                val full=ImagePipeline.render(source,restored.manifest.edit)
                val frame=DetailRenderer.render(restored,edit,MaskPoint(.5f,.5f),73,61)
                try {
                    var maximum=0;var total=0L
                    for(y in 0 until frame.bitmap.height) for(x in 0 until frame.bitmap.width) for(shift in listOf(0,8,16)) {
                        val a=full.getPixel(frame.bounds.left+x,frame.bounds.top+y)
                        val b=frame.bitmap.getPixel(x,y)
                        val d=kotlin.math.abs(((a ushr shift) and 255)-((b ushr shift) and 255))
                        maximum=maxOf(maximum,d);total+=d
                    }
                    assertTrue("turn=$turn flip=$flip angle=$angle max=$maximum",maximum<=if(angle==0f) 0 else 6)
                    assertTrue("Mean error",total.toDouble()/(73*61*3)<=.25)
                } finally {full.recycle();frame.bitmap.recycle()}
            }
        } finally {source.recycle()}
    }

    @Test fun oldSingleRegionPromotesWithoutChangingPixelsOrSource() = runBlocking {
        val source=Bitmap.createBitmap(IntArray(160*120) {i->Color.rgb(80+i%7,100+i%5,110+i%3)},160,120,Bitmap.Config.ARGB_8888)
        val single=Edit(exposureEv=.3f,local=LocalAdjustment(.6f,circle(.5f),.4f,.8f))
        val original=ImagePipeline.render(source,single)
        val promoted=ImagePipeline.render(source,single.addRegion("new","新选区"))
        try {assertTrue(original.sameAs(promoted))} finally {source.recycle();original.recycle();promoted.recycle()}
    }

    @Test fun twelveMegapixelFineMarkSurvivesPngExportAndOriginalResolutionDetail() = runBlocking {
        val w=4000;val h=3000;val x=2000;val y=1500
        val source=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(100,100,100))}
        val original=encode(source)
        val mark=MaskStroke(listOf(MaskPoint((x+.5f)/w,(y+.5f)/h)),.75f/h,1f)
        val edit=Edit(local=LocalAdjustment(1f,Region(refinements=listOf(mark))))
        val project=ProjectIO.create(original,w,h).withEdit(edit)
        val directory=File(context.cacheDir,"fine-export-${System.nanoTime()}").apply {mkdirs()}
        try {
            val png=File(directory,"fine.png")
            ProjectRepository(context,directory).exportPhoto(project,source,Uri.fromFile(png),ExportSettings(format=PhotoFormat.PNG))
            val result=BitmapFactory.decodeFile(png.path)
            val detail=DetailRenderer.render(project,edit,MaskPoint((x+.5f)/w,(y+.5f)/h),31,29)
            try {
                assertEquals(w,result.width);assertEquals(h,result.height)
                assertTrue(Color.red(result.getPixel(x,y))>130)
                assertEquals(Color.rgb(100,100,100),result.getPixel(x+1,y))
                for(yy in 0 until 29) for(xx in 0 until 31)
                    assertEquals(result.getPixel(detail.bounds.left+xx,detail.bounds.top+yy),detail.bitmap.getPixel(xx,yy))
                assertEquals(project.manifest.sourceSha256,ProjectIO.digest(original))
            } finally {result.recycle();detail.bitmap.recycle()}
        } finally {source.recycle();directory.deleteRecursively()}
    }

    @Test fun sessionCancelUndoAndReopenKeepAllOtherRegionsAndOriginal() = runBlocking {
        val directory=File(context.cacheDir,"multi-region-${System.nanoTime()}").apply {mkdirs()}
        val repository=ProjectRepository(context,directory)
        val source=Bitmap.createBitmap(300,200,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.GRAY)}
        val original=encode(source);source.recycle()
        val id=repository.library.create(ProjectIO.create(original,300,200))
        val store=ViewModelStore();lateinit var model: EditorViewModel
        suspend fun settled() {withTimeout(30000) {while(model.ui.value.busy || model.ui.value.rendering) delay(30)}}
        fun main(block: ()->Unit)=ins.runOnMainSync(block)
        try {
            main {model=EditorViewModel(context.applicationContext as Application,repository);store.put("editor",model)};settled()
            main {model.beginTool(EditorTool.LOCAL);model.updateDraft(Edit(regions=layers(),selectedRegionId="face"));model.localCheckpoint()};settled()
            main {model.cancelTool()};settled()
            assertTrue(repository.library.read(id).manifest.edit.regions.isEmpty())
            main {model.beginTool(EditorTool.LOCAL);model.updateDraft(Edit(regions=layers(),selectedRegionId="face"));model.localCheckpoint()};settled()
            main {model.selectRegion("clothes");model.renameRegion("服饰");model.enableRegion(false)};settled()
            val before=model.ui.value.draft!!
            main {model.resetRegionEffects()};settled()
            assertEquals(before.regions[0],model.ui.value.draft!!.regions[0]);assertEquals(before.regions[2],model.ui.value.draft!!.regions[2])
            main {model.localUndo()};settled();assertEquals(before,model.ui.value.draft)
            main {model.applyTool()};settled()
            withTimeout(20000) {while(repository.library.read(id).manifest.edit!=before) delay(30)}
            main {store.clear()}
            val saved=repository.library.read(id)
            assertArrayEquals(original,saved.original);assertEquals(before,saved.manifest.edit)
            main {model=EditorViewModel(context.applicationContext as Application,repository);store.put("editor",model)};settled()
            assertEquals(before,model.ui.value.project!!.manifest.edit)
            val archive=File(directory,"portable.nijiproj")
            repository.exportProject(saved,Uri.fromFile(archive))
            val (reopened,bitmap)=repository.open(Uri.fromFile(archive))
            try {
                assertEquals(before,reopened.manifest.edit);assertArrayEquals(original,reopened.original)
                val png=File(directory,"output.png")
                repository.exportPhoto(reopened,bitmap,Uri.fromFile(png),ExportSettings(format=PhotoFormat.PNG))
                val decoded=BitmapFactory.decodeFile(png.path);val reference=ImagePipeline.render(bitmap,before)
                try {assertTrue(reference.sameAs(decoded))} finally {decoded.recycle();reference.recycle()}
            } finally {bitmap.recycle()}
        } finally {main {store.clear()};directory.deleteRecursively()}
    }
}
