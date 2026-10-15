// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.editor

import java.io.File
import java.util.UUID

/** Private local catalog. Portable project identity never contains a filesystem path. */
class ProjectLibrary(private val directory: File, private val beforeSourceAllocation: (Int) -> Unit = {}) {
    data class Entry(val id: String, val name: String, val modified: Long, val width: Int, val height: Int, val damaged: Boolean = false)
    private val lastFile get() = File(directory, "last-project")
    private fun file(id: String): File {
        require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) { "Invalid local project identifier" }
        return File(directory, "$id.nijiproj")
    }
    fun create(project: Project): String = UUID.randomUUID().toString().also { save(it, project) }
    fun save(id: String, project: Project) {
        ProjectIO.saveAtomic(project, file(id))
        ProjectIO.replaceAtomic(lastFile) { it.write(id.encodeToByteArray()) }
    }
    fun markOpened(id: String) {
        require(file(id).isFile) { "工程不存在" }
        ProjectIO.replaceAtomic(lastFile) { it.write(id.encodeToByteArray()) }
    }
    fun read(id: String): Project = ProjectIO.read(file(id), beforeSourceAllocation)
    fun entries(): List<Entry> = directory.listFiles().orEmpty()
        .filter { it.extension == "nijiproj" && it.nameWithoutExtension.matches(Regex("[0-9a-f-]{36}")) }
        .map { path ->
            try {
                val manifest = ProjectIO.inspect(path)
                val (w,h) = Geometry.outputSize(manifest.width,manifest.height,manifest.edit)
                Entry(path.nameWithoutExtension,manifest.name,path.lastModified(),w,h)
            } catch (_: Exception) { Entry(path.nameWithoutExtension,"无法读取的工程",path.lastModified(),0,0,true) }
        }.sortedByDescending { it.modified }
    fun latest(): String? = runCatching { lastFile.readText().takeIf { file(it).isFile } }.getOrNull()
        ?: entries().firstOrNull { !it.damaged }?.id
    fun rename(id: String, name: String) {
        val project=read(id)
        save(id,project.copy(manifest=project.manifest.copy(name=name.trim())))
    }
    fun duplicate(id: String): String {
        val project=read(id)
        return create(project.copy(manifest=project.manifest.copy(name=(project.manifest.name+" 副本").take(80))))
    }
    fun delete(id: String) {
        val path=file(id)
        check(!path.exists() || path.delete()) { "无法删除工程" }
        if (lastFile.isFile && lastFile.readText()==id) lastFile.delete()
    }
    /** Deterministic migration is retry-safe, and never deletes the existing recovery file. */
    fun migrateLegacy(legacy: File) {
        val marker=File(directory,"legacy-imported")
        if (!legacy.isFile || marker.exists()) return
        val id="00000000-0000-4000-8000-000000000001"
        if (!file(id).exists()) save(id,ProjectIO.read(legacy, beforeSourceAllocation))
        ProjectIO.replaceAtomic(marker) { it.write(id.encodeToByteArray()) }
    }
}
