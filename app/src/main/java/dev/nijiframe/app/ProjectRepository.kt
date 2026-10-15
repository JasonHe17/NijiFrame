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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Android I/O boundary. Each editable project owns its source; no persistent URI permission is needed. */
class ProjectRepository(private val context: Context,
                        private val directory: File = File(context.filesDir, "library"),
                        private val availableMemory: () -> Long = { ImageMemory.available(context) }) {
    private val resolver = context.contentResolver
    val library = ProjectLibrary(directory)
    fun prepare() {
        if (directory == File(context.filesDir,"library"))
            library.migrateLegacy(File(context.filesDir,"projects/current.nijiproj"))
    }
    fun importPhoto(uri: Uri): Pair<Project, Bitmap> {
        fun budget() = minOf(ProjectIO.MAX_SOURCE_BYTES.toLong(), ((availableMemory() - 32L*1024*1024)/3).coerceAtLeast(0)).toInt()
        fun read(limit: Int) = requireNotNull(resolver.openInputStream(uri)) { "无法读取图片" }.use {
            ProjectIO.readBounded(it, limit)
        }
        var limit = budget()
        val bytes = try { read(limit) } catch (first: IllegalArgumentException) {
            // A previous project's byte arrays can await collection. Retry the read once with a fresh budget.
            if (limit == ProjectIO.MAX_SOURCE_BYTES) throw IllegalArgumentException("照片文件超过 64 MiB 限制", first)
            System.gc(); limit = budget()
            try { read(limit) } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException(if (limit == ProjectIO.MAX_SOURCE_BYTES) "照片文件超过 64 MiB 限制"
                    else "当前内存不足以读取这张照片，请关闭其他应用或使用较小文件", error)
            }
        }
        val info = ImagePipeline.inspect(bytes)
        val bitmap = ImagePipeline.decode(bytes, ImageSizing.previewSample(info.width, info.height), info)
        val name = runCatching { resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }}.getOrNull()?.substringBeforeLast('.')?.filterNot { it.isISOControl() }?.take(80)?.trim().orEmpty()
        try {
            val project = ProjectIO.create(bytes, info.width, info.height)
            return project.copy(manifest=project.manifest.copy(name=name.ifBlank { "新照片" })) to bitmap
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }
    fun open(uri: Uri): Pair<Project, Bitmap> =
        decode(requireNotNull(resolver.openInputStream(uri)) { "无法读取工程" }.use(ProjectIO::read))
    fun read(id: String): Pair<Project,Bitmap> = decode(library.read(id))
    fun restore(): Pair<Project,Bitmap> = read(requireNotNull(library.latest()) { "还没有保存的工程，请先导入照片" })
    private fun decode(project: Project): Pair<Project, Bitmap> {
        val info = ImagePipeline.inspect(project.original)
        require(info.width == project.manifest.width && info.height == project.manifest.height) { "工程尺寸与原图不一致" }
        val bitmap = ImagePipeline.decode(project.original, ImageSizing.previewSample(info.width, info.height), info)
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
    fun checkPhotoExport(project: Project, source: Bitmap, settings: ExportSettings) {
        val m = project.manifest
        val sample = ImageSizing.exportSample(m, settings)
        val reusable = source.width == m.width && source.height == m.height && sample == 1
        val required = ImageSizing.exportWorkingBytes(m, sample, reusable, settings.maxEdge != null)
        if (required > availableMemory()) {
            // Previous projects/previews can be unreachable but not collected yet. Recheck once before refusal.
            // This runs on a worker, and never raises the budget or substitutes a lower resolution.
            System.gc()
        }
        ImageMemory.requireExport(required, availableMemory())
    }
    suspend fun exportPhoto(project: Project, source: Bitmap, uri: Uri, settings: ExportSettings = ExportSettings()) {
        // Reject before creating/truncating the destination; never silently change the chosen dimensions.
        checkPhotoExport(project, source, settings)
        val m = project.manifest
        val sample = ImageSizing.exportSample(m, settings)
        val context = currentCoroutineContext()
        context.ensureActive()
        val reusable = source.width == m.width && source.height == m.height && sample == 1
        val input = if (reusable) source else ImagePipeline.decode(project.original, sample, check = { context.ensureActive() })
        try {
            val edit = ImagePipeline.scaledEdit(m.edit, m.width, m.height, input.width, input.height)
            var rendered = ImagePipeline.render(input, edit)
            try {
                val (w, h) = Geometry.outputSize(m.width, m.height, m.edit)
                val (width, height) = settings.dimensions(w, h)
                if (width != rendered.width || height != rendered.height) {
                    val reduced = Bitmap.createScaledBitmap(rendered, width, height, true)
                    rendered.recycle(); rendered = reduced
                }
                context.ensureActive()
                val format = if (settings.format == PhotoFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入照片" }.use {
                    check(rendered.compress(format, settings.quality, it)) { "照片编码失败" }
                }
            } finally { rendered.recycle() }
        } finally { if (!reusable) input.recycle() }
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
