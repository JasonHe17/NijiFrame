// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class ProjectLibraryTest {
    @Test fun multipleProjectsRemainIndependentAcrossReopenRenameAndDelete() {
        val directory=Files.createTempDirectory("nijiframe-library").toFile()
        try {
            val library=ProjectLibrary(directory)
            val first=ProjectIO.create(byteArrayOf(1,2),10,20)
            val a=library.create(first)
            val b=library.create(first.withEdit(Edit(exposureEv=1f)))
            library.rename(a,"Portrait A")
            assertEquals("Portrait A",library.read(a).manifest.name)
            assertEquals(1f,library.read(b).manifest.edit.exposureEv)
            val copy=library.duplicate(a)
            library.save(copy,library.read(copy).withEdit(Edit(exposureEv=-1f)))
            assertEquals(0f,library.read(a).manifest.edit.exposureEv)
            val reopened=ProjectLibrary(directory)
            assertEquals(copy,reopened.latest())
            assertEquals(3,reopened.entries().size)
            reopened.delete(copy)
            assertEquals(2,reopened.entries().size)
            assertNotEquals(copy,reopened.latest())
            assertThrows(IllegalArgumentException::class.java) { library.read("../escape") }
            val before=library.read(a).manifest
            assertThrows(IllegalArgumentException::class.java) { library.rename(a,"") }
            assertEquals(before,library.read(a).manifest)
        } finally { directory.deleteRecursively() }
    }
    @Test fun legacyMigrationIsRepeatableAndDamagedProjectsRemainVisible() {
        val directory=Files.createTempDirectory("nijiframe-library").toFile()
        try {
            val legacy=File(directory,"old.nijiproj")
            ProjectIO.saveAtomic(ProjectIO.create(byteArrayOf(5),10,10),legacy)
            val library=ProjectLibrary(File(directory,"library"))
            library.migrateLegacy(legacy);library.migrateLegacy(legacy)
            assertEquals(1,library.entries().size)
            val id=library.latest()!!
            File(directory,"library/$id.nijiproj").writeBytes(byteArrayOf(1,2,3))
            assertTrue(library.entries().single().damaged)
            assertTrue(legacy.exists())
            library.delete(id);library.migrateLegacy(legacy)
            assertTrue(library.entries().isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
