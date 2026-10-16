// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.nijiframe.editor.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class LocalRegionsUiTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private val device=UiDevice.getInstance(ins)
    private fun fresh() {if(android.os.Build.VERSION.SDK_INT>=34) ins.uiAutomation.clearCache()}
    private fun await(check: ()->Boolean) {
        val deadline=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<deadline) {if(runCatching(check).getOrDefault(false)) return;Thread.sleep(80)}
        assertTrue("Local region UI did not settle",check())
    }
    private fun node(label: String): UiObject2? {
        fresh();var value=device.findObject(By.desc(label)) ?: device.findObject(By.text(label)) ?: return null
        while(!value.isClickable) value=value.parent ?: return null
        return value
    }
    private fun tap(label: String) {await {node(label)?.isEnabled==true};node(label)!!.click();device.waitForIdle()}
    private fun rename(name: String) {
        tap("管理选区")
        val field=device.findObject(UiSelector().className("android.widget.EditText"));assertTrue(field.waitForExists(5000));field.setText(name)
        tap("保存名称")
    }
    private fun exact(label: String, value: String) {
        tap("全部参数");tap(label);tap("精确输入$label")
        val field=device.findObject(UiSelector().className("android.widget.EditText"));assertTrue(field.waitForExists(5000));field.setText(value)
        tap("使用此数值")
    }
    @Test fun realThreeRegionEditsCancelApplyRenameSwitchAndReopen() {
        val library=ProjectLibrary(File(context.filesDir,"library"));val previous=library.latest()
        val source=Bitmap.createBitmap(600,400,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(100,100,100))}
        val original=ByteArrayOutputStream().also {source.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray();source.recycle()
        val id=library.create(ProjectIO.create(original,600,400))
        var scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var model: EditorViewModel
        fun attach() {scenario.onActivity {model=ViewModelProvider(it)[EditorViewModel::class.java]}}
        fun settled()=await {!model.ui.value.busy && !model.ui.value.rendering}
        fun paint(x: Float) {
            tap("选区与修正");tap("添加区域");settled()
            fresh();val bounds=device.findObject(UiSelector().description("局部区域画布")).visibleBounds
            val width=minOf(bounds.width().toFloat(),bounds.height()*1.5f)
            device.click((bounds.centerX()+(x-.5f)*width).toInt(),bounds.centerY())
            await {model.ui.value.draft!!.activeLocal.region.strokes.isNotEmpty()};settled()
            tap("调整效果")
        }
        try {
            attach();settled();tap("修饰");tap("编辑选区");settled()
            rename("脸部");paint(.2f);exact("局部色温","35");settled()
            tap("新增选区");settled();rename("衣服");paint(.5f);exact("局部饱和度","-25");settled()
            tap("新增选区");settled();rename("背景");paint(.8f);exact("局部曝光","-0.5");settled()
            val completed=model.ui.value.draft!!
            assertEquals(listOf("脸部","衣服","背景"),completed.regions.map {it.name})
            assertEquals(.35f,completed.regions[0].adjustment.warmth,.0001f)
            assertEquals(.75f,completed.regions[1].adjustment.saturation,.0001f)
            assertEquals(-.5f,completed.regions[2].adjustment.exposureEv,.0001f)
            tap("切换选区");tap("选择选区 脸部");settled();tap("管理选区");tap("启用选区效果");tap("关闭");settled()
            assertFalse(model.ui.value.draft!!.regions[0].enabled)
            tap("撤销");await {model.ui.value.draft!!.regions[0].enabled};settled()
            assertEquals(completed.regions[0].id,model.ui.value.draft!!.selectedRegionId)
            tap("管理选区");tap("复位本区效果");tap("关闭")
            await {model.ui.value.draft!!.activeLocal.warmth==0f};settled()
            assertEquals(completed.regions[1],model.ui.value.draft!!.regions[1])
            assertEquals(completed.regions[2],model.ui.value.draft!!.regions[2])
            tap("撤销");await {model.ui.value.draft!!.activeLocal.warmth==.35f};settled()
            tap("管理选区");tap("删除选区");await {model.ui.value.draft!!.regions.size==2};settled()
            tap("撤销");await {model.ui.value.draft!!.regions.size==3};settled()
            assertEquals(completed.regions,model.ui.value.draft!!.regions)
            tap("新增选区");await {model.ui.value.draft!!.regions.size==4};settled()
            assertFalse("Region cap is visible without attempting a failing edit",node("新增选区")!!.isEnabled)
            tap("管理选区");tap("删除选区");await {model.ui.value.draft!!.regions.size==3};settled()
            tap("取消调整");settled();assertEquals(Edit(),library.read(id).manifest.edit)
            // A cancelled session never persists any region. Start a second real session.
            tap("修饰");tap("编辑选区");settled();rename("脸部");paint(.2f);exact("局部色温","35");settled()
            tap("新增选区");settled();rename("衣服");paint(.5f);exact("局部色调","20");settled()
            tap("新增选区");settled();rename("背景");paint(.8f);exact("局部曝光","-0.5");settled()
            val applied=model.ui.value.draft!!
            tap("应用调整");settled();await {library.read(id).manifest.edit==applied}
            scenario.close();scenario=ActivityScenario.launch(MainActivity::class.java);attach();settled()
            assertEquals(applied,model.ui.value.project!!.manifest.edit)
            assertArrayEquals(original,library.read(id).original)
            device.executeShellCommand("mkdir -p /sdcard/Download/nijiframe-test-evidence")
            device.executeShellCommand("screencap -p /sdcard/Download/nijiframe-test-evidence/local-regions-reopened.png")
        } catch(error: Throwable) {
            runCatching {device.dumpWindowHierarchy(System.out)}
            throw error
        } finally {
            scenario.close();library.delete(id);File(context.filesDir,"thumbnails/$id.jpg").delete()
            if(previous!=null && library.entries().any {it.id==previous}) library.markOpened(previous)
        }
    }
}
