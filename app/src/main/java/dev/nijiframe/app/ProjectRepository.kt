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
    val library = ProjectLibrary(directory, ::requireProjectMemory)
    private val importCache = File(context.cacheDir, "project-imports")
    private val exportCache = File(context.cacheDir, "export-staging")
    private val photoCache = File(context.cacheDir, "photo-imports")
    fun prepare() {
        StagedExport.prune(exportCache)
        // A killed process can leave a staged import. Do not touch recent files or saved projects.
        for (cache in listOf(importCache, photoCache)) {
            cache.listFiles()?.filter { it.name.startsWith("project-import-") && it.extension == "tmp" &&
                System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        }
        if (directory == File(context.filesDir,"library"))
            library.migrateLegacy(File(context.filesDir,"projects/current.nijiproj"))
    }
    fun importPhoto(uri: Uri): Pair<Project, Bitmap> {
        var inspected: ImagePipeline.ImageInfo? = null
        val project = try {
            requireNotNull(resolver.openInputStream(uri)) { "无法读取图片" }.use { input ->
                ProjectIO.create(input, photoCache, ::requirePhotoMemory) { bytes ->
                    ImagePipeline.inspect(bytes).also { inspected = it }.let { it.width to it.height }
                }
            }
        } catch (error: ProjectIO.ResourceTooLargeException) {
            throw IllegalArgumentException("照片文件超过 64 MiB 限制", error)
        }
        val info = requireNotNull(inspected)
        requireCodecMemory(info)
        val bitmap = ImagePipeline.decode(project.original, ImageSizing.previewSample(info.width, info.height), info)
        val name = runCatching { resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }}.getOrNull()?.substringBeforeLast('.')?.filterNot { it.isISOControl() }?.take(80)?.trim().orEmpty()
        try {
            return project.copy(manifest=project.manifest.copy(name=name.ifBlank { "新照片" })) to bitmap
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }
    fun open(uri: Uri): Pair<Project, Bitmap> =
        decode(requireNotNull(resolver.openInputStream(uri)) { "无法读取工程" }.use {
            ProjectIO.read(it, importCache, ::requireProjectMemory)
        })
    fun read(id: String): Pair<Project,Bitmap> = decode(library.read(id))
    fun restore(): Pair<Project,Bitmap> = read(requireNotNull(library.latest()) { "还没有保存的工程，请先导入照片" })
    private fun decode(project: Project): Pair<Project, Bitmap> {
        val info = ImagePipeline.inspect(project.original,project.manifest.honorExifColorSpace)
        require(info.width == project.manifest.width && info.height == project.manifest.height) { "工程尺寸与原图不一致" }
        requireCodecMemory(info)
        val bitmap = ImagePipeline.decode(project.original, ImageSizing.previewSample(info.width, info.height), info)
        return project to bitmap
    }
    private fun requirePhotoMemory(sourceBytes: Int) {
        val required = sourceBytes.toLong() + 32L * 1024 * 1024
        if (required > availableMemory()) System.gc()
        require(required <= availableMemory()) { "当前内存不足以读取这张照片，请关闭其他应用或使用较小文件" }
    }
    private fun requireProjectMemory(sourceBytes: Int) {
        val required = sourceBytes.toLong() + 32L * 1024 * 1024
        if (required > availableMemory()) System.gc()
        require(required <= availableMemory()) { "当前内存不足以打开这份工程，请关闭其他应用后重试；已保存文件仍保留" }
    }
    private fun requireCodecMemory(info: ImagePipeline.ImageInfo) {
        if(info.codecHeadroom>0) require(info.codecHeadroom+32L*1024*1024<=availableMemory()) {"此 HEIF/AVIF 照片解码需要更多内存，请使用较小照片或由相册导出 JPEG；原文件未改变"}
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
    suspend fun exportProject(project: Project, uri: Uri, onPhase: suspend (ExportPhase) -> Unit = {}) {
        onPhase(ExportPhase.PACKAGING)
        StagedExport.write(exportCache, {onPhase(ExportPhase.WRITING)},
            {requireNotNull(resolver.openOutputStream(uri,"wt")) {"无法写入工程"}}) { ProjectIO.write(project,it) }
    }
    fun checkPhotoExport(project: Project, source: Bitmap, settings: ExportSettings) {
        val m = project.manifest
        val sample = ImageSizing.exportSample(m, settings)
        val reusable = source.width == m.width && source.height == m.height && sample == 1
        val required = ImageSizing.exportWorkingBytes(m, sample, reusable, settings.maxEdge != null) +
            (if(reusable) 0 else ImagePipeline.inspect(project.original,project.manifest.honorExifColorSpace).codecHeadroom)
        if (required > availableMemory()) {
            // Previous projects/previews can be unreachable but not collected yet. Recheck once before refusal.
            // This runs on a worker, and never raises the budget or substitutes a lower resolution.
            System.gc()
        }
        ImageMemory.requireExport(required, availableMemory())
    }
    suspend fun exportPhoto(project: Project, source: Bitmap, uri: Uri, settings: ExportSettings = ExportSettings(),
                            onPhase: suspend (ExportPhase) -> Unit = {}) {
        // Reject before creating/truncating the destination; never silently change the chosen dimensions.
        onPhase(ExportPhase.RENDERING)
        checkPhotoExport(project, source, settings)
        val m = project.manifest
        val sample = ImageSizing.exportSample(m, settings)
        val context = currentCoroutineContext()
        context.ensureActive()
        val reusable = source.width == m.width && source.height == m.height && sample == 1
        StagedExport.write(exportCache, {onPhase(ExportPhase.WRITING)},
            {requireNotNull(resolver.openOutputStream(uri,"wt")) {"无法写入照片"}}) { output ->
            val input = if (reusable) source else ImagePipeline.decode(project.original, sample,
                info=ImagePipeline.inspect(project.original,m.honorExifColorSpace), check={context.ensureActive()})
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
                    onPhase(ExportPhase.ENCODING)
                    val encoded=rendered.compress(format,settings.quality,output)
                    context.ensureActive()
                    check(encoded) {"照片编码失败"}
                } finally { rendered.recycle() }
            } finally { if (!reusable) input.recycle() }
            // All export-only bitmaps are released before copying the prepared file to its provider.
        }
    }

    suspend fun sharePhoto(project: Project, source: Bitmap, settings: ExportSettings,
                           onPhase: suspend (ExportPhase) -> Unit = {}): Uri {
        val folder=File(context.cacheDir,"shared-photos").apply { mkdirs() }
        // Existing recipients can keep reading a previous share while a new photo is prepared.
        folder.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>86_400_000 }?.forEach { it.delete() }
        val path=File(folder,"NijiFrame-${UUID.randomUUID()}.${settings.format.extension}")
        try { exportPhoto(project,source,Uri.fromFile(path),settings,onPhase) }
        catch (error: Throwable) { path.delete();throw error }
        return FileProvider.getUriForFile(context,"${context.packageName}.files",path)
    }
}
