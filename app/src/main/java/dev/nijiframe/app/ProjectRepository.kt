// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dev.nijiframe.editor.Project
import dev.nijiframe.editor.ProjectIO
import java.io.File

/** All external content is copied into a self-contained project before it becomes editable. */
class ProjectRepository(private val context: Context) {
    private val resolver = context.contentResolver
    val savedFile = File(context.filesDir, "projects/current.nijiproj")
    fun importPhoto(uri: Uri): Pair<Project, Bitmap> {
        val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法读取图片" }.use {
            ProjectIO.readBounded(it, ProjectIO.MAX_SOURCE_BYTES)
        }
        val bitmap = ImagePipeline.decode(bytes)
        return ProjectIO.create(bytes, bitmap.width, bitmap.height) to bitmap
    }
    fun open(uri: Uri): Pair<Project, Bitmap> =
        decode(requireNotNull(resolver.openInputStream(uri)) { "无法读取工程" }.use(ProjectIO::read))
    fun restore(): Pair<Project, Bitmap> {
        require(savedFile.exists()) { "还没有保存的工程，请先导入照片并保存" }
        return decode(savedFile.inputStream().use(ProjectIO::read))
    }
    private fun decode(project: Project): Pair<Project, Bitmap> {
        val bitmap = ImagePipeline.decode(project.original)
        if (bitmap.width != project.manifest.width || bitmap.height != project.manifest.height) {
            bitmap.recycle()
            throw IllegalArgumentException("工程尺寸与原图不一致")
        }
        return project to bitmap
    }
    fun save(project: Project) = ProjectIO.saveAtomic(project, savedFile)
    fun exportProject(project: Project, uri: Uri) {
        requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入工程" }.use { ProjectIO.write(project, it) }
    }
    suspend fun exportPhoto(project: Project, source: Bitmap, uri: Uri) {
        val rendered = ImagePipeline.render(source, project.manifest.edit)
        try {
            requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法写入照片" }.use {
                check(rendered.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "照片编码失败" }
            }
        } finally { rendered.recycle() }
    }
}
