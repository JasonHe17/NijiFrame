// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import dev.nijiframe.editor.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

class DetailInspectorTest {
    @Test fun inspectorShowsNativePixelsAndLeavesDraftAndSavedProjectUntouched() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val device=UiDevice.getInstance(instrumentation)
        fun fresh() {if(android.os.Build.VERSION.SDK_INT>=34) instrumentation.uiAutomation.clearCache()}
        fun text(label: String): UiObject {fresh();return device.findObject(UiSelector().text(label))}
        fun desc(label: String): UiObject {fresh();return device.findObject(UiSelector().description(label))}
        fun await(condition: () -> Boolean) {
            val end=System.currentTimeMillis()+20000
            while(System.currentTimeMillis()<end) {if(runCatching(condition).getOrDefault(false)) return;Thread.sleep(80)}
            assertTrue(condition())
        }
        fun actionable(label: String): UiObject2? {
            fresh()
            var node=device.findObject(By.text(label)) ?: return null
            // Compose's Text child stays enabled while its enclosing button is disabled during rendering.
            while(!node.isClickable) node=node.parent ?: return null
            return node.takeIf {it.isEnabled}
        }
        fun tap(label: String) {await {actionable(label)!=null};requireNotNull(actionable(label)).click();device.waitForIdle()}
        val library=ProjectLibrary(File(context.filesDir,"library"));val previous=library.latest()
        val source=Bitmap.createBitmap(IntArray(2000*1600) {i ->
            val value=if((i%2000+i/2000)%2==0) 40 else 180
            Color.rgb(value,value,value)
        },2000,1600,Bitmap.Config.ARGB_8888)
        val original=ByteArrayOutputStream().also {source.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()
        source.recycle()
        val project=ProjectIO.create(original,2000,1600)
        val id=library.create(project)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            await {desc("编辑预览").exists()};tap("查看细节")
            await {desc("原尺寸细节图").exists()}
            val bounds=desc("原尺寸细节图").visibleBounds
            val file=File(context.cacheDir,"detail-screen.png")
            try {
                assertTrue(device.takeScreenshot(file))
                val screenshot=BitmapFactory.decodeFile(file.path)
                try {
                    val x=bounds.centerX();val y=bounds.centerY()
                    assertTrue("Original 1-pixel texture must remain 1 screen pixel",
                        abs(Color.red(screenshot.getPixel(x,y))-Color.red(screenshot.getPixel(x+1,y)))>100)
                } finally {screenshot.recycle()}
            } finally {file.delete()}
            val overview=desc("选择细节区域").visibleBounds
            device.click(overview.centerX()+30,overview.centerY()+20)
            await {desc("原尺寸细节图").exists()}
            tap("查看原图");await {text("原图细节 · 保持当前构图").exists() && desc("原尺寸细节图").exists()}
            scenario.recreate();await {text("100% 细节").exists() && desc("原尺寸细节图").exists()}
            device.pressBack();await {!text("100% 细节").exists() && desc("编辑预览").exists()}
            assertEquals(project.manifest,library.read(id).manifest)
            tap("明暗")
            await {desc("曝光调整").exists()}
            val slider=desc("曝光调整").visibleBounds
            device.swipe(slider.centerX(),slider.centerY(),slider.left+slider.width()*3/4,slider.centerY(),12)
            device.waitForIdle();tap("查看细节")
            await {desc("原尺寸细节图").exists()}
            tap("关闭细节");await {text("调整待应用").exists()}
            assertEquals(project.manifest,library.read(id).manifest)
            tap("取消调整")
            assertArrayEquals(original,library.read(id).original)
        } catch(error: Throwable) {
            val evidence=File("/sdcard/Download/nijiframe-test-evidence").apply {mkdirs()}
            device.dumpWindowHierarchy(File(evidence,"detail-failure.xml"))
            device.takeScreenshot(File(evidence,"detail-failure.png"))
            File(evidence,"detail-memory.txt").writeText(device.executeShellCommand("dumpsys meminfo dev.nijiframe.app"))
            throw error
        } finally {
            scenario.close();library.delete(id)
            if(previous!=null && library.entries().any {it.id==previous}) library.markOpened(previous)
        }
    }
}
