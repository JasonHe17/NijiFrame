// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.MediaStore
import android.util.Base64
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiSelector
import dev.nijiframe.editor.ProjectLibrary
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real accessibility/touch interaction, including the system document picker. No fake activity results. */
class EditorUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val device=UiDevice.getInstance(instrumentation)
    private fun freshTree() { if(android.os.Build.VERSION.SDK_INT>=34) instrumentation.uiAutomation.clearCache() }
    private fun text(value: String): UiObject {
        freshTree()
        return device.findObject(UiSelector().text(value)).also {check(it.waitForExists(10_000)) {"Missing UI text: $value"} }
    }
    private fun tap(value: String) {text(value).click();device.waitForIdle();freshTree()}
    private fun description(value: String): UiObject {
        freshTree()
        return device.findObject(UiSelector().description(value)).also {check(it.waitForExists(10_000)) {"Missing description: $value"} }
    }
    private fun await(condition: ()->Boolean) {
        val deadline=System.currentTimeMillis()+15_000
        while(System.currentTimeMillis()<deadline) {
            if(runCatching(condition).getOrDefault(false)) return
            Thread.sleep(100)
        }
        assertTrue("Condition did not become true",condition())
    }
    private fun moveSlider(label: String,fraction: Float) {
        val bounds=description(label).visibleBounds
        device.swipe(bounds.centerX(),bounds.centerY(),(bounds.left+bounds.width()*fraction).toInt(),bounds.centerY(),20)
        device.waitForIdle()
    }

    @Test fun pickerToolsAutosaveCropExportShareAndProjectManagement() {
        val stamp=System.nanoTime().toString()
        val name="niji-ui-$stamp"
        val resolver=context.contentResolver
        val library=ProjectLibrary(File(context.filesDir,"library"))
        val previous=library.latest()
        val uri=requireNotNull(resolver.insert(MediaStore.Files.getContentUri("external"),ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Download")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }))
        val fixture=Bitmap.createBitmap(960,640,Bitmap.Config.ARGB_8888)
        for(y in 0 until 640) for(x in 0 until 960) fixture.setPixel(x,y,Color.rgb(x*255/960,y*255/640,128))
        resolver.openOutputStream(uri)!!.use { fixture.compress(Bitmap.CompressFormat.PNG,100,it) };fixture.recycle()
        resolver.update(uri,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
        val exportName="niji-export-$stamp.png"
        var scenario: ActivityScenario<MainActivity>?=null
        try {
            scenario=ActivityScenario.launch(MainActivity::class.java)
            await {device.hasObject(By.text("导入照片")) || device.hasObject(By.text("项目"))}
            if(device.hasObject(By.text("项目"))) tap("项目")
            tap("导入照片")
            description("Show roots").click();device.waitForIdle()
            device.findObject(UiSelector().resourceId("android:id/title").text("Downloads")).click()
            device.waitForIdle()
            await {device.hasObject(By.text("$name.png")) || device.hasObject(By.descStartsWith("$name.png,"))}
            if(device.findObject(UiSelector().text("$name.png")).exists()) device.findObject(UiSelector().text("$name.png")).click()
            else device.findObject(UiSelector().descriptionStartsWith("$name.png,")).click()
            text(name)
            await {library.entries().any {it.name==name}}
            val id=library.entries().first {it.name==name}.id
            val canvas=description("编辑预览").visibleBounds
            val sampleX=canvas.left+canvas.width()/4
            val sampleY=canvas.centerY()
            fun sample(): Int = requireNotNull(instrumentation.uiAutomation.takeScreenshot()).let { screenshot ->
                Color.red(screenshot.getPixel(sampleX,sampleY)).also {screenshot.recycle()}
            }
            val beforeZoom=sample()
            assertTrue(description("编辑预览").pinchOut(70,25));device.waitForIdle()
            val zoomed=sample()
            assertTrue("Pinch must change the visible image",kotlin.math.abs(zoomed-beforeZoom)>5)
            device.swipe(canvas.centerX(),canvas.centerY(),canvas.centerX()+120,canvas.centerY(),20)
            device.waitForIdle()
            assertTrue("Dragging a zoomed image must pan",kotlin.math.abs(sample()-zoomed)>2)
            tap("适应画布")
            assertTrue(kotlin.math.abs(sample()-beforeZoom)<=2)
            tap("明暗");moveSlider("曝光调整",.75f)
            text("调整待应用")
            tap("取消调整")
            assertEquals(0f,library.read(id).manifest.edit.exposureEv)
            tap("明暗");moveSlider("曝光调整",.75f)
            // Activity recreation retains an in-progress tool, without persisting an uncommitted draft.
            scenario.recreate();text("调整待应用");text("应用调整")
            tap("应用调整")
            await {library.read(id).manifest.edit.exposureEv>.5f}
            tap("撤销");await {library.read(id).manifest.edit.exposureEv==0f}
            tap("重做");await {library.read(id).manifest.edit.exposureEv>.5f}
            tap("裁剪");tap("1:1")
            val bounds=description("裁剪框，拖动四角或框内调整").visibleBounds
            val imageHeight=bounds.width()*640f/960
            val bottom=bounds.exactCenterY()+imageHeight/2
            val right=bounds.left+bounds.width()*800f/960
            device.swipe(right.toInt()-2,bottom.toInt()-2,right.toInt()-100,bottom.toInt()-100,25)
            tap("应用调整")
            await {library.read(id).manifest.edit.crop!=null}
            val crop=library.read(id).manifest.edit.crop!!
            assertTrue("Crop drag must resize the selection",crop.width<640)
            assertTrue(kotlin.math.abs(crop.width-crop.height)<=1)
            tap("旋转");tap("水平翻转");tap("应用调整")
            await {library.read(id).manifest.edit.flipHorizontal}
            tap("细节");moveSlider("锐化调整",.6f);device.pressBack()
            text("当前工具还有未完成的调整");tap("放弃本次调整")
            tap(name);assertEquals(0f,library.read(id).manifest.edit.sharpen)
            // Check landscape layout and tool availability; edits survive reconfiguration.
            scenario.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            text("导出");tap("颜色");text("饱和度")
            moveSlider("饱和度调整",.35f);tap("应用调整")
            await {library.read(id).manifest.edit.saturation<1f}
            scenario.onActivity {it.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            text("导出");tap("导出");tap("PNG");tap("保存照片")
            val savedBeforeCancel=library.read(id).manifest
            device.findObject(UiSelector().className("android.widget.EditText")).waitForExists(10_000)
            device.pressBack();device.waitForIdle()
            if(device.currentPackageName!=context.packageName) device.pressBack()
            text("已取消导出")
            assertEquals(savedBeforeCancel,library.read(id).manifest)
            tap("导出");tap("保存照片")
            val field=device.findObject(UiSelector().className("android.widget.EditText"))
            check(field.waitForExists(10_000));field.setText(exportName)
            tap("SAVE")
            text("照片已导出，未复制原图元数据")
            val bytes=Base64.decode(device.executeShellCommand("base64 /sdcard/Download/$exportName"),Base64.DEFAULT)
            val exported=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
            assertEquals(crop.width,exported.width);assertEquals(crop.height,exported.height);exported.recycle()
            tap("导出");tap("分享照片")
            await {device.currentPackageName!=context.packageName}
            device.pressBack();text("项目")
            tap("项目");text("我的工程")
            description("$name 更多操作").click();tap("创建副本")
            text("$name 副本")
            description("$name 副本 更多操作").click();tap("删除工程")
            text("删除此工程？");tap("删除工程")
            await {library.entries().count {it.name.startsWith(name)}==1}
            description("$name 更多操作").click();tap("重命名")
            val nameField=device.findObject(UiSelector().className("android.widget.EditText"))
            check(nameField.waitForExists(5_000));nameField.setText("$name-renamed");tap("保存名称")
            text("$name-renamed");tap("$name-renamed");text("已保存")
            assertTrue(library.read(id).manifest.edit.exposureEv>.5f)
            device.executeShellCommand("mkdir -p /sdcard/Download/nijiframe-test-evidence")
            device.executeShellCommand("screencap -p /sdcard/Download/nijiframe-test-evidence/editor-success.png")
        } catch (error: Throwable) {
            device.executeShellCommand("mkdir -p /sdcard/Download/nijiframe-test-evidence")
            device.executeShellCommand("screencap -p /sdcard/Download/nijiframe-test-evidence/editor-failure.png")
            device.dumpWindowHierarchy(File(context.cacheDir,"ui-failure.xml"))
            device.executeShellCommand("sh -c 'run-as dev.nijiframe.app cat cache/ui-failure.xml > /sdcard/Download/nijiframe-test-evidence/window.xml'")
            throw error
        } finally {
            scenario?.close()
            resolver.delete(uri,null,null)
            device.executeShellCommand("rm -f /sdcard/Download/$exportName")
            library.entries().filter {it.name.startsWith(name)}.forEach {library.delete(it.id)}
            if(previous!=null && library.entries().any {it.id==previous}) library.markOpened(previous)
        }
    }
}
