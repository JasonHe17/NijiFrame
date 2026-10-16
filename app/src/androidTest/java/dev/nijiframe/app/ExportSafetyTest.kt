// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.nijiframe.editor.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ExportSafetyTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun directory()=File(context.cacheDir,"export-test-${System.nanoTime()}").apply {mkdirs()}
    private fun fixture(): Pair<Project,Bitmap> {
        val bitmap=Bitmap.createBitmap(160,120,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(80,80,80))}
        val bytes=ByteArrayOutputStream().also {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}.toByteArray()
        return ProjectIO.create(bytes,160,120).withEdit(Edit(exposureEv=1f,turns=1)) to bitmap
    }

    @Test fun preparationFailureAndMidStreamCancellationNeverOpenDestination() = runBlocking {
        val root=directory();val staging=File(root,"staging")
        val target=File(root,"output").apply {writeText("existing")}
        var opened=false;var committing=false
        try {
            try {
                StagedExport.write(staging,{committing=true},{opened=true;target.outputStream()}) {
                    it.write(ByteArray(128*1024));throw IOException("injected encoding failure")
                }
                fail("Expected preparation failure")
            } catch (_: IOException) { }
            assertFalse(opened);assertFalse(committing);assertEquals("existing",target.readText())
            assertTrue(staging.listFiles()!!.isEmpty())
            val job=launch(Dispatchers.Default) {
                val worker=currentCoroutineContext()
                StagedExport.write(staging,{committing=true},{opened=true;target.outputStream()}) {
                    it.write(ByteArray(128*1024));worker.cancel();it.write(ByteArray(128*1024))
                }
            }
            job.join();assertTrue(job.isCancelled)
            assertFalse(opened);assertFalse(committing);assertEquals("existing",target.readText())
            assertTrue(staging.listFiles()!!.isEmpty())
        } finally {root.deleteRecursively()}
    }

    @Test fun cancellationDuringNativePngEncodingDoesNotTouchDestination() = runBlocking {
        val root=directory();val staging=File(root,"staging")
        val target=File(root,"output.png").apply {writeText("keep")}
        val (_,source)=fixture()
        var written=false;var opened=false
        try {
            val job=launch(Dispatchers.Default) {
                val worker=currentCoroutineContext()
                StagedExport.write(staging,{}, {opened=true;target.outputStream()}) { output ->
                    val cancelAfterWrite=object : java.io.FilterOutputStream(output) {
                        override fun write(b: ByteArray,off: Int,len: Int) {
                            out.write(b,off,len);written=true;worker.cancel()
                        }
                        override fun write(value: Int) {out.write(value);written=true;worker.cancel()}
                    }
                    val result=source.compress(Bitmap.CompressFormat.PNG,100,cancelAfterWrite)
                    worker.ensureActive();check(result)
                }
            }
            job.join();assertTrue(job.isCancelled);assertTrue(written)
            assertFalse(opened);assertEquals("keep",target.readText())
            assertTrue(staging.listFiles()!!.isEmpty())
        } finally {source.recycle();root.deleteRecursively()}
    }

    @Test fun cancellationAtCommitBoundaryKeepsDestinationButCommittedCopyFinishes() = runBlocking {
        val root=directory();val staging=File(root,"staging")
        val target=File(root,"output").apply {writeText("existing")}
        var opened=false
        val bytes=ByteArray(180001) {(it%251).toByte()}
        try {
            val before=launch(Dispatchers.Default) {
                val worker=currentCoroutineContext()
                StagedExport.write(staging,{worker.cancel()},{opened=true;target.outputStream()}) {it.write(bytes)}
            }
            before.join();assertTrue(before.isCancelled)
            assertFalse(opened);assertEquals("existing",target.readText())
            assertTrue(staging.listFiles()!!.isEmpty())
            val during=launch(Dispatchers.Default) {
                val worker=currentCoroutineContext()
                StagedExport.write(staging,{}, {
                    opened=true
                    val output=target.outputStream()
                    object : OutputStream() {
                        override fun write(value: Int) {output.write(value);worker.cancel()}
                        override fun write(b: ByteArray,off: Int,len: Int) {output.write(b,off,len);worker.cancel()}
                        override fun close()=output.close()
                        override fun flush()=output.flush()
                    }
                }) {it.write(bytes)}
            }
            during.join();assertTrue(during.isCancelled);assertTrue(opened)
            assertArrayEquals(bytes,target.readBytes())
            assertTrue(staging.listFiles()!!.isEmpty())
        } finally {root.deleteRecursively()}
    }

    @Test fun cacheAndProviderFailuresAreExplicitAndCleanStaging() = runBlocking {
        val root=directory();val blocked=File(root,"blocked").apply {writeText("not a directory")}
        var opened=false
        try {
            try {StagedExport.write(blocked,{}, {opened=true;ByteArrayOutputStream()}) {it.write(1)};fail("Expected cache failure")}
            catch (_: IOException) { }
            assertFalse(opened)
            val staging=File(root,"staging")
            try {StagedExport.write(staging,{}, {throw IOException("provider unavailable")}) {it.write(1)};fail("Expected provider failure")}
            catch (_: IOException) { }
            assertTrue(staging.listFiles()!!.isEmpty())
            try {
                StagedExport.write(staging,{}, {object : OutputStream() {
                    override fun write(value: Int) {throw IOException("provider write failed")}
                }}) {it.write(ByteArray(128*1024))}
                fail("Expected write failure")
            } catch (_: IOException) { }
            assertTrue(staging.listFiles()!!.isEmpty())
        } finally {root.deleteRecursively()}
    }

    @Test fun photoAndProjectAreCompleteBeforeDestinationIsOpened() = runBlocking {
        val root=directory();val (project,source)=fixture()
        val repo=ProjectRepository(context,File(root,"library"))
        val cache=File(context.cacheDir,"export-staging")
        try {
            for(format in PhotoFormat.entries) {
                val target=File(root,"photo.${format.extension}").apply {writeText("existing")}
                val phases=mutableListOf<ExportPhase>()
                repo.exportPhoto(project,source,Uri.fromFile(target),ExportSettings(format)) { phase ->
                    phases.add(phase)
                    if(phase==ExportPhase.WRITING) {
                        assertEquals("existing",target.readText())
                        val prepared=BitmapFactory.decodeFile(cache.listFiles()!!.single().path)
                        assertNotNull(prepared)
                        try {assertEquals(120,prepared.width);assertEquals(160,prepared.height)} finally {prepared.recycle()}
                    }
                }
                assertEquals(listOf(ExportPhase.RENDERING,ExportPhase.ENCODING,ExportPhase.WRITING),phases)
                val decoded=BitmapFactory.decodeFile(target.path)
                try {assertTrue(Color.red(decoded.getPixel(40,40)) in 110..115)} finally {decoded.recycle()}
                assertTrue(cache.listFiles()!!.isEmpty())
            }
            val portable=File(root,"project.nijiproj").apply {writeText("existing")}
            val phases=mutableListOf<ExportPhase>()
            repo.exportProject(project,Uri.fromFile(portable)) {phase ->
                phases.add(phase)
                if(phase==ExportPhase.WRITING) {
                    assertEquals("existing",portable.readText())
                    assertEquals(project.manifest,ProjectIO.read(cache.listFiles()!!.single()).manifest)
                }
            }
            assertEquals(listOf(ExportPhase.PACKAGING,ExportPhase.WRITING),phases)
            val reopened=ProjectIO.read(portable)
            assertEquals(project.manifest,reopened.manifest);assertArrayEquals(project.original,reopened.original)
            assertTrue(cache.listFiles()!!.isEmpty())
        } finally {source.recycle();root.deleteRecursively()}
    }

    @Test fun cancelledEncodingAndInvalidProjectKeepExistingFilesAndLibrary() = runBlocking {
        val root=directory();val (project,source)=fixture()
        val repo=ProjectRepository(context,File(root,"library"))
        val id=repo.library.create(project)
        val target=File(root,"existing").apply {writeText("keep")}
        val before=File(root,"library/$id.nijiproj").readBytes()
        val cache=File(context.cacheDir,"export-staging")
        try {
            val job=launch(Dispatchers.Default) {
                val worker=currentCoroutineContext()
                repo.exportPhoto(project,source,Uri.fromFile(target)) {phase ->
                    if(phase==ExportPhase.ENCODING) worker.cancel()
                    assertNotEquals(ExportPhase.WRITING,phase)
                }
            }
            job.join();assertTrue(job.isCancelled)
            assertEquals("keep",target.readText())
            assertTrue(cache.listFiles().orEmpty().isEmpty())
            try {
                repo.exportProject(project.copy(original=byteArrayOf(1)),Uri.fromFile(target))
                fail("Expected source integrity rejection")
            } catch (_: IllegalArgumentException) { }
            assertEquals("keep",target.readText())
            assertTrue(cache.listFiles()!!.isEmpty())
            assertArrayEquals(before,File(root,"library/$id.nijiproj").readBytes())
        } finally {source.recycle();root.deleteRecursively()}
    }
    @Test fun editorAllowsPreparationCancellationAndProtectsTheWritePhase() = runBlocking {
        val root=directory();val (project,bitmap)=fixture();bitmap.recycle()
        val repo=ProjectRepository(context,File(root,"library"))
        val id=repo.library.create(project)
        val saved=File(root,"library/$id.nijiproj").readBytes()
        val store=androidx.lifecycle.ViewModelStore()
        val ins=InstrumentationRegistry.getInstrumentation()
        lateinit var model: EditorViewModel
        fun main(block: () -> Unit)=ins.runOnMainSync(block)
        suspend fun idle() {withTimeout(15000) {while(model.ui.value.busy || model.ui.value.rendering || model.ui.value.saving) delay(10)}}
        try {
            main {model=EditorViewModel(context.applicationContext as android.app.Application,repo);store.put("editor",model)}
            idle()
            for(phase in listOf(ExportPhase.ENCODING,ExportPhase.WRITING)) {
                val target=File(root,"export.png").apply {writeText("keep")}
                var observed=false
                // Observe the published phase immediately, before the export worker continues.
                // No fake repository, sleeps in product code or repeated cancel requests are used.
                val observer=launch(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) {
                    model.ui.collect { ui ->
                        if(ui.busy && ui.status==phase.label && !observed) {
                            observed=true
                            assertEquals(phase.cancellable,ui.canCancel)
                            model.cancelOperation()
                        }
                    }
                }
                try {main {model.exportPhoto(Uri.fromFile(target),PhotoFormat.PNG)};idle()}
                finally {observer.cancelAndJoin()}
                assertTrue("Expected $phase notification",observed)
                assertNull(model.ui.value.error)
                assertFalse(model.ui.value.canCancel)
                if(phase==ExportPhase.ENCODING) {
                    assertEquals("keep",target.readText())
                    assertEquals("操作已取消，工程已保留",model.ui.value.status)
                } else {
                    assertEquals("照片已导出，未复制原图元数据",model.ui.value.status)
                    val exported=BitmapFactory.decodeFile(target.path)
                    try {assertNotNull(exported);assertTrue(Color.red(exported.getPixel(10,10)) in 110..115)} finally {exported?.recycle()}
                }
                assertArrayEquals(saved,File(root,"library/$id.nijiproj").readBytes())
            }
        } finally {main {store.clear()};root.deleteRecursively()}
    }

}
