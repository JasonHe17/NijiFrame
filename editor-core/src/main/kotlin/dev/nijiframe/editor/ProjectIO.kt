// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProjectIO {
    const val MAX_SOURCE_BYTES = 32 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 16 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun create(original: ByteArray, width: Int, height: Int): Project =
        Project(Manifest(sourceBytes = original.size, sourceSha256 = digest(original),
            width = width, height = height).also { it.validate() }, original.copyOf())

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size().toLong() + count <= limit) { "Resource exceeds size limit" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    /** Catalog metadata only; opening for editing still validates the entire resource package. */
    fun inspect(file: File): Manifest = ZipFile(file).use { zip ->
        val entry = requireNotNull(zip.getEntry("project.json")) { "Missing project manifest" }
        val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_MANIFEST_BYTES) }
        json.decodeFromString<Manifest>(bytes.decodeToString(throwOnInvalidSequence = true)).also { it.validate() }.current()
    }

    fun read(input: InputStream): Project {
        // Never extract paths to a filesystem. Only these two regular payloads are accepted.
        val entries = mutableMapOf<String, ByteArray>()
        val container = readBounded(input, MAX_SOURCE_BYTES + 1024 * 1024)
        validateContainer(container)
        ZipInputStream(container.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && entry.name in setOf("project.json", "assets/original") &&
                    entry.name !in entries) { "Unexpected or duplicate resource" }
                val limit = if (entry.name == "project.json") MAX_MANIFEST_BYTES else MAX_SOURCE_BYTES
                entries[entry.name] = readBounded(zip, limit)
                zip.closeEntry()
            }
        }
        require(entries.size == 2) { "Missing project resources" }
        val manifest = json.decodeFromString<Manifest>(entries.getValue("project.json").decodeToString(throwOnInvalidSequence = true))
        manifest.validate()
        val original = entries.getValue(manifest.source)
        require(original.size == manifest.sourceBytes && digest(original) == manifest.sourceSha256) { "Source integrity check failed" }
        return Project(manifest.current(), original)
    }

    // Version 1 deliberately excludes comments, ZIP64, encryption and special files.
    // Validate the central directory as well as payload CRCs: a truncated local stream is not a saved project.
    private fun validateContainer(bytes: ByteArray) {
        fun u16(at: Int): Int {
            require(at >= 0 && at + 2 <= bytes.size) { "Truncated ZIP" }
            return (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
        }
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        val end = bytes.size - 22
        require(end >= 0 && u32(end) == 0x06054b50L && u16(end + 20) == 0 &&
            u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == 2 && u16(end + 10) == 2) { "Incomplete or unsupported ZIP container" }
        val offset = u32(end + 16)
        require(offset <= end && offset + u32(end + 12) == end.toLong()) { "Invalid ZIP directory" }
        var cursor = offset.toInt()
        val names = mutableSetOf<String>()
        val offsets = mutableSetOf<Long>()
        repeat(2) {
            require(cursor + 46 <= end && u32(cursor) == 0x02014b50L) { "Invalid ZIP entry" }
            val length = u16(cursor + 28)
            val next = cursor.toLong() + 46 + length + u16(cursor + 30) + u16(cursor + 32)
            require(next <= end && u16(cursor + 34) == 0 && u16(cursor + 8) and 1 == 0 &&
                u16(cursor + 10) in setOf(0, 8)) { "Unsupported ZIP entry" }
            val mode = (u32(cursor + 38) shr 16).toInt() and 0xf000
            require(mode == 0 || mode == 0x8000) { "Special files are not supported" }
            val name = bytes.copyOfRange(cursor + 46, cursor + 46 + length).decodeToString(throwOnInvalidSequence = true)
            require(name in setOf("project.json", "assets/original") && names.add(name)) { "Unexpected or duplicate resource" }
            val local = u32(cursor + 42)
            require(local < offset && offsets.add(local) && u32(local.toInt()) == 0x04034b50L) { "Invalid local entry" }
            val localNameLength = u16(local.toInt() + 26)
            require(local + 30 + localNameLength <= offset &&
                bytes.copyOfRange(local.toInt() + 30, local.toInt() + 30 + localNameLength).decodeToString() == name) { "Conflicting ZIP paths" }
            cursor = next.toInt()
        }
        require(cursor == end) { "Unexpected ZIP directory data" }
    }

    fun write(project: Project, output: OutputStream) {
        project.manifest.validate()
        require(project.original.size == project.manifest.sourceBytes &&
            digest(project.original) == project.manifest.sourceSha256) { "Source integrity check failed" }
        // Close the deflater while retaining the caller's stream for fsync.
        ZipOutputStream(object : FilterOutputStream(output) {
            // FilterOutputStream otherwise expands a bulk write into one syscall per byte.
            override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length) }
            override fun close() { flush() }
        }).use { zip ->
        for ((name, bytes) in listOf("project.json" to json.encodeToString(project.manifest).encodeToByteArray(),
            project.manifest.source to project.original)) {
            zip.putNextEntry(ZipEntry(name).apply { time = 0 })
            zip.write(bytes)
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
        }
    }

    /** A failed write or replacement leaves the previous complete project intact. */
    fun saveAtomic(project: Project, destination: File) = replaceAtomic(destination) { write(project, it) }

    internal fun replaceAtomic(destination: File, writer: (OutputStream) -> Unit) {
        val parent = destination.absoluteFile.parentFile!!
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create project directory" }
        val temp = File.createTempFile(".nijiframe-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { output -> writer(output); output.fd.sync() }
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}
