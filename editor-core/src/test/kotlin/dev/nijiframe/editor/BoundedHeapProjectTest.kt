// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/** Runs in a separate 96 MiB JVM: an archive-sized staging array or chunk copy would exhaust this heap. */
class BoundedHeapProjectTest {
    @Test fun maximumOriginalReopensFromLibraryAndProviderWithOneSourceArray() {
        assertTrue(Runtime.getRuntime().maxMemory() <= 96L*1024*1024)
        val root = Files.createTempDirectory("nijiframe-bounded-heap").toFile()
        try {
            val id = "00000000-0000-4000-8000-000000000001"
            val archive = File(root,"$id.nijiproj")
            val hash = MessageDigest.getInstance("SHA-256")
            val crc = CRC32()
            fun blocks(accept: (ByteArray) -> Unit) {
                val random = Random(17)
                val buffer = ByteArray(64*1024)
                repeat(ProjectIO.MAX_SOURCE_BYTES/buffer.size) { random.nextBytes(buffer);accept(buffer) }
            }
            blocks {hash.update(it);crc.update(it)}
            val digest = hash.digest().joinToString("") {"%02x".format(it)}
            val manifest = Manifest(sourceBytes=ProjectIO.MAX_SOURCE_BYTES,sourceSha256=digest,width=4000,height=3000,
                name="Bounded project",edit=Edit(exposureEv=.5f))
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                // Deliberately put the original first; package correctness does not depend on entry order.
                zip.putNextEntry(ZipEntry("assets/original").apply {
                    method=ZipEntry.STORED;size=ProjectIO.MAX_SOURCE_BYTES.toLong();this.crc=crc.value
                })
                blocks {zip.write(it)};zip.closeEntry()
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(Json.encodeToString(manifest).encodeToByteArray());zip.closeEntry()
            }
            assertTrue(archive.length()>ProjectIO.MAX_SOURCE_BYTES)
            fun verify(project: Project) {
                assertEquals(manifest,project.manifest)
                assertEquals(ProjectIO.MAX_SOURCE_BYTES,project.original.size)
                assertEquals(digest,ProjectIO.digest(project.original))
            }
            verify(ProjectLibrary(root).read(id))
            val staging=File(root,"staging")
            verify(archive.inputStream().use {ProjectIO.read(it,staging)})
            assertTrue(staging.listFiles()!!.isEmpty())
        } finally {root.deleteRecursively()}
    }
}
