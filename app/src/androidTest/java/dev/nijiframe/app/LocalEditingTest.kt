// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.nijiframe.editor.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.*

class LocalEditingTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun source(w: Int=400,h: Int=200)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(100,100,100))}
    private fun bytes(bitmap: Bitmap)=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
    private val region=Region(listOf(MaskStroke(listOf(MaskPoint(.35f,.5f)),.1f,1f)))

    @Test fun regionFollowsGeometryAndPortableExportAfterSourceDeletion() = runBlocking {
        val root=File(context.cacheDir,"local-${System.nanoTime()}").apply {mkdirs()}
        val repository=ProjectRepository(context,root)
        val bitmap=source();val original=bytes(bitmap)
        try {
            for(turn in 0..3) for(flip in listOf(false,true)) for(angle in listOf(0f,17f)) {
                val (w,h)=Geometry.canvasSize(bitmap.width,bitmap.height,turn)
                val edit=Edit(turns=turn,flipHorizontal=flip,straighten=angle,crop=Crop(w/10,h/10,w*8/10,h*8/10),local=LocalAdjustment(1f,region))
                val p=ProjectIO.create(original,400,200).withEdit(edit)
                val id=repository.library.create(p)
                val portable=File(root,"portable.nijiproj")
                repository.exportProject(p,Uri.fromFile(portable))
                repository.library.delete(id)
                val (loaded,decoded)=repository.open(Uri.fromFile(portable))
                assertEquals(edit,loaded.manifest.edit);assertArrayEquals(original,loaded.original)
                val png=File(root,"out.png")
                repository.exportPhoto(loaded,decoded,Uri.fromFile(png),ExportSettings(format=PhotoFormat.PNG))
                val result=BitmapFactory.decodeFile(png.path)
                val expected=ImagePipeline.render(bitmap,edit)
                assertTrue(result.sameAs(expected))
                val at=SourceGeometry(400,200,edit).toOutput(MaskPoint(.35f,.5f))
                assertTrue(Color.red(result.getPixel(at.x.toInt(),at.y.toInt()))>125)
                val outside=SourceGeometry(400,200,edit).toOutput(MaskPoint(.65f,.5f))
                assertEquals(100,Color.red(result.getPixel(outside.x.toInt(),outside.y.toInt())))
                val overlay=ImagePipeline.regionOverlay(400,200,edit)
                assertTrue(Color.alpha(overlay.getPixel(at.x.toInt(),at.y.toInt()))>80)
                assertEquals(0,Color.alpha(overlay.getPixel(outside.x.toInt(),outside.y.toInt())))
                overlay.recycle();decoded.recycle();result.recycle();expected.recycle()
            }
        } finally {bitmap.recycle();root.deleteRecursively()}
    }

    @Test fun approximatePreviewSettlesToReferenceAndLocalDraftRestores() = runBlocking {
        val root=File(context.cacheDir,"preview-${System.nanoTime()}").apply {mkdirs()}
        val repository=ProjectRepository(context,root);val source=source(1800,1000)
        val edit=Edit(local=LocalAdjustment(.8f,region))
        val id=repository.library.create(ProjectIO.create(bytes(source),1800,1000).withEdit(edit))
        var model: EditorViewModel?=null
        try {
            instrumentation.runOnMainSync {model=EditorViewModel(context.applicationContext as Application,repository)}
            withTimeout(20_000) {while(requireNotNull(model).ui.value.busy || model!!.ui.value.rendering) delay(20)}
            assertFalse(model!!.ui.value.approximatePreview)
            val reference=ImagePipeline.preview(ImagePipeline.render(source,edit))
            assertTrue(reference.sameAs(model!!.ui.value.preview));reference.recycle()
            instrumentation.runOnMainSync {
                model!!.beginTool(EditorTool.LOCAL)
                model!!.updateDraft(edit.copy(local=edit.local.copy(exposureEv=1.2f)))
                model!!.localCheckpoint();model!!.localUndo()
                assertEquals(.8f,model!!.ui.value.draft!!.local.exposureEv)
                model!!.localRedo();model!!.applyTool()
            }
            withTimeout(20_000) {while(model!!.ui.value.saving || model!!.ui.value.rendering) delay(20)}
            assertEquals(1.2f,repository.library.read(id).manifest.edit.local.exposureEv)
            instrumentation.runOnMainSync {model!!.home()}
            withTimeout(20_000) {while(model!!.ui.value.busy) delay(20)}
            instrumentation.runOnMainSync {model=EditorViewModel(context.applicationContext as Application,repository)}
            withTimeout(20_000) {while(model!!.ui.value.busy || model!!.ui.value.rendering) delay(20)}
            assertEquals(region,model!!.ui.value.project!!.manifest.edit.local.region)
            assertEquals(1.2f,model!!.ui.value.project!!.manifest.edit.local.exposureEv)
        } finally {
            instrumentation.runOnMainSync {model?.home()}
            withTimeout(20_000) {while(model?.ui?.value?.busy==true) delay(20)}
            source.recycle();root.deleteRecursively()
        }
    }

    @Test fun realBrushEraseUndoCancelApplyAndReopen() {
        val device=UiDevice.getInstance(instrumentation)
        fun fresh() {if(android.os.Build.VERSION.SDK_INT>=34) instrumentation.uiAutomation.clearCache()}
        fun find(text: String): UiObject {fresh();return device.findObject(UiSelector().text(text)).also {check(it.waitForExists(10000)){"Missing $text"}}}
        fun tap(text: String) {find(text).click();device.waitForIdle();fresh()}
        fun desc(label: String): UiObject {fresh();return device.findObject(UiSelector().description(label))}
        fun await(check: ()->Boolean) {val end=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<end){if(runCatching(check).getOrDefault(false))return;Thread.sleep(100)};assertTrue(check())}
        fun scrollTo(label: String) {
            repeat(5) {
                val obj=desc(label)
                if(obj.exists() && !obj.visibleBounds.isEmpty) return
                device.swipe(device.displayWidth/2,device.displayHeight*84/100,device.displayWidth/2,device.displayHeight*72/100,20)
                device.waitForIdle()
            }
            assertTrue("Missing slider $label",desc(label).exists())
        }
        val library=ProjectLibrary(File(context.filesDir,"library"));val previous=library.latest()
        val bitmap=source(900,600)
        val initial=Edit(turns=1,flipHorizontal=true,straighten=12f,crop=Crop(40,80,520,740))
        val project=ProjectIO.create(bytes(bitmap),900,600).withEdit(initial)
        val id=library.create(project.copy(manifest=project.manifest.copy(name="Local test")));bitmap.recycle()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            await {desc("编辑预览").exists()};tap("局部")
            await {desc("局部区域画布").exists()}
            val bounds=desc("局部区域画布").visibleBounds
            device.swipe(bounds.centerX(),bounds.centerY(),bounds.centerX()+30,bounds.centerY()+30,12);device.waitForIdle()
            await {find("显示区域 · 1 笔").exists()}
            tap("撤销")
            await {find("显示区域 · 0 笔").exists()}
            tap("重做")
            scrollTo("局部曝光调整")
            val slider=desc("局部曝光调整").visibleBounds
            device.swipe(slider.centerX(),slider.centerY(),slider.left+slider.width()*3/4,slider.centerY(),15)
            device.waitForIdle()
            tap("柔肤（实验）")
            scrollTo("柔肤强度调整")
            val soften=desc("柔肤强度调整").visibleBounds
            device.swipe(soften.left+8,soften.centerY(),soften.centerX(),soften.centerY(),15)
            device.waitForIdle();tap("应用调整")
            await {library.read(id).manifest.edit.local.exposureEv>.5f}
            val saved=library.read(id).manifest.edit
            assertTrue(saved.local.soften>.1f)
            assertEquals(1,saved.local.region.strokes.size)
            val expectedPoint=requireNotNull(SourceGeometry(900,600,initial).toSource(260f,370f))
            val actualPoint=saved.local.region.strokes.single().points.first()
            assertEquals(expectedPoint.x,actualPoint.x,.015f);assertEquals(expectedPoint.y,actualPoint.y,.015f)
            tap("撤销");await {library.read(id).manifest.edit.local.region.strokes.isEmpty()}
            tap("重做");await {library.read(id).manifest.edit==saved}
            tap("局部")
            tap("移动画布")
            // Hiding the overlay also exposes the photo's accessibility node underneath it.
            desc("显示区域").click();device.waitForIdle()
            await {desc("编辑预览").exists()}
            desc("编辑预览").pinchOut(40,12)
            device.waitForIdle()
            tap("擦除区域")
            await {desc("局部区域画布").exists()}
            val canvas=desc("局部区域画布").visibleBounds
            device.click(canvas.centerX(),canvas.centerY());device.waitForIdle()
            tap("取消调整")
            assertEquals(saved,library.read(id).manifest.edit)
            tap("项目");tap("Local test")
            await {desc("编辑预览").exists()}
            assertEquals(saved,library.read(id).manifest.edit)
            device.executeShellCommand("mkdir -p /sdcard/Download/nijiframe-test-evidence")
            device.executeShellCommand("screencap -p /sdcard/Download/nijiframe-test-evidence/local-editor.png")
        } finally {
            scenario.close();library.delete(id);previous?.let {library.markOpened(it)}
        }
    }
}
