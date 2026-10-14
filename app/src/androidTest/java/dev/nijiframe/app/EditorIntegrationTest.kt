// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import dev.nijiframe.editor.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses Android decoders, JNI, ContentResolver and durable app storage, with synthetic images only. */
class EditorIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private fun document(name: String, mime: String): Uri = requireNotNull(resolver.insert(
        MediaStore.Files.getContentUri("external"), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/NijiFrameTests")
        }))
    private fun fixture(): Bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(128, 64, 32))
        for (y in 0 until 20) for (x in 0 until 10) setPixel(x, y, Color.BLUE)
    }
    @Test fun contentUriImportSaveSourceDeletionReopenAndFullSizeExport() = runBlocking {
        val sourceUri = document("fixture.png", "image/png")
        val projectUri = document("edited.nijiproj", "application/zip")
        val jpegUri = document("export.jpg", "image/jpeg")
        val repository = ProjectRepository(context)
        // Never overwrite a user's saved project when running connected checks.
        val previous = repository.savedFile.takeIf { it.exists() }?.readBytes()
        try {
            resolver.openOutputStream(sourceUri)!!.use { fixture().compress(Bitmap.CompressFormat.PNG, 100, it) }
            val (project, source) = repository.importPhoto(sourceUri)
            val edited = project.copy(manifest = project.manifest.copy(edit = Edit(1f, 0f, 1, Crop(0, 10, 20, 30))))
            repository.save(edited)
            repository.exportProject(edited, projectUri)
            resolver.delete(sourceUri, null, null)
            val reopened = ProjectRepository(context).restore()
            assertArrayEquals(project.original, reopened.first.original)
            assertEquals(edited.manifest, reopened.first.manifest)
            val portable = repository.open(projectUri)
            assertEquals(edited.manifest, portable.first.manifest)
            repository.exportPhoto(portable.first, portable.second, jpegUri)
            val jpegBytes = resolver.openInputStream(jpegUri)!!.use { it.readBytes() }
            val output = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            assertEquals(20, output.width); assertEquals(30, output.height)
            val pixel = output.getPixel(10, 15)
            assertTrue(kotlin.math.abs(Color.red(pixel) - Color.green(pixel)) <= 2)
            assertTrue(Color.red(pixel) in 110..125)
            val exif = ExifInterface(jpegBytes.inputStream())
            assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
            assertTrue(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in 0..1)
            // Reopened parameters can change independently of the retained source.
            val neutral = ImagePipeline.render(source, Edit())
            assertEquals(Color.rgb(128, 64, 32), neutral.getPixel(20, 10))
            source.recycle(); reopened.second.recycle(); portable.second.recycle(); neutral.recycle(); output.recycle()
        } finally {
            listOf(sourceUri, projectUri, jpegUri).forEach { resolver.delete(it, null, null) }
            if (previous == null) repository.savedFile.delete() else repository.savedFile.writeBytes(previous)
        }
    }
    @Test fun latestEditWinsAndFailedProviderExportKeepsRecoveryCopy() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val uri = document("latest.png", "image/png")
        val repository = ProjectRepository(context)
        val previous = repository.savedFile.takeIf { it.exists() }?.readBytes()
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var model: EditorViewModel
        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
        suspend fun idle() { withTimeout(15_000) { while (model.ui.value.busy || model.ui.value.rendering) delay(20) } }
        try {
            val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(128, 128, 128)) }
            resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            onMain { model = EditorViewModel(context.applicationContext as android.app.Application); store.put("editor", model) }
            idle()
            onMain { model.importPhoto(uri) }; idle()
            onMain {
                model.commit(Edit(1f)); model.commit(Edit(2f)); model.undo(); model.redo()
            }
            idle()
            assertEquals(2f, model.ui.value.project!!.manifest.edit.exposureEv)
            val red = Color.red(model.ui.value.preview!!.getPixel(100, 100))
            assertTrue("Expected +2 EV of sRGB 128 to be 239, got $red", red in 238..240)
            val expected = model.ui.value.project!!.manifest
            val invalid = Uri.parse("content://dev.nijiframe.nonexistent/document/1")
            onMain { model.exportPhoto(invalid) }; idle()
            assertNotNull(model.ui.value.error)
            assertEquals(expected, repository.restore().first.manifest)
            onMain { model.importPhoto(invalid) }; idle()
            assertEquals(expected, model.ui.value.project!!.manifest)
            assertNotNull(model.ui.value.preview)
        } finally {
            onMain { store.clear() }
            resolver.delete(uri, null, null)
            if (previous == null) repository.savedFile.delete() else repository.savedFile.writeBytes(previous)
        }
    }

    @Test fun geometryDetailsAndConfiguredPngExport() = runBlocking {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW)
        val source = Bitmap.createBitmap(colors, 3, 2, Bitmap.Config.ARGB_8888)
        val transformed = ImagePipeline.render(source, Edit(turns=1, flipHorizontal=true))
        assertEquals(2, transformed.width); assertEquals(3, transformed.height)
        val actual = IntArray(6); transformed.getPixels(actual,0,2,0,0,2,3)
        assertArrayEquals(intArrayOf(Color.YELLOW,Color.BLUE,Color.MAGENTA,Color.GREEN,Color.CYAN,Color.RED),actual)
        source.recycle();transformed.recycle()
        val white = Bitmap.createBitmap(180,120,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val straight = ImagePipeline.render(white,Edit(straighten=37f, sharpen=.8f))
        val pixels=IntArray(180*120);straight.getPixels(pixels,0,180,0,0,180,120)
        assertTrue("Straighten must fill the entire output",pixels.all { it == Color.WHITE })
        straight.recycle()
        val encoded=java.io.ByteArrayOutputStream().also { white.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        val project=ProjectIO.create(encoded,180,120).withEdit(Edit(turns=1,vignette=.5f))
        val uri=document("configured.png","image/png")
        try {
            ProjectRepository(context).exportPhoto(project,white,uri,ExportSettings(PhotoFormat.PNG,80,90))
            val bytes=resolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47),bytes.copyOf(4))
            val result=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
            assertEquals(60,result.width);assertEquals(90,result.height)
            assertTrue(Color.red(result.getPixel(30,45)) > Color.red(result.getPixel(0,0)))
            result.recycle()
        } finally { resolver.delete(uri,null,null);white.recycle() }
    }

    @Test fun sharpeningStripsMatchWholeImageWithoutBoundarySeams() = runBlocking {
        val width=17;val height=75
        val input=IntArray(width*height) { i -> Color.rgb((i*13)%256,(i*19)%256,(i*29)%256) }
        val reference=IntArray(input.size)
        dev.nijiframe.image.NativeTone.sharpen(input,reference,width,height,.75f)
        val source=Bitmap.createBitmap(input,width,height,Bitmap.Config.ARGB_8888)
        val result=ImagePipeline.render(source,Edit(sharpen=.75f))
        val pixels=IntArray(input.size);result.getPixels(pixels,0,width,0,0,width,height)
        assertArrayEquals(reference,pixels)
        source.recycle();result.recycle()
    }

    @Test fun allExifOrientationsUseTheSameEditingCoordinates() {
        val path = File(context.cacheDir, "orientation.jpg")
        try {
            val input = Bitmap.createBitmap(30, 20, Bitmap.Config.ARGB_8888)
            for (y in 0 until 20) for (x in 0 until 30) input.setPixel(x, y,
                if (x < 15) (if (y < 10) Color.RED else Color.BLUE) else (if (y < 10) Color.GREEN else Color.WHITE))
            path.outputStream().use { input.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            // Expected top-left corner after each EXIF transform: TL, TR, BR, BL, TL, BL, BR, TR.
            val expected = listOf(Color.RED, Color.GREEN, Color.WHITE, Color.BLUE, Color.RED, Color.BLUE, Color.WHITE, Color.GREEN)
            for (orientation in 1..8) {
                ExifInterface(path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                val normalized = ImagePipeline.decode(path.readBytes())
                assertEquals(if (orientation >= 5) 20 else 30, normalized.width)
                assertEquals(if (orientation >= 5) 30 else 20, normalized.height)
                val actual = normalized.getPixel(3, 3)
                val wanted = expected[orientation - 1]
                assertTrue("orientation $orientation", listOf(Color.red(actual) - Color.red(wanted),
                    Color.green(actual) - Color.green(wanted), Color.blue(actual) - Color.blue(wanted)).all { kotlin.math.abs(it) < 10 })
                normalized.recycle()
            }
            input.recycle()
        } finally { path.delete() }
    }
    @Test fun cancellationAndTransparentInputAreExplicit() = runBlocking {
        val bitmap = fixture()
        val large = Bitmap.createBitmap(2400, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            started.complete(Unit)
            ImagePipeline.render(large, Edit(1.5f)).recycle()
        }
        started.await()
        delay(10)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(Color.GRAY, large.getPixel(1200, 1200))
        large.recycle()
        val transparent = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { transparent.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) { ImagePipeline.decode(bytes) }
        bitmap.recycle(); transparent.recycle()
    }
}
