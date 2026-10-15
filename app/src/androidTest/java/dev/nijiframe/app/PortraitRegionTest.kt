// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import dev.nijiframe.editor.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PortraitRegionTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun encode(bitmap: Bitmap)=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
    @Test fun realOfflineInferenceRejectsNoFaceWithoutChangingDraft() = runBlocking {
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.INTERNET))
        val info=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
        assertFalse(info.services.orEmpty().any {it.name.startsWith("com.google.android.datatransport")})
        assertFalse(info.receivers.orEmpty().any {it.name.startsWith("com.google.android.datatransport")})
        val input=Bitmap.createBitmap(256,192,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.GRAY)}
        val root=File(context.cacheDir,"region-failure-${System.nanoTime()}").apply {mkdirs()}
        val repository=ProjectRepository(context,root)
        val edit=Edit(exposureEv=.25f,local=LocalAdjustment(.3f,Region(listOf(MaskStroke(listOf(MaskPoint(.5f,.5f)))))))
        val id=repository.library.create(ProjectIO.create(encode(input),256,192).withEdit(edit.copy(exposureEv=0f)))
        val store=androidx.lifecycle.ViewModelStore()
        lateinit var model: EditorViewModel
        try {
            instrumentation.runOnMainSync {model=EditorViewModel(context.applicationContext as Application,repository);store.put("test",model)}
            withTimeout(60_000) {while(model.ui.value.busy || model.ui.value.rendering) delay(20)}
            instrumentation.runOnMainSync {model.commit(edit);model.beginTool(EditorTool.LOCAL);model.suggestRegion()}
            withTimeout(60_000) {while(model.ui.value.busy) delay(20)}
            assertTrue(model.ui.value.error.orEmpty().contains("未找到清晰人脸"))
            assertNull(model.ui.value.suggestion)
            assertEquals(edit,model.ui.value.draft)
            assertEquals(edit,repository.library.read(id).manifest.edit)
            instrumentation.runOnMainSync {model.dismissError();model.suggestRegion();model.cancelOperation()}
            withTimeout(60_000) {while(model.ui.value.busy) delay(20)}
            assertNull(model.ui.value.suggestion);assertEquals(edit,model.ui.value.draft)
            assertEquals(edit,repository.library.read(id).manifest.edit)
        } finally {instrumentation.runOnMainSync {store.clear()};input.recycle();root.deleteRecursively()}
    }
    @Test fun fallbackRejectsNonFacesAndCancellationRetainsCallerBitmap() {
        val random=java.util.Random(4242)
        for(kind in 0..1) {
            val pixels=IntArray(256*384) {i ->
                if(kind==0) {if((i%256/16+i/256/16)%2==0) Color.WHITE else Color.BLACK}
                else Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256))
            }
            val bitmap=Bitmap.createBitmap(pixels,256,384,Bitmap.Config.ARGB_8888)
            try {
                val error=assertThrows(IllegalArgumentException::class.java) {PortraitRegion.suggest(context,bitmap)}
                assertTrue(error.message.orEmpty().contains("未找到清晰人脸"))
                assertFalse(bitmap.isRecycled)
                var checks=0
                assertThrows(CancellationException::class.java) {
                    PortraitRegion.suggest(context,bitmap) {if(++checks>=6) throw CancellationException("cancel during fallback")}
                }
                assertFalse(bitmap.isRecycled)
            } finally {bitmap.recycle()}
        }
    }
    @Test fun baseCoverageSurvivesDraftCorrectionSaveExportAndDetail() = runBlocking {
        val input=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(100,110,120))}
        val region=Region(base=BaseCoverage(3,2,"ffff00ffff00"))
        val root=File(context.cacheDir,"region-roundtrip-${System.nanoTime()}").apply {mkdirs()}
        val repository=ProjectRepository(context,root)
        val id=repository.library.create(ProjectIO.create(encode(input),240,160))
        val store=androidx.lifecycle.ViewModelStore()
        lateinit var model: EditorViewModel
        try {
            instrumentation.runOnMainSync {model=EditorViewModel(context.applicationContext as Application,repository);store.put("test",model)}
            withTimeout(20_000) {while(model.ui.value.busy || model.ui.value.rendering) delay(20)}
            instrumentation.runOnMainSync {
                model.beginTool(EditorTool.LOCAL)
                model.updateDraft(Edit(turns=1,flipHorizontal=true,local=LocalAdjustment(.8f,region,.3f)))
                model.localCheckpoint();model.regionMode(RegionMode.ERASE)
                model.addStroke(listOf(MaskPoint(.25f,.5f)))
                assertEquals(region.base,model.ui.value.draft!!.local.region.base)
                model.localUndo();assertEquals(region,model.ui.value.draft!!.local.region)
                model.localRedo();model.applyTool()
            }
            withTimeout(20_000) {while(model.ui.value.saving || model.ui.value.rendering) delay(20)}
            val saved=repository.library.read(id)
            assertEquals(region.base,saved.manifest.edit.local.region.base)
            val bytes=ByteArrayOutputStream().also {ProjectIO.write(saved,it)}.toByteArray()
            val reopened=ProjectIO.read(bytes.inputStream())
            val full=ImagePipeline.render(input,reopened.manifest.edit)
            val frame=DetailRenderer.render(reopened,reopened.manifest.edit,MaskPoint(.5f,.5f),64,64)
            try {
                val expected=Bitmap.createBitmap(full,frame.bounds.left,frame.bounds.top,64,64)
                assertTrue(expected.sameAs(frame.bitmap));expected.recycle()
                val geometry=SourceGeometry(240,160,reopened.manifest.edit)
                val erased=geometry.toOutput(MaskPoint(.25f,.5f))
                val selected=geometry.toOutput(MaskPoint(.1f,.5f))
                assertTrue(Color.red(full.getPixel(erased.x.toInt(),erased.y.toInt()))<130)
                assertTrue(Color.red(full.getPixel(selected.x.toInt(),selected.y.toInt()))>120)
                val png=ByteArrayOutputStream().also {full.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
                val decoded=ImagePipeline.decode(png)
                assertTrue(full.sameAs(decoded));decoded.recycle()
            } finally {full.recycle();frame.bitmap.recycle()}
        } finally {instrumentation.runOnMainSync {store.clear()};input.recycle();root.deleteRecursively()}
    }
}
