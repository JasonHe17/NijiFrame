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

class EditorExperienceTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private val device=UiDevice.getInstance(ins)
    private fun fresh() {if(android.os.Build.VERSION.SDK_INT>=34) ins.uiAutomation.clearCache()}
    private fun await(check: () -> Boolean) {
        val end=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<end) {if(runCatching(check).getOrDefault(false)) return;Thread.sleep(80)}
        assertTrue("UI condition did not complete",check())
    }
    private fun node(text: String): UiObject2? {
        fresh();var n=device.findObject(By.text(text)) ?: return null
        while(!n.isClickable) n=n.parent ?: return null
        return n
    }
    private fun tap(text: String) {await {node(text)?.isEnabled==true};requireNotNull(node(text)).click();device.waitForIdle()}
    private fun desc(label: String): UiObject {fresh();return device.findObject(UiSelector().description(label))}
    private fun scroll(label: String, control: String, description: Boolean=false) {
        fresh()
        val target=if(description) UiSelector().description(label) else UiSelector().text(label)
        assertTrue("Cannot reach $label",UiScrollable(UiSelector().description(control)).scrollIntoView(target))
        device.waitForIdle();fresh()
    }
    private fun slider(label: String, fraction: Float) {
        val b=desc(label).visibleBounds
        assertTrue("Usable slider $label",b.width()>80 && b.height()>0)
        device.swipe(b.centerX(),b.centerY(),(b.left+b.width()*fraction).toInt(),b.centerY(),20)
        device.waitForIdle()
    }
    private fun evidence(name: String) {
        device.executeShellCommand("mkdir -p /sdcard/Download/nijiframe-test-evidence")
        device.executeShellCommand("screencap -p /sdcard/Download/nijiframe-test-evidence/experience-$name.png")
    }
    private fun withEditor(block: (EditorViewModel,ProjectLibrary,String,ActivityScenario<MainActivity>) -> Unit) {
        val library=ProjectLibrary(File(context.filesDir,"library"));val previous=library.latest()
        val bitmap=Bitmap.createBitmap(900,600,Bitmap.Config.ARGB_8888)
        for(y in 0 until 600) for(x in 0 until 900) bitmap.setPixel(x,y,Color.rgb(x*255/900,y*255/600,128))
        val bytes=ByteArrayOutputStream().also {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();bitmap.recycle()
        val project=ProjectIO.create(bytes,900,600)
        val id=library.create(project.copy(manifest=project.manifest.copy(name="Experience test")))
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: EditorViewModel
        scenario.onActivity {model=ViewModelProvider(it)[EditorViewModel::class.java]}
        try {
            await {!model.ui.value.busy && !model.ui.value.rendering && desc("编辑预览").exists()}
            block(model,library,id,scenario)
        } catch(error: Throwable) {evidence("failure");runCatching {device.dumpWindowHierarchy(System.out)};throw error}
        finally {
            scenario.close();library.delete(id)
            File(context.filesDir,"thumbnails/$id.jpg").delete()
            if(previous!=null) library.markOpened(previous)
        }
    }

    @Test fun exportSettingsCancellationPreservesDraftAndExplainsSavedState() = withEditor { model,library,id,_ ->
        val initial=library.read(id)
        evidence("overview")
        tap("明暗");await {model.ui.value.tool==EditorTool.LIGHT}
        slider("曝光调整",.75f)
        await {model.ui.value.draft!!.exposureEv>.5f && !model.ui.value.rendering}
        val draft=model.ui.value.draft
        tap("导出");await {device.hasObject(By.text("导出与分享"))}
        assertEquals(draft,model.ui.value.draft)
        assertEquals(initial.manifest,library.read(id).manifest)
        tap("取消");await {!device.hasObject(By.text("导出与分享"))}
        assertEquals(draft,model.ui.value.draft)
        desc("工程与照片信息").click();device.waitForIdle()
        await {device.hasObject(By.textStartsWith("当前工具还有草稿"))}
        assertTrue(device.hasObject(By.textStartsWith("工程：已应用的修改已保存")))
        tap("关闭");tap("应用调整")
        await {model.ui.value.draft==null && !model.ui.value.saving && !model.ui.value.dirty}
        assertEquals(draft,library.read(id).manifest.edit)
        assertArrayEquals(initial.original,library.read(id).original)
        await {fresh();device.hasObject(By.text("已保存"))}
    }

    @Test fun portraitStepsShareBrushControlsAndKeepComparisonReadOnly() = withEditor { model,library,id,scenario ->
        val initial=library.read(id)
        tap("人像修图");await {model.ui.value.tool==EditorTool.LOCAL && !model.ui.value.rendering && desc("局部区域画布").exists()}
        tap("调整效果");await {model.ui.value.regionMode==RegionMode.MOVE && !model.ui.value.showRegion}
        // StateFlow publication can precede Compose and accessibility updates.
        // Wait for the rendered mode as well; never repeat the user action.
        await {node("调整效果")?.isChecked==true && !desc("局部区域画布").exists()}
        tap("选区与修正");await {model.ui.value.regionMode==RegionMode.ADD && model.ui.value.showRegion &&
            node("选区与修正")?.isChecked==true && desc("局部区域画布").exists()}
        val canvas=desc("局部区域画布").visibleBounds
        device.click(canvas.centerX(),canvas.centerY())
        await {model.ui.value.draft!!.local.region.strokes.size==1 && !model.ui.value.rendering}
        scroll("区域笔刷大小","工具设置",true);slider("区域笔刷大小",.2f)
        val radius=model.ui.value.brushRadius
        scroll("边缘硬度","工具设置");tap("边缘硬度")
        scroll("区域边缘硬度","工具设置",true);slider("区域边缘硬度",.8f)
        await {model.ui.value.brushHardness>.6f}
        val hardness=model.ui.value.brushHardness
        tap("查看细节");await {model.ui.value.detail?.bitmap!=null && model.ui.value.detail?.loading==false}
        evidence("detail")
        assertEquals(radius,model.ui.value.brushRadius)
        assertEquals(hardness,model.ui.value.brushHardness)
        scroll("边缘硬度","细节控制");tap("边缘硬度")
        scroll("区域边缘硬度","细节控制",true);slider("区域边缘硬度",.3f)
        await {model.ui.value.brushHardness<.45f}
        val revisedHardness=model.ui.value.brushHardness
        tap("查看原图");await {model.ui.value.detail?.comparing==true && model.ui.value.detail?.loading==false}
        await {!desc("细节区域画布").exists() && desc("原尺寸细节图").exists()}
        val original=desc("原尺寸细节图").visibleBounds
        device.click(original.centerX(),original.centerY());device.waitForIdle()
        assertEquals(1,model.ui.value.draft!!.local.region.strokes.size)
        tap("关闭细节")
        assertEquals(revisedHardness,model.ui.value.brushHardness)
        scroll("调整效果","工具设置");tap("调整效果")
        await {model.ui.value.regionMode==RegionMode.MOVE && !model.ui.value.showRegion}
        evidence("effects")
        scenario.recreate()
        await {node("调整效果")?.isChecked==true}
        assertFalse(model.ui.value.showRegion)
        tap("取消调整");await {model.ui.value.draft==null}
        assertEquals(initial.manifest,library.read(id).manifest)
        assertArrayEquals(initial.original,library.read(id).original)
    }
    @Test fun largeTextLandscapeKeepsCanvasAndToolActionsReachable() {
        val scale=device.executeShellCommand("settings get system font_scale").trim()
        try {
            device.executeShellCommand("settings put system font_scale 1.5")
            withEditor {model,library,id,_ ->
                // Launch the editor before rotation: the system launcher may lock portrait.
                device.setOrientationLeft()
                await {device.displayWidth>device.displayHeight}
                val initial=library.read(id).manifest
                tap("人像修图");await {model.ui.value.tool==EditorTool.LOCAL && !model.ui.value.rendering && desc("局部区域画布").exists()}
                val canvas=desc("局部区域画布").visibleBounds
                assertTrue("Landscape retains a usable canvas",canvas.width()>80 && canvas.height()>80)
                assertTrue(requireNotNull(node("应用调整")).visibleBounds.height()>0)
                assertTrue(requireNotNull(node("取消调整")).visibleBounds.height()>0)
                tap("调整效果");await {model.ui.value.regionMode==RegionMode.MOVE}
                scroll("局部曝光调整","工具设置",true)
                evidence("large-text-landscape")
                tap("取消调整");await {model.ui.value.draft==null}
                assertEquals(initial,library.read(id).manifest)
            }
        } finally {
            if(scale=="null") device.executeShellCommand("settings delete system font_scale")
            else device.executeShellCommand("settings put system font_scale $scale")
            device.setOrientationNatural();device.unfreezeRotation()
        }
    }

}
