// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.nijiframe.editor.*
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Exercise the production controls and persisted results using a synthetic image. */
class ModernEditorTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private val device=UiDevice.getInstance(ins)
    private fun await(check: ()->Boolean) {
        val end=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<end) {if(runCatching(check).getOrDefault(false)) return;Thread.sleep(80)}
        assertTrue("UI condition did not complete",check())
    }
    private fun node(label: String): UiObject2? {
        if(android.os.Build.VERSION.SDK_INT>=34) ins.uiAutomation.clearCache()
        var n=(device.findObject(By.desc(label)) ?: device.findObject(By.text(label))) ?: return null
        while(!n.isClickable) n=n.parent ?: return null
        return n
    }
    private fun tap(label: String) {await {node(label)?.isEnabled==true};requireNotNull(node(label)).click();device.waitForIdle()}
    private fun desc(label: String): UiObject {if(android.os.Build.VERSION.SDK_INT>=34) ins.uiAutomation.clearCache();return device.findObject(UiSelector().description(label))}
    private fun exact(label: String,value: String) {
        tap("精确输入$label")
        val field=device.findObject(UiSelector().className("android.widget.EditText"));assertTrue(field.waitForExists(5000));assertTrue(field.setText(value));device.waitForIdle()
        tap("使用此数值")
    }
    private fun evidence(name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/nijiframe-test-evidence")
        device.executeShellCommand("screencap -p /sdcard/Download/nijiframe-test-evidence/modern-$name.png")
    }
    private fun withEditor(large: Boolean=false,block: (EditorViewModel,ProjectLibrary,String,ActivityScenario<MainActivity>)->Unit) {
        val library=ProjectLibrary(File(context.filesDir,"library"));val previous=library.latest()
        val image=Bitmap.createBitmap(900,600,Bitmap.Config.ARGB_8888)
        for(y in 0 until 600) for(x in 0 until 900) image.setPixel(x,y,Color.rgb(x*255/900,y*255/600,128))
        val original=ByteArrayOutputStream().also {image.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();image.recycle()
        val bytes=if(large) ins.context.assets.open("formats/large-201mp.jpg").use {it.readBytes()} else original
        val info=ImagePipeline.inspect(bytes)
        val id=library.create(ProjectIO.create(bytes,info.width,info.height))
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: EditorViewModel
        scenario.onActivity {model=ViewModelProvider(it)[EditorViewModel::class.java]}
        val appearance=model.ui.value.appearance;val canvas=model.ui.value.canvasBackground
        val settings=model.ui.value.exportSettings
        try {
            await {!model.ui.value.busy && !model.ui.value.rendering && desc("编辑预览").exists()}
            block(model,library,id,scenario)
            assertArrayEquals(bytes,library.read(id).original)
        } catch(error: Throwable) {evidence("failure");runCatching {device.dumpWindowHierarchy(System.out)};throw error}
        finally {
            scenario.onActivity {model.setAppearance(appearance);model.setCanvasBackground(canvas);model.setExportSettings(settings)}
            scenario.close();library.delete(id);File(context.filesDir,"thumbnails/$id.jpg").delete()
            if(previous!=null) library.markOpened(previous)
        }
    }
    @Test fun exportPreflightOffersSmallerSizeAndDetailWithoutApplyingDraft() = withEditor(large=true) {model,library,id,scenario ->
        val initial=library.read(id).manifest
        scenario.onActivity {model.setExportSettings(ExportSettings())}
        tap("明暗");exact("曝光","0.35")
        await {!model.ui.value.rendering}
        val draft=model.ui.value.draft
        tap("导出")
        await {device.hasObject(By.textContains("当前内存预计不足"))}
        assertFalse(node("应用并导出")?.isEnabled ?: true)
        assertEquals(initial,library.read(id).manifest)
        val size="12288 × 16384 · 原尺寸"
        tap(size);tap("长边 1280")
        await {!device.hasObject(By.textContains("当前内存预计不足")) && node("应用并导出")?.isEnabled==true}
        assertEquals(draft,model.ui.value.draft)
        tap("查看原尺寸细节")
        await {model.ui.value.detail?.bitmap!=null && model.ui.value.detail?.loading==false}
        assertEquals(draft,model.ui.value.draft)
        assertEquals(initial,library.read(id).manifest)
        scenario.onActivity {model.closeDetail()}
        tap("导出");tap("可编辑工程")
        assertTrue(node("应用并导出")?.isEnabled==true)
        tap("取消")
        assertEquals(initial,library.read(id).manifest)
        assertEquals(draft,model.ui.value.draft)
    }
    @Test fun exactInputThemeAndDraftHistoryPreserveSavedProject() = withEditor {model,library,id,scenario ->
        val initial=library.read(id).manifest
        tap("明暗");tap("精确输入曝光")
        assertTrue(device.findObject(UiSelector().className("android.widget.EditText")).setText("8"));device.waitForIdle();tap("使用此数值")
        await {device.hasObject(By.text("请输入范围内的有效数值"))}
        assertEquals(0f,model.ui.value.draft!!.exposureEv,0f)
        assertTrue(device.findObject(UiSelector().className("android.widget.EditText")).setText("0.35"));device.waitForIdle()
        tap("切换正负");await {device.findObject(UiSelector().className("android.widget.EditText")).text=="-0.35"}
        assertEquals(0f,model.ui.value.draft!!.exposureEv,0f)
        tap("切换正负");tap("使用此数值")
        await {model.ui.value.draft!!.exposureEv>.34f && !model.ui.value.rendering}
        tap("全部参数");tap("对比度");exact("对比度","20")
        await {model.ui.value.draft!!.contrast>.19f}
        tap("撤销");await {model.ui.value.draft!!.contrast==0f}
        assertEquals(.35f,model.ui.value.draft!!.exposureEv,.0001f)
        tap("撤销");await {model.ui.value.draft!!.exposureEv==0f}
        assertFalse(model.ui.value.localCanUndo)
        tap("重做");tap("重做");await {!model.ui.value.rendering}
        val draft=model.ui.value.draft
        val preview=model.ui.value.preview
        tap("编辑菜单");tap("外观与查看");tap("中灰");tap("深色");tap("完成")
        await {model.ui.value.appearance==AppAppearance.DARK && model.ui.value.canvasBackground==CanvasBackground.MID}
        assertEquals(draft,model.ui.value.draft);assertSame(preview,model.ui.value.preview)
        assertEquals(initial,library.read(id).manifest);evidence("dark")
        scenario.recreate();await {desc("应用调整").exists()}
        assertEquals(draft,model.ui.value.draft)
        tap("编辑菜单");tap("外观与查看");tap("浅色");tap("完成")
        assertEquals(CanvasBackground.MID,model.ui.value.canvasBackground);evidence("light")
        tap("导出");await {device.hasObject(By.text("应用并导出"))};tap("取消")
        assertEquals(initial,library.read(id).manifest)
        device.pressBack();tap("继续编辑");assertEquals(draft,model.ui.value.draft)
        tap("应用调整");await {!model.ui.value.saving && model.ui.value.draft==null}
        assertEquals(draft,library.read(id).manifest.edit)
        tap("撤销");await {library.read(id).manifest.edit==initial.edit}
        tap("重做");await {library.read(id).manifest.edit==draft}
    }
    @Test fun relativeRulerDoesNotJumpAndNavigationDoesNotPaint() = withEditor {model,_,_,_ ->
        tap("明暗");exact("曝光","2.95")
        val ruler=desc("曝光调整").visibleBounds
        device.click(ruler.left+25,ruler.centerY());device.waitForIdle()
        assertEquals(2.95f,model.ui.value.draft!!.exposureEv,.0001f)
        device.swipe(ruler.centerX(),ruler.centerY(),ruler.left+20,ruler.centerY(),20);device.waitForIdle()
        await {model.ui.value.draft!!.exposureEv==3f}
        device.swipe(ruler.centerX(),ruler.centerY(),ruler.right-20,ruler.centerY(),20);device.waitForIdle()
        await {model.ui.value.draft!!.exposureEv<2.95f}
        tap("取消调整");tap("修饰");tap("编辑选区")
        assertEquals(RegionMode.MOVE,model.ui.value.regionMode)
        tap("添加区域");await {desc("局部区域画布").exists()}
        val canvas=desc("局部区域画布")
        assertTrue(canvas.pinchOut(50,20));device.waitForIdle()
        assertTrue(model.ui.value.draft!!.local.region.isEmpty)
        val bounds=canvas.visibleBounds
        device.click(bounds.centerX(),bounds.centerY());device.waitForIdle()
        await {model.ui.value.draft!!.local.region.strokes.size==1}
        tap("对比原图");await {model.ui.value.comparing && !desc("局部区域画布").exists()}
        device.click(bounds.centerX(),bounds.centerY());device.waitForIdle()
        assertEquals(1,model.ui.value.draft!!.local.region.strokes.size)
        tap("返回调整");tap("取消调整")
    }
    @Test fun shortWindowWithDoubleTextKeepsActionsAndExactInputReachable() {
        val scale=device.executeShellCommand("settings get system font_scale").trim()
        val priorSize=device.executeShellCommand("wm size").lineSequence().firstOrNull {it.startsWith("Override size:")}?.substringAfter(": ")?.trim()
        val priorDensity=device.executeShellCommand("wm density").lineSequence().firstOrNull {it.startsWith("Override density:")}?.substringAfter(": ")?.trim()
        try {
            device.executeShellCommand("wm size 720x1280")
            device.executeShellCommand("wm density 360")
            device.executeShellCommand("settings put system font_scale 2.0")
            withEditor {model,_,_,_ ->
                tap("明暗")
                val canvas=desc("编辑预览").visibleBounds
                assertTrue("Short window keeps photo space",canvas.width()>80 && canvas.height()>80)
                assertTrue(desc("应用调整").visibleBounds.height()>0)
                assertTrue(desc("取消调整").visibleBounds.height()>0)
                exact("曝光","0.35")
                await {model.ui.value.draft!!.exposureEv>.34f}
                evidence("short-double-text")
                tap("取消调整");assertNull(model.ui.value.draft)
            }
        } finally {
            device.executeShellCommand("settings put system font_scale ${if(scale=="null") "1.0" else scale}")
            device.executeShellCommand("wm size ${priorSize ?: "reset"}")
            device.executeShellCommand("wm density ${priorDensity ?: "reset"}")
        }
    }

}
