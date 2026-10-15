// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.nijiframe.editor.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class DetailRegionTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val base=BaseCoverage.from(MaskRaster(4,4,ByteArray(16) {255.toByte()}))

    @Test fun outdatedDetailGesturesAndClosedWindowCannotChangeTheDraft() = runBlocking {
        val root=File(context.cacheDir,"detail-stale-${System.nanoTime()}").apply {mkdirs()}
        val repository=ProjectRepository(context,root)
        val bitmap=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.GRAY)}
        val original=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        bitmap.recycle()
        val initial=Edit(local=LocalAdjustment(region=Region(base=base)))
        val id=repository.library.create(ProjectIO.create(original,800,600).withEdit(initial))
        val store=ViewModelStore()
        lateinit var model: EditorViewModel
        suspend fun settled() {withTimeout(20000) {while(model.ui.value.busy || model.ui.value.rendering) delay(20)}}
        suspend fun loaded() {withTimeout(20000) {while(model.ui.value.detail?.bitmap==null) delay(20)}}
        try {
            instrumentation.runOnMainSync {
                model=EditorViewModel(context.applicationContext as Application,repository);store.put("editor",model)
            }
            settled()
            instrumentation.runOnMainSync {model.beginTool(EditorTool.LOCAL)};settled()
            instrumentation.runOnMainSync {model.openDetail();model.loadDetail(160,120)};loaded()
            val stale=requireNotNull(model.ui.value.detail)
            instrumentation.runOnMainSync {
                model.detailCenter(MaskPoint(.7f,.6f))
                model.addDetailStroke(listOf(MaskPoint(.5f,.5f)),stale)
                assertEquals(initial,model.ui.value.draft)
                model.loadDetail(160,120)
            }
            loaded()
            instrumentation.runOnMainSync {
                model.regionMode(RegionMode.ERASE)
                model.addDetailStroke(listOf(MaskPoint(.7f,.6f)),requireNotNull(model.ui.value.detail))
                assertEquals(1,model.ui.value.draft!!.local.region.strokes.size)
                model.loadDetail(160,120);model.closeDetail()
            }
            settled()
            assertNull(model.ui.value.detail)
            instrumentation.runOnMainSync {model.cancelTool()};settled()
            assertEquals(initial,model.ui.value.project!!.manifest.edit)
            assertEquals(initial,repository.library.read(id).manifest.edit)
        } finally {instrumentation.runOnMainSync {store.clear()};root.deleteRecursively()}
    }

    @Test fun nativeOverlayFollowsRotationsFlipsCropAndStraightening() = runBlocking {
        for(turn in 0..3) for(flip in listOf(false,true)) for(angle in listOf(0f,13f)) {
            val (w,h)=Geometry.canvasSize(1200,800,turn)
            val region=Region(base=base,strokes=listOf(MaskStroke(listOf(MaskPoint(.5f,.5f)),.08f,1f,erase=true)))
            val edit=Edit(turns=turn,flipHorizontal=flip,flipVertical=!flip,straighten=angle,
                crop=Crop(40,40,w-80,h-80),local=LocalAdjustment(1f,region))
            val mapping=SourceGeometry(1200,800,edit)
            val center=mapping.toOutput(MaskPoint(.5f,.5f))
            val bounds=Rect(center.x.toInt()-100,center.y.toInt()-100,center.x.toInt()+100,center.y.toInt()+100)
            val overlay=DetailRenderer.regionOverlay(1200,800,edit,bounds)
            try {
                assertEquals(0,Color.alpha(overlay.getPixel(100,100)))
                assertEquals(110,Color.alpha(overlay.getPixel(0,0)))
                val reference=MaskRaster.create(region,1200,800)
                for(y in 0 until 200 step 7) for(x in 0 until 200 step 7) {
                    val p=requireNotNull(mapping.toSource(bounds.left+x+.5f,bounds.top+y+.5f))
                    assertEquals(reference.sample(p.x,p.y)*110/255,Color.alpha(overlay.getPixel(x,y)))
                }
            } finally {overlay.recycle()}
        }
    }

    @Test fun realDetailBrushPreservesBaseAndSupportsCancelUndoReopenAndExport() = runBlocking {
        val device=UiDevice.getInstance(instrumentation)
        fun fresh() {if(android.os.Build.VERSION.SDK_INT>=34) instrumentation.uiAutomation.clearCache()}
        fun desc(label: String): UiObject {fresh();return device.findObject(UiSelector().description(label))}
        fun text(label: String): UiObject {fresh();return device.findObject(UiSelector().text(label))}
        fun await(condition: ()->Boolean) {
            val end=System.currentTimeMillis()+25000
            while(System.currentTimeMillis()<end) {if(runCatching(condition).getOrDefault(false)) return;Thread.sleep(80)}
            assertTrue(condition())
        }
        fun actionable(label: String): UiObject2? {
            fresh();var node=device.findObject(By.text(label)) ?: return null
            while(!node.isClickable) node=node.parent ?: return null
            return node.takeIf {it.isEnabled}
        }
        fun tap(label: String) {await {actionable(label)!=null};requireNotNull(actionable(label)).click();device.waitForIdle()}
        fun ready(count: Int) {await {desc("细节区域画布").exists() && text("细节区域 · $count 笔").exists()}}
        fun nativeFrame(canvas: Rect): Rect {
            // API 29 can report a clipped accessibility rectangle. Locate the actual pixels
            // instead of assuming that rectangle and the native frame have the same center.
            val file=File(context.cacheDir,"detail-brush-screen.png")
            assertTrue(device.takeScreenshot(file))
            val screen=BitmapFactory.decodeFile(file.path)
            try {
                fun selected(x: Int,y: Int): Boolean {
                    val color=screen.getPixel(x,y)
                    return Color.red(color)>80 && Color.red(color)>Color.green(color)+25
                }
                val xs=(canvas.left until canvas.right).filter {selected(it,canvas.centerY())}
                val ys=(canvas.top until canvas.bottom).filter {selected(canvas.centerX(),it)}
                val frame=Rect(xs.first(),ys.first(),xs.last()+1,ys.last()+1)
                assertEquals(360,frame.width());assertEquals(520,frame.height())
                return frame
            } finally {screen.recycle();file.delete()}
        }
        val library=ProjectLibrary(File(context.filesDir,"library"));val previous=library.latest()
        val source=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(100,100,100))}
        val original=ByteArrayOutputStream().also {source.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        val initial=Edit(turns=1,flipHorizontal=true,flipVertical=true,straighten=9f,crop=Crop(20,40,360,520),
            local=LocalAdjustment(1f,Region(base=base)))
        val project=ProjectIO.create(original,600,400).withEdit(initial)
        val id=library.create(project)
        val root=File(context.cacheDir,"detail-brush-${System.nanoTime()}").apply {mkdirs()}
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            await {desc("编辑预览").exists()};tap("局部");tap("查看细节");ready(0)
            tap("细节擦除")
            var canvas=desc("细节区域画布").visibleBounds
            // The small native frame is centered, with blank space around it.
            device.click(canvas.left+2,canvas.centerY());device.waitForIdle();ready(0)
            device.swipe(canvas.centerX(),canvas.centerY(),canvas.left+2,canvas.centerY(),15)
            device.waitForIdle();ready(0)
            device.click(canvas.centerX()+30,canvas.centerY()-20);device.waitForIdle();ready(1)
            tap("撤销笔画");ready(0);tap("重做笔画");ready(1)
            tap("查看原图");await {desc("原尺寸细节图").exists() && !desc("细节区域画布").exists()}
            device.click(canvas.centerX(),canvas.centerY());device.waitForIdle()
            assertTrue(text("细节区域 · 1 笔").exists())
            tap("查看调整");ready(1)
            tap("关闭细节");tap("取消调整")
            assertEquals(initial,library.read(id).manifest.edit)
            tap("局部");tap("查看细节");ready(0);tap("细节擦除")
            canvas=desc("细节区域画布").visibleBounds
            val frameBounds=nativeFrame(canvas)
            device.click(frameBounds.left+210,frameBounds.top+240);device.waitForIdle();ready(1)
            tap("细节添加")
            device.click(frameBounds.left+110,frameBounds.top+320);device.waitForIdle();ready(2)
            scenario.recreate();ready(2)
            device.setOrientationLeft()
            await {device.displayWidth>device.displayHeight && desc("细节区域画布").exists()}
            assertTrue("Landscape retains a usable detail window",desc("细节区域画布").visibleBounds.height()>80)
            device.setOrientationNatural();ready(2)
            tap("关闭细节");tap("应用调整")
            await {library.read(id).manifest.edit.local.region.strokes.size==2}
            val saved=library.read(id)
            assertEquals(base,saved.manifest.edit.local.region.base)
            assertArrayEquals(original,saved.original)
            val strokes=saved.manifest.edit.local.region.strokes
            assertTrue(strokes[0].erase);assertFalse(strokes[1].erase)
            val expected=requireNotNull(SourceGeometry(600,400,initial).toSource(210f,240f))
            assertEquals("Inverse-mapped source X",expected.x,strokes[0].points.first().x,.003f)
            assertEquals("Inverse-mapped source Y",expected.y,strokes[0].points.first().y,.003f)
            val repository=ProjectRepository(context,root)
            val portable=File(root,"corrected.nijiproj")
            repository.exportProject(saved,Uri.fromFile(portable))
            val (reopened,decoded)=repository.open(Uri.fromFile(portable))
            try {
                assertEquals(saved.manifest.edit,reopened.manifest.edit)
                assertArrayEquals(original,reopened.original)
                val png=File(root,"result.png")
                repository.exportPhoto(reopened,decoded,Uri.fromFile(png),ExportSettings(format=PhotoFormat.PNG))
                val result=BitmapFactory.decodeFile(png.path)
                val frame=DetailRenderer.render(reopened,reopened.manifest.edit,MaskPoint(.5f,.5f),360,520)
                try {
                    assertEquals(100,Color.red(result.getPixel(210,240)))
                    assertEquals(100,Color.red(frame.bitmap.getPixel(210,240)))
                    assertTrue(Color.red(result.getPixel(110,320))>125)
                } finally {result.recycle();frame.bitmap.recycle()}
            } finally {decoded.recycle()}
        } catch(error: Throwable) {
            // App UID cannot write directly to arbitrary Downloads paths on every supported API.
            // A secondary evidence failure must never replace the assertion that caused it.
            android.util.Log.e("DetailRegionTest","Detail correction assertion failed",error)
            runCatching {
                device.executeShellCommand("mkdir -p /sdcard/Download/nijiframe-test-evidence")
                device.dumpWindowHierarchy(File(context.cacheDir,"detail-brush-failure.xml"))
                device.executeShellCommand("sh -c 'run-as dev.nijiframe.app cat cache/detail-brush-failure.xml > /sdcard/Download/nijiframe-test-evidence/detail-brush-failure.xml'")
                device.executeShellCommand("screencap -p /sdcard/Download/nijiframe-test-evidence/detail-brush-failure.png")
            }.onFailure {error.addSuppressed(it)}
            throw error
        } finally {
            device.setOrientationNatural();device.unfreezeRotation()
            scenario.close();source.recycle();library.delete(id);root.deleteRecursively()
            if(previous!=null && library.entries().any {it.id==previous}) library.markOpened(previous)
        }
    }
}
