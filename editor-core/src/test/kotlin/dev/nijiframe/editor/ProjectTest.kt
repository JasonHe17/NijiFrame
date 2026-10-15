// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.zip.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProjectTest {
    private fun project() = ProjectIO.create(ByteArray(64) { it.toByte() }, 8, 4)
    private fun archive(p: Project = project()) = ByteArrayOutputStream().also { ProjectIO.write(p, it) }.toByteArray()
    private fun custom(manifest: Manifest = project().manifest, bytes: ByteArray = project().original,
                       path: String = "assets/original"): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, payload) in listOf("project.json" to Json.encodeToString(manifest).encodeToByteArray(), path to bytes)) {
                zip.putNextEntry(ZipEntry(name)); zip.write(payload); zip.closeEntry()
            }
        }
    }.toByteArray()
    private fun rejects(bytes: ByteArray) { assertThrows(Exception::class.java) { ProjectIO.read(bytes.inputStream()) } }

    @Test fun roundTripRetainsOriginalAndEditableState() {
        val original = project()
        val p = original.copy(manifest = original.manifest.copy(edit = Edit(1.25f, 0.5f, 3, Crop(1, 2, 2, 4))))
        val restored = ProjectIO.read(archive(p).inputStream())
        assertEquals(p.manifest, restored.manifest)
        assertArrayEquals(original.original, restored.original)
    }
    @Test fun historyBranchesAndCoalescesOneGesture() {
        val h = History()
        h.commit(Edit(1f)); h.commit(Edit(2f))
        assertEquals(Edit(1f), h.undo()); assertEquals(Edit(), h.undo())
        assertEquals(Edit(1f), h.redo())
        h.commit(Edit(saturation = 0f)); assertFalse(h.canRedo)
        h.commit(h.current); assertEquals(Edit(1f), h.undo())
    }
    @Test fun unsupportedVersionsAndOperationsFail() {
        rejects(custom(project().manifest.copy(version = 99)))
        rejects(custom(project().manifest.copy(pipeline = "future-retouch")))
    }
    @Test fun invalidResourceAndDigestFail() {
        rejects(custom(bytes = byteArrayOf(1, 2, 3)))
        rejects(custom(path = "../original"))
        rejects(custom(project().manifest.copy(sourceSha256 = "0".repeat(64))))
    }
    @Test fun truncatedContainerFailsEvenWhenPayloadsAreIntact() {
        val bytes = archive()
        rejects(bytes.copyOf(bytes.size - 1))
        rejects(bytes.copyOf(bytes.size - 22))
    }
    @Test fun symbolicLinkEntryFails() {
        val bytes = archive()
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val start = (0..bytes.size - 4).first { bytes.copyOfRange(it, it + 4).contentEquals(signature) }
        bytes[start + 41] = 0xa1.toByte()
        rejects(bytes)
    }
    @Test fun invalidGeometryAndNumbersFail() {
        for (edit in listOf(Edit(turns = 4), Edit(crop = Crop(Int.MAX_VALUE, 0, 2, 2)),
            Edit(exposureEv = Float.NaN), Edit(saturation = -1f))) {
            assertThrows(IllegalArgumentException::class.java) { edit.validate(8, 4) }
        }
    }
    @Test fun resourceBoundIsEnforcedDuringRead() {
        assertThrows(IllegalArgumentException::class.java) { ProjectIO.readBounded(ByteArray(33).inputStream(), 32) }
    }
    @Test fun compressedResourcesRetainBulkWritesAtTheStorageBoundary() {
        val bytes=ByteArray(64*1024).also {java.util.Random(1).nextBytes(it)}
        val output=object : OutputStream() {
            var singleWrites=0
            var bulkBytes=0
            override fun write(value: Int) {singleWrites++}
            override fun write(buffer: ByteArray,offset: Int,length: Int) {bulkBytes+=length}
        }
        ProjectIO.write(ProjectIO.create(bytes,100,100),output)
        assertTrue("ZIP payload must not become per-byte storage calls",output.singleWrites<1024)
        assertTrue("The incompressible source should be sent in blocks",output.bulkBytes>bytes.size/2)
    }

    @Test fun failedSavePreservesPreviousProjectAndCleansTemporaryFile() {
        val directory = Files.createTempDirectory("nijiframe-test").toFile()
        try {
            val path = File(directory, "current.nijiproj")
            ProjectIO.saveAtomic(project(), path)
            val before = path.readBytes()
            assertThrows(IOException::class.java) {
                ProjectIO.replaceAtomic(path) { it.write(byteArrayOf(1, 2)); throw IOException("injected disk failure") }
            }
            assertArrayEquals(before, path.readBytes())
            assertEquals(listOf("current.nijiproj"), directory.list()!!.toList())
            assertEquals(project().manifest, path.inputStream().use(ProjectIO::read).manifest)
        } finally { directory.deleteRecursively() }
    }
}
