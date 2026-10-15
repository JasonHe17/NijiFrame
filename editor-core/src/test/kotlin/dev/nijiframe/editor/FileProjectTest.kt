// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.zip.*

class FileProjectTest {
    private fun inDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("nijiframe-file-test").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun source() = ProjectIO.create(ByteArray(1024) { it.toByte() }, 40, 30)
    private fun archive() = ByteArrayOutputStream().also { ProjectIO.write(source(), it) }.toByteArray()
    private fun u32(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { value, i -> value or ((bytes[at+i].toLong() and 255) shl (8*i)) }
    private fun put32(bytes: ByteArray, at: Int, value: Long) { repeat(4) { bytes[at+it] = (value shr (8*it)).toByte() } }
    private fun directories(bytes: ByteArray): List<Int> = (0..bytes.size-4).filter { u32(bytes,it)==0x02014b50L }

    @Test fun fileAndProviderReadersPreserveAllSupportedVersionsAndRegions() = inDirectory { root ->
        val pipelines = listOf("srgb-tone-crop-turn-v1", "srgb-basic-v2", "srgb-local-v3", "srgb-soften-v4", "srgb-region-v5", "srgb-import-v6")
        for ((index,pipeline) in pipelines.withIndex()) {
            val version = index+1
            val region = if(version<3) Region() else Region(strokes=listOf(MaskStroke(points=listOf(MaskPoint(.5f,.5f)),radius=.1f)))
            val base = source()
            val project = base.copy(manifest=base.manifest.copy(version=version,pipeline=pipeline,honorExifColorSpace=version>=6,
                edit=Edit(exposureEv=.5f,local=LocalAdjustment(region=region))))
            val file = File(root,"v$version.nijiproj")
            ProjectIO.saveAtomic(project,file)
            val memory = file.inputStream().use { ProjectIO.read(it) }
            for (loaded in listOf(ProjectIO.read(file), file.inputStream().use { ProjectIO.read(it,File(root,"staging")) })) {
                assertEquals(memory.manifest,loaded.manifest)
                assertArrayEquals(project.original,loaded.original)
            }
        }
        assertTrue(File(root,"staging").listFiles()!!.isEmpty())
    }

    @Test fun centralDirectoryCannotHideSizeCrcMethodOrPathConflicts() = inDirectory { root ->
        val original = archive()
        val central = directories(original).last()
        val local = u32(original,central+42).toInt()
        val mutations: List<(ByteArray) -> Unit> = listOf(
            { put32(it,central+24,ProjectIO.MAX_SOURCE_BYTES+1L) },
            { put32(it,central+24,10) },
            { put32(it,central+24,2048) },
            { put32(it,central+16,0) },
            { put32(it,central+20,0xffffffffL) },
            { it[local+8]=0 },
            { it[local+6]=1 },
            { it[local+30]='x'.code.toByte() },
            { put32(it,central+42,0xffffffffL) }
        )
        for (mutate in mutations) {
            val bytes = original.copyOf().also(mutate)
            val file = File(root,"bad.nijiproj").apply {writeBytes(bytes)}
            assertThrows(Exception::class.java) { ProjectIO.read(file) }
            assertThrows(Exception::class.java) { ProjectIO.read(bytes.inputStream()) }
        }
    }

    @Test fun storedPayloadCrcIsChecked() = inDirectory { root ->
        val original = source()
        // Repack with STORED payloads, then corrupt source bytes while keeping declared sizes intact.
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip -> ZipInputStream(archive().inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val payload = input.readBytes()
                    zip.putNextEntry(ZipEntry(entry.name).apply {method=ZipEntry.STORED;size=payload.size.toLong();crc=CRC32().apply {update(payload)}.value})
                    zip.write(payload);zip.closeEntry()
                }
            } }
        }.toByteArray()
        val central = directories(bytes).last()
        val local = u32(bytes,central+42).toInt()
        val file = File(root,"stored.nijiproj").apply {writeBytes(bytes)}
        assertArrayEquals(original.original,ProjectIO.read(file).original)
        bytes[local+30+"assets/original".length] = 99
        file.writeBytes(bytes)
        assertThrows(Exception::class.java) {ProjectIO.read(file)}
    }

    @Test fun stagedImportCleansUpOnReadAndAdmissionFailureAndHandlesShortReads() = inDirectory { root ->
        val bytes = archive()
        val input = object : ByteArrayInputStream(bytes) {
            var calls = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                if (++calls%4==0) 0 else super.read(b,off,minOf(len,7))
        }
        assertArrayEquals(source().original,ProjectIO.read(input,root).original)
        assertThrows(IOException::class.java) {
            ProjectIO.read(object : InputStream() {
                var calls=0
                override fun read(): Int { if (++calls>=15) throw IOException("injected provider failure"); return 1 }
            },root)
        }
        var admitted = -1
        assertThrows(IllegalArgumentException::class.java) { ProjectIO.read(bytes.inputStream(),root) { admitted=it; throw IllegalArgumentException("refused") } }
        assertEquals(source().original.size,admitted)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun archiveLimitAppliesBeforePayloadAllocationAndToProviderCopy() = inDirectory { root ->
        val file = File(root,"oversize.nijiproj")
        RandomAccessFile(file,"rw").use {it.setLength(ProjectIO.MAX_ARCHIVE_BYTES+1L)}
        var allocated = false
        assertThrows(IllegalArgumentException::class.java) {ProjectIO.read(file) {allocated=true}}
        assertFalse(allocated)
        val staging = File(root,"staging")
        val input = object : InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = len
        }
        assertThrows(IllegalArgumentException::class.java) {ProjectIO.read(input,staging)}
        assertTrue(staging.listFiles()!!.isEmpty())
    }
}
