// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.nijiframe.editor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EditorUi(
    val project: Project? = null,
    val preview: Bitmap? = null,
    val busy: Boolean = false,
    val rendering: Boolean = false,
    val comparing: Boolean = false,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val status: String = "导入一张照片，开始编辑",
    val error: String? = null
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ProjectRepository(application)
    private val mutable = MutableStateFlow(EditorUi())
    val ui = mutable.asStateFlow()
    private var source: Bitmap? = null
    private var history = History()
    private var saved: Project? = null
    private var renderJob: Job? = null
    private val pixelWork = Mutex()
    private var generation = 0L
    init { if (repository.savedFile.exists()) restore() }

    private fun io(status: String, action: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, status = status, error = null)
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = mutable.value.copy(error = when (error) {
                    is SecurityException -> "没有文件访问权限，请重新选择文件"
                    is java.io.IOException -> "文件读写失败，请检查文件、存储空间或访问权限后重试"
                    else -> error.message?.takeIf { text -> text.any { it in '\u4e00'..'\u9fff' } }
                        ?: "文件格式或内容不受支持，无法完成操作"
                }, status = "操作未完成；原工程仍保留")
            } catch (_: OutOfMemoryError) {
                mutable.value = mutable.value.copy(rendering = false, error = "设备可用内存不足，请使用较小的图片", status = "操作未完成")
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
    fun dismissError() { mutable.value = mutable.value.copy(error = null) }
    fun importPhoto(uri: Uri) = load("正在导入照片", false) { repository.importPhoto(uri) }
    fun open(uri: Uri) = load("正在读取工程", false) { repository.open(uri) }
    fun restore() = load("正在恢复已存工程", true) { repository.restore() }
    private fun load(status: String, restored: Boolean, read: () -> Pair<Project, Bitmap>) = io(status) {
        renderJob?.cancelAndJoin()
        val (project, bitmap) = try {
            withContext(Dispatchers.IO) { pixelWork.withLock { read() } }
        } catch (error: Exception) { render(); throw error }
        source = bitmap
        history = History(project.manifest.edit)
        saved = if (restored) project else null
        mutable.value = EditorUi(project = project, busy = true, dirty = !restored,
            status = if (restored) "已恢复保存的工程" else "尚未保存工程")
        render()
    }
    fun commit(edit: Edit) {
        if (mutable.value.busy) return
        val project = mutable.value.project ?: return
        edit.validate(project.manifest.width, project.manifest.height)
        history.commit(edit)
        publishEdit(history.current)
    }
    fun undo() { if (!mutable.value.busy) publishEdit(history.undo()) }
    fun redo() { if (!mutable.value.busy) publishEdit(history.redo()) }
    private fun publishEdit(edit: Edit) {
        val project = mutable.value.project ?: return
        val updated = project.withEdit(edit)
        val dirty = saved?.manifest != updated.manifest
        mutable.value = mutable.value.copy(project = updated, dirty = dirty, comparing = false,
            canUndo = history.canUndo, canRedo = history.canRedo,
            status = if (dirty) "有未保存的修改" else "工程已保存")
        render()
    }
    fun previewEdit(edit: Edit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(comparing = false)
        render(edit, true)
    }
    fun compare() {
        mutable.value = mutable.value.copy(comparing = !mutable.value.comparing)
        render()
    }
    private fun render(draft: Edit? = null, debounce: Boolean = false) {
        renderJob?.cancel()
        val project = mutable.value.project ?: return
        val original = source ?: return
        val parameters = if (mutable.value.comparing) Edit() else draft ?: project.manifest.edit
        val ticket = ++generation
        mutable.value = mutable.value.copy(rendering = true)
        renderJob = viewModelScope.launch {
            try {
                if (debounce) delay(80)
                val bitmap = withContext(Dispatchers.Default) {
                    pixelWork.withLock { ImagePipeline.preview(ImagePipeline.render(original, parameters)) }
                }
                if (ticket == generation) mutable.value = mutable.value.copy(preview = bitmap, rendering = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = mutable.value.copy(rendering = false, error = error.message ?: "预览失败") }
            catch (_: OutOfMemoryError) { mutable.value = mutable.value.copy(rendering = false, error = "预览内存不足，请使用较小图片") }
        }
    }
    fun save() = io("正在保存工程") { saveCurrent() }
    private suspend fun saveCurrent(): Project {
        val project = requireNotNull(mutable.value.project)
        withContext(Dispatchers.IO) { repository.save(project) }
        saved = project
        mutable.value = mutable.value.copy(dirty = false, status = "工程已保存在应用中")
        return project
    }
    fun exportProject(uri: Uri) = io("正在导出工程") {
        val project = saveCurrent()
        withContext(Dispatchers.IO) { repository.exportProject(project, uri) }
        mutable.value = mutable.value.copy(status = "工程已导出，可重新打开继续编辑")
    }
    fun exportPhoto(uri: Uri) = io("正在导出完整尺寸照片") {
        val project = saveCurrent()
        val bitmap = requireNotNull(source)
        withContext(Dispatchers.Default) { pixelWork.withLock { repository.exportPhoto(project, bitmap, uri) } }
        mutable.value = mutable.value.copy(status = "JPEG 已导出；未复制位置等原图元数据")
    }
}
