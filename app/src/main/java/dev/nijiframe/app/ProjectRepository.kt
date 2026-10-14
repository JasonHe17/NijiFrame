// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.FileProvider
import dev.nijiframe.editor.*
import java.io.File
import java.util.UUID

/** Android I/O boundary. Each editable project owns its source; no persistent URI permission is needed. */
class ProjectRepository(private val context: Context,
                        private val directory: File = File(context.filesDir, "library")) {
    private val resolver = context.contentResolver
    val library = ProjectLibrary(directory)
    fun prepare() {
        if (directory == File(context.filesDir,"library"))
            library.migrateLegacy(File(context.filesDir,"projects/current.nijiproj"))
    }
    fun importPhoto(uri: Uri): Pair<Project, Bitmap> {
        val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法读取图片" }.use {
            ProjectIO.readBounded(it, ProjectIO.MAX_SOURCE_BYTES)
        }
        val bitmap = ImagePipeline.decode(bytes)
        val name = runCatching { resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }}.getOrNull()?.substringBeforeLast('.')?.filterNot { it.isISOControl() }?.take(80)?.trim().orEmpty()
        val project = ProjectIO.create(bytes, bitmap.width, bitmap.height)
        return project.copy(manifest=project.manifest.copy(name=name.ifBlank { "新照片" })) to bitmap
    }
    fun open(uri: Uri): Pair<Project, Bitmap> =
        decode(requireNotNull(resolver.openInputStream(uri)) { "无法读取工程" }.use(ProjectIO::read))
    fun read(id: String): Pair<Project,Bitmap> = decode(library.read(id))
    fun restore(): Pair<Project,Bitmap> = read(requireNotNull(library.latest()) { "还没有保存的工程，请先导入照片" })
    private fun decode(project: Project): Pair<Project, Bitmap> {
        val bitmap = ImagePipeline.decode(project.original)
        if (bitmap.width != project.manifest.width || bitmap.height != project.manifest.height) {
            bitmap.recycle()
            throw IllegalArgumentException("工程尺寸与原图不一致")
        }
        return project to bitmap
    }
    fun thumbnail(id: String): File {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        return File(context.cacheDir,"project-previews/$id.jpg")
    }
    fun saveThumbnail(id: String, bitmap: Bitmap) {
        val path=thumbnail(id);path.parentFile?.mkdirs()
        val scale=minOf(1f,400f/maxOf(bitmap.width,bitmap.height))
        val small=Bitmap.createScaledBitmap(bitmap,maxOf(1,(bitmap.width*scale).toInt()),maxOf(1,(bitmap.height*scale).toInt()),true)
        try {
            val atomic=AtomicFile(path);val stream=atomic.startWrite()
            try { check(small.compress(Bitmap.CompressFormat.JPEG,85,stream));atomic.finishWrite(stream) }
            catch (error: Exception) { atomic.failWrite(stream);throw error }
        } finally { if (small !== bitmap) small.recycle() }
    }
    fun exportProject(project: Project, uri: Uri) {
        requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入工程" }.use { ProjectIO.write(project, it) }
    }
    suspend fun exportPhoto(project: Project, source: Bitmap, uri: Uri, settings: ExportSettings = ExportSettings()) {
        var rendered = ImagePipeline.render(source, project.manifest.edit)
        try {
            val (width, height) = settings.dimensions(rendered.width, rendered.height)
            if (width != rendered.width || height != rendered.height) {
                val reduced = Bitmap.createScaledBitmap(rendered, width, height, true)
                rendered.recycle(); rendered = reduced
            }
            val format = if (settings.format == PhotoFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入照片" }.use {
                check(rendered.compress(format, settings.quality, it)) { "照片编码失败" }
            }
        } finally { rendered.recycle() }
    }
    suspend fun sharePhoto(project: Project, source: Bitmap, settings: ExportSettings): Uri {
        val folder=File(context.cacheDir,"shared-photos").apply { mkdirs() }
        // Existing recipients can keep reading a previous share while a new photo is prepared.
        folder.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>86_400_000 }?.forEach { it.delete() }
        val path=File(folder,"NijiFrame-${UUID.randomUUID()}.${settings.format.extension}")
        try { exportPhoto(project,source,Uri.fromFile(path),settings) }
        catch (error: Throwable) { path.delete();throw error }
        return FileProvider.getUriForFile(context,"${context.packageName}.files",path)
    }
}
