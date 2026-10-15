// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProjectIO {
    const val MAX_SOURCE_BYTES = 64 * 1024 * 1024
    const val MAX_MASK_BYTES = 512 * 1024
    const val MAX_ARCHIVE_BYTES = MAX_SOURCE_BYTES + MAX_MASK_BYTES + 1024 * 1024
    private val allowed = setOf("project.json", "assets/original", "assets/local-mask.json")
    private const val MAX_MANIFEST_BYTES = 16 * 1024
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun create(original: ByteArray, width: Int, height: Int): Project =
        Project(Manifest(sourceBytes = original.size, sourceSha256 = digest(original),
            width = width, height = height, honorExifColorSpace = true).also { it.validate() }, original.copyOf())

    fun readBounded(input: InputStream, limit: Int): ByteArray {
        require(limit >= 0)
        // Fixed chunks avoid ByteArrayOutputStream's doubling buffer plus final-copy peak on large originals.
        val chunks = ArrayList<ByteArray>()
        var total = 0
        var finished = false
        while (!finished) {
            val chunk = ByteArray(minOf(64 * 1024L, limit.toLong() - total + 1).toInt())
            var used = 0
            while (used < chunk.size) {
                val count = input.read(chunk, used, chunk.size - used)
                if (count < 0) { finished = true; break }
                if (count == 0) {
                    val byte = input.read()
                    if (byte < 0) { finished = true; break }
                    chunk[used++] = byte.toByte()
                } else used += count
                require(total.toLong() + used <= limit) { "Resource exceeds size limit" }
            }
            if (used > 0) { chunks.add(if (used == chunk.size) chunk else chunk.copyOf(used)); total += used }
        }
        val result = ByteArray(total)
        var offset = 0
        for (chunk in chunks) { chunk.copyInto(result, offset); offset += chunk.size }
        return result
    }

    /** Catalog metadata only; opening for editing still validates the entire resource package. */
    fun inspect(file: File): Manifest = ZipFile(file).use { zip ->
        val entry = requireNotNull(zip.getEntry("project.json")) { "Missing project manifest" }
        val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_MANIFEST_BYTES) }
        json.decodeFromString<Manifest>(bytes.decodeToString(throwOnInvalidSequence = true)).also { it.validate() }.current()
    }

    /** Convenience reader for in-memory callers. Use the file/staged overloads for large projects. */
    fun read(input: InputStream): Project {
        val container = readBounded(input, MAX_ARCHIVE_BYTES)
        val directory = validateContainer(container.size) { at, length -> container.copyOfRange(at, at + length) }
        return readResources(container.inputStream(), directory)
    }

    /** One file handle for directory validation and payloads, including across atomic replacements. */
    fun read(file: File, beforeSourceAllocation: (Int) -> Unit = {}): Project = RandomAccessFile(file, "r").use { source ->
        require(source.length() <= MAX_ARCHIVE_BYTES) { "Resource exceeds size limit" }
        val directory = validateContainer(source.length().toInt()) { at, length ->
            ByteArray(length).also { source.seek(at.toLong()); source.readFully(it) }
        }
        beforeSourceAllocation(requireNotNull(directory["assets/original"]) { "Missing original" }.size)
        source.seek(0)
        readResources(object : InputStream() {
            override fun read(): Int = source.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = source.read(bytes, offset, length)
        }.buffered(), directory)
    }

    /** Stage a non-seekable provider stream with a fixed buffer; no archive paths are extracted. */
    fun read(input: InputStream, stagingDirectory: File, beforeSourceAllocation: (Int) -> Unit = {}): Project {
        check(stagingDirectory.isDirectory || stagingDirectory.mkdirs()) { "Cannot create import directory" }
        val temp = File.createTempFile("project-import-", ".tmp", stagingDirectory)
        try {
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) {
                        val byte = input.read()
                        if (byte < 0) break
                        require(++total <= MAX_ARCHIVE_BYTES) { "Resource exceeds size limit" }
                        output.write(byte)
                    } else {
                        total += count
                        require(total <= MAX_ARCHIVE_BYTES) { "Resource exceeds size limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            return read(temp, beforeSourceAllocation)
        } finally { temp.delete() }
    }

    private data class Resource(val size: Int, val compressedSize: Long, val crc: Long)

    private fun readResources(input: InputStream, directory: Map<String, Resource>): Project {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val resource = requireNotNull(directory[entry.name]) { "Unexpected resource" }
                require(!entry.isDirectory && entry.name !in entries) { "Unexpected or duplicate resource" }
                // Directory sizes are bounded before allocation. Read exactly once into the final array;
                // the extra read both rejects expanded payloads and lets ZipInputStream validate its CRC.
                val bytes = ByteArray(resource.size)
                DataInputStream(zip).readFully(bytes)
                require(zip.read() == -1 && entry.size == resource.size.toLong() &&
                    entry.compressedSize == resource.compressedSize && entry.crc == resource.crc &&
                    CRC32().apply { update(bytes) }.value == resource.crc) { "Resource integrity check failed" }
                entries[entry.name] = bytes
                zip.closeEntry()
            }
        }
        require(entries.keys == directory.keys) { "Missing project resources" }
        require(entries.size in 2..3) { "Missing project resources" }
        val manifest = json.decodeFromString<Manifest>(entries.getValue("project.json").decodeToString(throwOnInvalidSequence = true))
        manifest.validate()
        val original = entries.getValue(manifest.source)
        require(original.size == manifest.sourceBytes && digest(original) == manifest.sourceSha256) { "Source integrity check failed" }
        val resource=manifest.localMask
        require((resource != null) == entries.containsKey("assets/local-mask.json")) { "Missing or unexpected mask" }
        val region=if(resource==null) Region() else {
            val bytes=entries.getValue(resource.path)
            require(bytes.size==resource.bytes && digest(bytes)==resource.sha256) { "Mask integrity check failed" }
            json.decodeFromString<Region>(bytes.decodeToString(throwOnInvalidSequence=true)).also { it.validate() }
        }
        require(manifest.version >= 5 || region.base == null) { "Legacy project cannot contain base coverage" }
        region.validateWork(manifest.width,manifest.height)
        val current=manifest.current()
        return Project(current.copy(localMask=null,edit=current.edit.copy(local=current.edit.local.copy(region=region))), original)
    }

    // Version 1 deliberately excludes comments, ZIP64, encryption and special files.
    // Validate the central directory as well as payload CRCs: a truncated local stream is not a saved project.
    private fun validateContainer(size: Int, read: (Int, Int) -> ByteArray): Map<String, Resource> {
        fun bytes(at: Int, length: Int): ByteArray {
            require(at >= 0 && length >= 0 && at.toLong() + length <= size) { "Truncated ZIP" }
            return read(at, length)
        }
        fun u16(at: Int): Int = bytes(at, 2).let { (it[0].toInt() and 255) or ((it[1].toInt() and 255) shl 8) }
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        val end = size - 22
        require(end >= 0 && u32(end) == 0x06054b50L && u16(end + 20) == 0 &&
            u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) in 2..3 && u16(end + 10) == u16(end + 8)) { "Incomplete or unsupported ZIP container" }
        val offset = u32(end + 16)
        require(offset <= end && offset + u32(end + 12) == end.toLong()) { "Invalid ZIP directory" }
        var cursor = offset.toInt()
        val resources = mutableMapOf<String, Resource>()
        val offsets = mutableSetOf<Long>()
        repeat(u16(end + 8)) {
            require(cursor + 46 <= end && u32(cursor) == 0x02014b50L) { "Invalid ZIP entry" }
            val length = u16(cursor + 28)
            val next = cursor.toLong() + 46 + length + u16(cursor + 30) + u16(cursor + 32)
            require(next <= end && u16(cursor + 34) == 0 && u16(cursor + 8) and 1 == 0 &&
                u16(cursor + 10) in setOf(0, 8)) { "Unsupported ZIP entry" }
            val mode = (u32(cursor + 38) shr 16).toInt() and 0xf000
            require(mode == 0 || mode == 0x8000) { "Special files are not supported" }
            val name = bytes(cursor + 46, length).decodeToString(throwOnInvalidSequence = true)
            require(name in allowed && name !in resources) { "Unexpected or duplicate resource" }
            val limit = when (name) { "project.json" -> MAX_MANIFEST_BYTES; "assets/local-mask.json" -> MAX_MASK_BYTES; else -> MAX_SOURCE_BYTES }
            val sizeInEntry = u32(cursor + 24)
            require(sizeInEntry <= limit) { "Resource exceeds size limit" }
            resources[name] = Resource(sizeInEntry.toInt(), u32(cursor + 20), u32(cursor + 16))
            val local = u32(cursor + 42)
            require(local + 30 <= offset && offsets.add(local) && u32(local.toInt()) == 0x04034b50L) { "Invalid local entry" }
            val localNameLength = u16(local.toInt() + 26)
            val payload = local + 30 + localNameLength + u16(local.toInt() + 28)
            require(payload + u32(cursor + 20) <= offset &&
                u16(local.toInt() + 6) == u16(cursor + 8) && u16(local.toInt() + 8) == u16(cursor + 10) &&
                bytes(local.toInt() + 30, localNameLength).decodeToString(throwOnInvalidSequence = true) == name) { "Conflicting ZIP entry" }
            cursor = next.toInt()
        }
        require(cursor == end) { "Unexpected ZIP directory data" }
        return resources
    }

    fun write(project: Project, output: OutputStream) {
        project.manifest.validate()
        require(project.original.size == project.manifest.sourceBytes &&
            digest(project.original) == project.manifest.sourceSha256) { "Source integrity check failed" }
        val region=project.manifest.edit.local.region
        val mask=if(region.isEmpty) null else json.encodeToString(region).encodeToByteArray()
        require(mask==null || mask.size<=MAX_MASK_BYTES) { "Mask exceeds size limit" }
        val manifest=project.manifest.copy(localMask=mask?.let { MaskResource(bytes=it.size,sha256=digest(it)) })
        manifest.validate()
        val manifestBytes=json.encodeToString(manifest).encodeToByteArray()
        require(manifestBytes.size<=MAX_MANIFEST_BYTES)
        val payloads=mutableListOf("project.json" to manifestBytes, manifest.source to project.original)
        if(mask!=null) payloads.add("assets/local-mask.json" to mask)
        // Close the deflater while retaining the caller's stream for fsync.
        ZipOutputStream(object : FilterOutputStream(output) {
            // FilterOutputStream otherwise expands a bulk write into one syscall per byte.
            override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length) }
            override fun close() { flush() }
        }).use { zip ->
        for ((name, bytes) in payloads) {
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
