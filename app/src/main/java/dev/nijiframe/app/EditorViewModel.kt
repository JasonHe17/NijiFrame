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

enum class EditorTool(val label: String) {
    LOCAL("局部"), LIGHT("明暗"), COLOR("颜色"), CROP("裁剪"), GEOMETRY("旋转"), DETAILS("细节"), LOOKS("预设")
}
enum class RegionMode(val label: String) { ADD("添加区域"), ERASE("擦除区域"), MOVE("移动画布") }
enum class RetouchStep { REGION, EFFECT }
data class DetailUi(val center: MaskPoint=MaskPoint(.5f,.5f), val comparing: Boolean=false,
    val loading: Boolean=false, val bitmap: Bitmap?=null, val bounds: android.graphics.Rect?=null,
    val outputWidth: Int, val outputHeight: Int, val error: String?=null,
    val regionOverlay: Bitmap?=null, val revision: Long=0)

enum class ExportDestination { PHOTO, PROJECT }
data class SharedPhoto(val uri: Uri, val mime: String)
data class EditorUi(
    val appearance: AppAppearance = AppAppearance.SYSTEM, val canvasBackground: CanvasBackground = CanvasBackground.AUTO,
    val toolSession: Long = 0, val retouchStep: RetouchStep = RetouchStep.REGION,
    val selectedParameter: String? = null, val fitRevision: Int = 0,
    val project: Project? = null, val projectId: String? = null,
    val projects: List<ProjectLibrary.Entry> = emptyList(), val home: Boolean = true,
    val suggestion: Region? = null, val suggestionOverlay: Bitmap? = null,
    val sourceNotice: String? = null, val sourceWarning: String? = null, val detail: DetailUi? = null, val preview: Bitmap? = null, val regionOverlay: Bitmap? = null, val approximatePreview: Boolean = false,
    val regionMode: RegionMode = RegionMode.ADD, val showRegion: Boolean = true,
    val brushRadius: Float = .06f, val brushHardness: Float = .5f,
    val localCanUndo: Boolean = false, val localCanRedo: Boolean = false, val busy: Boolean = false, val saving: Boolean = false,
    val rendering: Boolean = false, val comparing: Boolean = false, val dirty: Boolean = false,
    val canUndo: Boolean = false, val canRedo: Boolean = false,
    val tool: EditorTool? = null, val draft: Edit? = null, val cropAspect: Float? = null, val canCancel: Boolean = false,
    val exportSettings: ExportSettings = ExportSettings(), val exportRequest: ExportDestination? = null, val sharedPhoto: SharedPhoto? = null,
    val status: String = "导入照片，开始编辑", val error: String? = null
)

class EditorViewModel @JvmOverloads constructor(application: Application,
    private val repository: ProjectRepository = ProjectRepository(application)) : AndroidViewModel(application) {
    private val preferences=application.getSharedPreferences("editor",0)
    private val mutable=MutableStateFlow(EditorUi(
        appearance=runCatching {AppAppearance.valueOf(preferences.getString("appearance","SYSTEM")!!)}.getOrDefault(AppAppearance.SYSTEM),
        canvasBackground=runCatching {CanvasBackground.valueOf(preferences.getString("canvasBackground","AUTO")!!)}.getOrDefault(CanvasBackground.AUTO),
        exportSettings=ExportSettings(
        format=runCatching { PhotoFormat.valueOf(preferences.getString("format","JPEG")!!) }.getOrDefault(PhotoFormat.JPEG),
        quality=preferences.getInt("quality",95).coerceIn(1,100),
        maxEdge=preferences.getInt("edge",0).takeIf { it in 64..16384 }
    )))
    val ui=mutable.asStateFlow()
    private var source: Bitmap?=null
    private var interactiveSource: Bitmap?=null
    private var history=History()
    private var localHistory=History()
    private var drawingMode=RegionMode.ADD
    private var savedManifest: Manifest?=null
    private var detailJob: Job?=null
    private var detailEdit: Edit?=null
    private var detailGeneration=0L
    private var renderJob: Job?=null
    private var autoSaveJob: Job?=null
    private var actionJob: Job?=null
    private val pixelWork=Mutex()
    private val saveWork=Mutex()
    private val actions=Mutex()
    private val initialized=CompletableDeferred<Unit>()
    private var generation=0L
    private var saveGeneration=0L

    init {
        mutable.value=mutable.value.copy(busy=true,status="正在读取工程")
        viewModelScope.launch {
            try {
                val latest=withContext(Dispatchers.IO) { repository.prepare();repository.library.latest() }
                refreshProjects()
                if (latest != null) {
                    val loaded=withContext(Dispatchers.IO) { repository.read(latest) }
                    install(latest,loaded)
                }
            } catch (error: Exception) { fail(error) }
            catch (_: OutOfMemoryError) { mutable.value=mutable.value.copy(error="内存不足，未能重开工程；已保存文件仍保留，可从项目列表选择其他工程") }
            finally {
                mutable.value=mutable.value.copy(busy=false,
                    status=if(mutable.value.project==null && mutable.value.error==null) "导入照片，开始编辑" else mutable.value.status)
                initialized.complete(Unit)
            }
        }
    }
    private fun message(error: Exception): String = when(error) {
        is SecurityException -> "没有文件访问权限，请重新选择文件"
        is java.io.IOException -> "文件读写失败，请检查文件、存储空间或访问权限后重试"
        else -> error.message?.takeIf { it.any { c -> c in '\u4e00'..'\u9fff' } }
            ?: "文件格式或内容不受支持，无法完成操作"
    }
    private fun fail(error: Exception) { mutable.value=mutable.value.copy(error=message(error),status="操作未完成，已保存的工程仍保留") }
    fun dismissError() { mutable.value=mutable.value.copy(error=null) }
    fun cancelOperation() { if(mutable.value.canCancel) actionJob?.cancel() }
    private fun action(status: String, block: suspend () -> Unit) {
        if (initialized.isCompleted && mutable.value.busy) return
        closeDetail()
        actionJob=viewModelScope.launch {
            initialized.await()
            actions.withLock {
                // Disable competing actions before waiting for an in-flight automatic save.
                mutable.value=mutable.value.copy(busy=true,saving=false,status=status,error=null,canCancel=true)
                try { autoSaveJob?.cancelAndJoin();block() }
                catch (cancelled: CancellationException) { mutable.value=mutable.value.copy(status="操作已取消，工程已保留");throw cancelled }
                catch (error: Exception) { fail(error) }
                catch (_: OutOfMemoryError) { mutable.value=mutable.value.copy(error="可用内存不足，请使用较小图片",rendering=false) }
                finally { mutable.value=mutable.value.copy(busy=false,canCancel=false);if (renderJob?.isCancelled == true && !mutable.value.home) render() }
            }
        }
    }
    private suspend fun refreshProjects() {
        val projects=withContext(Dispatchers.IO) { repository.library.entries() }
        mutable.value=mutable.value.copy(projects=projects)
    }
    private suspend fun install(id: String, loaded: Pair<Project,Bitmap>) {
        val (project,bitmap)=loaded
        source?.takeIf {it!==bitmap}?.recycle()
        interactiveSource?.recycle()
        source=bitmap
        interactiveSource=withContext(Dispatchers.Default) {ImagePipeline.interactiveSource(bitmap)}
        val inputInfo=withContext(Dispatchers.Default) {ImagePipeline.inspect(project.original,project.manifest.honorExifColorSpace)}
        history=History(project.manifest.edit);savedManifest=project.manifest
        mutable.value=mutable.value.copy(project=project,projectId=id,home=false,sourceNotice=inputInfo.notice,sourceWarning=inputInfo.warning,preview=null,regionOverlay=null,approximatePreview=false,dirty=false,
            canUndo=false,canRedo=false,tool=null,draft=null,cropAspect=null,comparing=false,status="所有修改已保存")
        render()
    }
    fun importPhoto(uri: Uri) = openNew("正在导入照片") { repository.importPhoto(uri) }
    fun open(uri: Uri) = openNew("正在读取工程") { repository.open(uri) }
    private fun openNew(status: String, read: () -> Pair<Project,Bitmap>) = action(status) {
        saveCurrentIfNeeded()
        renderJob?.cancelAndJoin()
        val loaded=withContext(Dispatchers.IO) { pixelWork.withLock { read() } }
        val id=withContext(Dispatchers.IO) { repository.library.create(loaded.first) }
        install(id,loaded);refreshProjects()
    }
    fun openProject(id: String) = action("正在读取工程") {
        saveCurrentIfNeeded();renderJob?.cancelAndJoin()
        val loaded=withContext(Dispatchers.IO) { pixelWork.withLock { repository.read(id).also { repository.library.markOpened(id) } } }
        install(id,loaded)
    }
    fun restore() { val latest=mutable.value.projects.firstOrNull { !it.damaged }?.id; if (latest!=null) openProject(latest) }
    fun home() = action("正在保存工程") {
        saveCurrentIfNeeded();renderJob?.cancelAndJoin();refreshProjects()
        mutable.value=mutable.value.copy(home=true,tool=null,draft=null,comparing=false,rendering=false,status="选择工程继续编辑")
    }
    fun renameProject(id: String, name: String) = action("正在重命名") {
        saveCurrentIfNeeded()
        withContext(Dispatchers.IO) { repository.library.rename(id,name) }
        if (mutable.value.projectId==id) {
            val updated=mutable.value.project!!.copy(manifest=mutable.value.project!!.manifest.copy(name=name.trim()))
            savedManifest=updated.manifest;mutable.value=mutable.value.copy(project=updated)
        }
        refreshProjects();mutable.value=mutable.value.copy(status="名称已保存")
    }
    fun duplicateProject(id: String) = action("正在复制工程") {
        saveCurrentIfNeeded();withContext(Dispatchers.IO) { repository.library.duplicate(id) };refreshProjects()
        mutable.value=mutable.value.copy(status="已创建独立副本")
    }
    fun deleteProject(id: String) = action("正在删除工程") {
        withContext(Dispatchers.IO) { repository.library.delete(id);repository.thumbnail(id).delete() }
        if (mutable.value.projectId==id) {
            renderJob?.cancelAndJoin();source?.recycle();source=null;interactiveSource?.recycle();interactiveSource=null;savedManifest=null
            mutable.value=mutable.value.copy(project=null,projectId=null,preview=null,home=true,tool=null,draft=null,dirty=false,rendering=false)
        }
        refreshProjects();mutable.value=mutable.value.copy(status="工程已删除")
    }
    fun thumbnail(id: String)=repository.thumbnail(id)
    fun setAppearance(value: AppAppearance) {
        preferences.edit().putString("appearance",value.name).apply()
        mutable.value=mutable.value.copy(appearance=value)
    }
    fun setCanvasBackground(value: CanvasBackground) {
        preferences.edit().putString("canvasBackground",value.name).apply()
        mutable.value=mutable.value.copy(canvasBackground=value)
    }
    fun beginTool(tool: EditorTool) {
        if (mutable.value.busy || mutable.value.tool==tool) return
        // Navigation must resolve the existing session explicitly before replacing it.
        if (mutable.value.tool!=null) return
        val edit=mutable.value.project?.manifest?.edit ?: return
        localHistory=History(edit)
        drawingMode=RegionMode.ADD
        mutable.value=mutable.value.copy(tool=tool,draft=edit,toolSession=mutable.value.toolSession+1,
            selectedParameter=null,
            comparing=false,localCanUndo=false,localCanRedo=false,retouchStep=RetouchStep.REGION,
            regionMode=RegionMode.ADD,showRegion=true)
        render()
    }
    internal fun beginParameter(parameter: EditorParameter) {
        beginTool(parameter.tool)
        selectParameter(parameter)
        if(parameter.tool==EditorTool.LOCAL) {
            if(mutable.value.draft?.local?.region?.isEmpty==false) retouchStep(RetouchStep.EFFECT)
            else regionMode(RegionMode.MOVE)
        }
    }
    internal fun selectParameter(parameter: EditorParameter) {
        if(mutable.value.tool==parameter.tool && !mutable.value.comparing) mutable.value=mutable.value.copy(selectedParameter=parameter.name)
    }
    fun fitCanvas() {mutable.value=mutable.value.copy(fitRevision=mutable.value.fitRevision+1)}
    fun regionMode(mode: RegionMode) {
        drawingMode=mode
        mutable.value=mutable.value.copy(regionMode=mode,retouchStep=if(mode==RegionMode.MOVE) mutable.value.retouchStep else RetouchStep.REGION)
    }
    fun retouchStep(step: RetouchStep) {
        val state=mutable.value
        if(state.tool!=EditorTool.LOCAL || state.busy || state.comparing || state.retouchStep==step) return
        if(step==RetouchStep.EFFECT) {
            drawingMode=state.regionMode
            mutable.value=state.copy(retouchStep=step,regionMode=RegionMode.MOVE,showRegion=false)
        } else mutable.value=state.copy(retouchStep=step,regionMode=drawingMode,showRegion=true)
    }
    fun showRegion(show: Boolean) {mutable.value=mutable.value.copy(showRegion=show)}
    fun brushRadius(value: Float) {mutable.value=mutable.value.copy(brushRadius=value)}
    fun brushHardness(value: Float) {mutable.value=mutable.value.copy(brushHardness=value)}
    fun localCheckpoint() {
        if(mutable.value.busy || mutable.value.comparing) return
        val edit=mutable.value.draft ?: return
        localHistory.commit(edit)
        mutable.value=mutable.value.copy(localCanUndo=localHistory.canUndo,localCanRedo=localHistory.canRedo)
    }
    fun localUndo() {
        if(mutable.value.busy || mutable.value.tool==null || mutable.value.comparing) return
        updateDraft(localHistory.undo())
        mutable.value=mutable.value.copy(localCanUndo=localHistory.canUndo,localCanRedo=localHistory.canRedo)
    }
    fun localRedo() {
        if(mutable.value.busy || mutable.value.tool==null || mutable.value.comparing) return
        updateDraft(localHistory.redo())
        mutable.value=mutable.value.copy(localCanUndo=localHistory.canUndo,localCanRedo=localHistory.canRedo)
    }
    fun addStroke(points: List<MaskPoint>) {
        val state=mutable.value;val edit=state.draft ?: return
        if(state.busy || state.comparing || state.regionMode==RegionMode.MOVE || state.tool!=EditorTool.LOCAL || points.isEmpty()) return
        val stroke=MaskStroke(points.toList(),state.brushRadius,state.brushHardness,state.regionMode==RegionMode.ERASE)
        val region=edit.local.region.copy(strokes=edit.local.region.strokes+stroke)
        try {region.validate();region.validateWork(state.project!!.manifest.width,state.project.manifest.height)} catch(error: IllegalArgumentException) {
            mutable.value=state.copy(error="区域笔画达到上限，请撤销部分笔画或清空区域");return
        }
        updateDraft(edit.copy(local=edit.local.copy(region=region)));localCheckpoint()
    }
    fun addDetailStroke(points: List<MaskPoint>, frame: DetailUi) {
        val current=mutable.value.detail ?: return
        // A gesture belongs to the displayed frame, never to a new location or comparison.
        if(current!==frame || current.loading || current.comparing || current.bitmap==null ||
            mutable.value.regionMode==RegionMode.MOVE) return
        addStroke(points)
    }
    fun clearRegion() {
        val edit=mutable.value.draft ?: return
        updateDraft(edit.copy(local=edit.local.copy(region=Region())));localCheckpoint()
    }
    fun suggestRegion() {
        val state=mutable.value
        val edit=state.draft ?: return
        val original=source ?: return
        val project=state.project ?: return
        if(state.tool!=EditorTool.LOCAL || state.busy || state.suggestion!=null) return
        action("正在本机分析人像；取消后会等待本次分析结束") {
            renderJob?.cancelAndJoin()
            var pendingOverlay: Bitmap?=null
            try {
                // The action cancels pending autosave; preserve already-applied edits, never this draft.
                saveCurrentIfNeeded()
                mutable.value=mutable.value.copy(status="正在本机分析人像；取消后会等待本次分析结束")
                val region=withContext(Dispatchers.Default) { pixelWork.withLock {
                    require(ImageMemory.available(getApplication()) >= 80L*1024*1024) {
                        "当前可用内存不足以分析人像，请继续手工涂选"
                    }
                    val job=currentCoroutineContext()
                    val result=PortraitRegion.suggest(getApplication(),original) {job.ensureActive()}
                    pendingOverlay=ImagePipeline.regionOverlay(project.manifest.width,project.manifest.height,
                        edit.copy(local=edit.local.copy(region=result)))
                    result
                }}
                mutable.value=mutable.value.copy(suggestion=region,suggestionOverlay=pendingOverlay,
                    status="请检查建议区域，采用后仍可手工修正")
                pendingOverlay=null
            } finally {
                pendingOverlay?.recycle()
                if(mutable.value.dirty) scheduleSave()
            }
        }
    }
    fun dismissSuggestion() {
        mutable.value=mutable.value.copy(suggestion=null,suggestionOverlay=null)
    }
    fun acceptSuggestion() {
        val state=mutable.value
        val region=state.suggestion ?: return
        val edit=state.draft ?: return
        dismissSuggestion()
        updateDraft(edit.copy(local=edit.local.copy(region=region)))
        localCheckpoint()
        mutable.value=mutable.value.copy(showRegion=true,regionMode=RegionMode.ERASE,
            retouchStep=RetouchStep.REGION,
            status="已采用建议区域，可擦除眼镜、胡须和遮挡；应用调整后保存")
    }
    fun cropAspect(aspect: Float?) {
        val project=mutable.value.project ?: return
        val edit=mutable.value.draft ?: return
        val (w,h)=Geometry.canvasSize(project.manifest.width,project.manifest.height,edit.turns)
        mutable.value=mutable.value.copy(cropAspect=aspect)
        updateDraft(edit.copy(crop=Geometry.centeredCrop(w,h,aspect)))
    }
    fun updateDraft(edit: Edit) {
        if(mutable.value.busy || mutable.value.tool==null || mutable.value.comparing) return
        val project=mutable.value.project ?: return
        edit.validate(project.manifest.width,project.manifest.height)
        val previous=mutable.value
        mutable.value=previous.copy(draft=edit,comparing=false)
        val keepDetail=previous.detail!=null && previous.tool==EditorTool.LOCAL &&
            previous.draft?.copy(local=edit.local)==edit
        if(keepDetail) {
            detailJob?.cancel();detailGeneration++;detailEdit=edit
            mutable.value=mutable.value.copy(detail=previous.detail!!.copy(bitmap=null,regionOverlay=null,
                loading=true,error=null,revision=previous.detail.revision+1))
        }
        // Crop handles change the overlay only; the full transformed photo underneath is unchanged.
        if (previous.tool!=EditorTool.CROP || previous.comparing ||
            previous.draft?.copy(crop=null)!=edit.copy(crop=null)) render(debounce=true,keepDetail=keepDetail)
    }
    fun applyTool() {
        if(mutable.value.busy) return
        val draft=mutable.value.draft ?: return
        mutable.value=mutable.value.copy(tool=null,draft=null,toolSession=mutable.value.toolSession+1)
        commit(draft)
    }
    fun cancelTool() { if(!mutable.value.busy) {mutable.value=mutable.value.copy(tool=null,draft=null,comparing=false,toolSession=mutable.value.toolSession+1);render()} }
    internal fun setParameter(parameter: EditorParameter, value: Float, session: Long) {
        val state=mutable.value
        if(state.toolSession!=session || state.tool!=parameter.tool || state.busy || state.comparing) return
        val draft=state.draft ?: return
        updateDraft(parameter.set(draft,value))
    }
    fun toolCheckpoint(session: Long) {if(mutable.value.toolSession==session) localCheckpoint()}
    fun commit(edit: Edit) {
        if (mutable.value.busy) return
        val project=mutable.value.project ?: return
        edit.validate(project.manifest.width,project.manifest.height)
        history.commit(edit);publishEdit(history.current)
    }
    fun undo() { if (!mutable.value.busy && mutable.value.tool==null) publishEdit(history.undo()) }
    fun redo() { if (!mutable.value.busy && mutable.value.tool==null) publishEdit(history.redo()) }
    private fun publishEdit(edit: Edit) {
        val project=mutable.value.project ?: return
        val updated=project.withEdit(edit)
        mutable.value=mutable.value.copy(project=updated,dirty=savedManifest!=updated.manifest,comparing=false,
            canUndo=history.canUndo,canRedo=history.canRedo,status="正在等待自动保存")
        render();scheduleSave()
    }
    // Kept as a direct preview boundary for sliders outside a tool transaction.
    fun previewEdit(edit: Edit) { if (!mutable.value.busy) render(edit,true) }
    fun compare() { mutable.value=mutable.value.copy(comparing=!mutable.value.comparing);render() }
    private fun render(temporary: Edit?=null, debounce: Boolean=false, keepDetail: Boolean=false) {
        if(!keepDetail) closeDetail()
        renderJob?.cancel()
        val state=mutable.value
        val project=state.project ?: return
        val original=source ?: return
        var parameters=temporary ?: state.draft ?: project.manifest.edit
        if (state.tool==EditorTool.CROP) parameters=parameters.copy(crop=null)
        if (state.comparing) parameters=Look.NEUTRAL.apply(parameters).copy(local=LocalAdjustment())
        val ticket=++generation
        mutable.value=mutable.value.copy(rendering=true,regionOverlay=null)
        renderJob=viewModelScope.launch {
            try {
                if (debounce) delay(80)
                val proxy=interactiveSource
                // The settled render uses the loaded source, which is a bounded proxy for large originals.
                if(proxy!=null) {
                    val fast=withContext(Dispatchers.Default) {pixelWork.withLock {
                        ImagePipeline.render(proxy,ImagePipeline.scaledEdit(parameters,project.manifest.width,project.manifest.height,proxy.width,proxy.height))
                    }}
                    if(ticket==generation) mutable.value=mutable.value.copy(preview=fast,approximatePreview=true)
                    else fast.recycle()
                    delay(250)
                }
                val (bitmap,overlay)=withContext(Dispatchers.Default) {
                    pixelWork.withLock {
                        val scaled=ImagePipeline.scaledEdit(parameters,project.manifest.width,project.manifest.height,original.width,original.height)
                        val bitmap=ImagePipeline.preview(ImagePipeline.render(original,scaled))
                        try {
                            val overlay=if(state.tool==EditorTool.LOCAL && !state.comparing)
                                ImagePipeline.regionOverlay(project.manifest.width,project.manifest.height,parameters) else null
                            bitmap to overlay
                        } catch(error: Throwable) {bitmap.recycle();throw error}
                    }
                }
                if (ticket==generation) {
                    mutable.value=mutable.value.copy(preview=bitmap,regionOverlay=overlay,rendering=false,approximatePreview=original.width!=project.manifest.width || original.height!=project.manifest.height)
                    if (state.tool==null && !state.comparing && state.projectId!=null) {
                        withContext(Dispatchers.IO) { runCatching { repository.saveThumbnail(state.projectId,bitmap) } }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value=mutable.value.copy(rendering=false,error=message(error)) }
            catch (_: OutOfMemoryError) { mutable.value=mutable.value.copy(rendering=false,error="预览内存不足，请使用较小图片") }
        }
    }
    fun openDetail() {
        val state=mutable.value
        val project=state.project ?: return
        if(state.busy || state.rendering || state.preview==null) return
        var edit=state.draft ?: project.manifest.edit
        if(state.tool==EditorTool.CROP) edit=edit.copy(crop=null)
        detailEdit=edit
        val (w,h)=Geometry.outputSize(project.manifest.width,project.manifest.height,edit)
        mutable.value=state.copy(detail=DetailUi(comparing=state.comparing,outputWidth=w,outputHeight=h))
    }
    fun closeDetail() {
        detailJob?.cancel();detailGeneration++;detailEdit=null
        if(mutable.value.detail!=null) mutable.value=mutable.value.copy(detail=null)
    }
    fun detailCenter(point: MaskPoint) {
        val d=mutable.value.detail ?: return
        if(!point.x.isFinite() || !point.y.isFinite()) return
        val bounded=MaskPoint(point.x.coerceIn(0f,1f),point.y.coerceIn(0f,1f))
        if(bounded==d.center) return
        detailJob?.cancel();detailGeneration++
        mutable.value=mutable.value.copy(detail=d.copy(center=bounded,
            bitmap=null,regionOverlay=null,bounds=null,error=null,loading=true))
    }
    fun detailCompare() {
        val d=mutable.value.detail ?: return
        detailJob?.cancel();detailGeneration++
        mutable.value=mutable.value.copy(detail=d.copy(comparing=!d.comparing,bitmap=null,regionOverlay=null,error=null,loading=true))
    }
    fun loadDetail(width: Int, height: Int) {
        val d=mutable.value.detail ?: return
        val project=mutable.value.project ?: return
        var edit=detailEdit ?: return
        val local=mutable.value.tool==EditorTool.LOCAL && !d.comparing
        if(d.comparing) edit=Look.NEUTRAL.apply(edit).copy(local=LocalAdjustment())
        detailJob?.cancel()
        val ticket=++detailGeneration
        mutable.value=mutable.value.copy(detail=d.copy(loading=true,bitmap=null,regionOverlay=null,error=null))
        detailJob=viewModelScope.launch {
            var pending: DetailRenderer.Frame?=null
            var overlay: Bitmap?=null
            try {
                withContext(Dispatchers.Default) { pixelWork.withLock {
                    val required=project.original.size.toLong()+64L*1024*1024+ImagePipeline.inspect(project.original,project.manifest.honorExifColorSpace).codecHeadroom
                    if(required>ImageMemory.available(getApplication())) System.gc()
                    require(required<=ImageMemory.available(getApplication())) {"当前内存不足以查看细节，请稍后重试；工程仍保留"}
                    pending=DetailRenderer.render(project,edit,d.center,width,height)
                    if(local) overlay=DetailRenderer.regionOverlay(project.manifest.width,project.manifest.height,edit,pending!!.bounds)
                }}
                val frame=requireNotNull(pending)
                if(ticket==detailGeneration && mutable.value.detail!=null) mutable.value=mutable.value.copy(
                    detail=mutable.value.detail!!.copy(loading=false,bitmap=frame.bitmap,bounds=frame.bounds,regionOverlay=overlay))
                else {frame.bitmap.recycle();overlay?.recycle()}
                overlay=null
                pending=null
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(error: Exception) {if(ticket==detailGeneration) mutable.value=mutable.value.copy(
                detail=mutable.value.detail?.copy(loading=false,error=message(error)))}
            catch(_: OutOfMemoryError) {if(ticket==detailGeneration) mutable.value=mutable.value.copy(
                detail=mutable.value.detail?.copy(loading=false,error="查看细节时内存不足；请关闭细节视图，工程仍保留"))}
            finally {pending?.bitmap?.recycle();overlay?.recycle()}
        }
    }

    private fun scheduleSave() {
        autoSaveJob?.cancel()
        val ticket=++saveGeneration
        val project=mutable.value.project ?: return
        val id=mutable.value.projectId ?: return
        mutable.value=mutable.value.copy(saving=true)
        autoSaveJob=viewModelScope.launch {
            try {
                delay(300)
                persist(id,project)
                if (mutable.value.projectId==id && mutable.value.project?.manifest==project.manifest) {
                    savedManifest=project.manifest
                    mutable.value=mutable.value.copy(dirty=false,status="所有修改已保存")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value=mutable.value.copy(error=message(error),status="自动保存失败，请重试保存") }
            finally { if (ticket==saveGeneration) mutable.value=mutable.value.copy(saving=false) }
        }
    }
    private suspend fun persist(id: String, project: Project) = withContext(Dispatchers.IO) {
        saveWork.withLock { currentCoroutineContext().ensureActive();repository.library.save(id,project) }
    }
    private suspend fun saveCurrentIfNeeded() {
        val project=mutable.value.project ?: return
        val id=mutable.value.projectId ?: return
        if (savedManifest!=project.manifest) {
            persist(id,project);savedManifest=project.manifest
            mutable.value=mutable.value.copy(dirty=false,status="所有修改已保存")
        }
    }
    fun save() = action("正在保存工程") { saveCurrentIfNeeded();mutable.value=mutable.value.copy(status="所有修改已保存") }
    fun setExportSettings(settings: ExportSettings) {
        mutable.value=mutable.value.copy(exportSettings=settings)
        preferences.edit().putString("format",settings.format.name).putInt("quality",settings.quality)
            .putInt("edge",settings.maxEdge ?: 0).apply()
    }
    internal suspend fun photoExportAssessment(edit: Edit, settings: ExportSettings): PhotoExportAssessment? {
        val project=mutable.value.project ?: return null
        val bitmap=source ?: return null
        val width=bitmap.width;val height=bitmap.height
        return withContext(Dispatchers.Default) {
            val manifest=project.manifest.copy(edit=edit)
            val codec=ImagePipeline.inspect(project.original,manifest.honorExifColorSpace).codecHeadroom
            var assessment=assessPhotoExport(manifest,settings,width,height,codec,ImageMemory.available(getApplication()))
            if(!assessment.fits) {
                // Match export admission's one collection before reporting a refusal.
                System.gc()
                currentCoroutineContext().ensureActive()
                assessment=assessPhotoExport(manifest,settings,width,height,codec,ImageMemory.available(getApplication()))
            }
            assessment
        }
    }
    fun prepareExport(destination: ExportDestination) = action("正在保存修改") {
        saveCurrentIfNeeded()
        val settings=mutable.value.exportSettings
        if (destination == ExportDestination.PHOTO) {
            renderJob?.cancelAndJoin()
            withContext(Dispatchers.Default) {
                repository.checkPhotoExport(requireNotNull(mutable.value.project), requireNotNull(source), settings)
            }
        }
        withContext(Dispatchers.IO) {
            check(preferences.edit().putString("format",settings.format.name).putInt("quality",settings.quality)
                .putInt("edge",settings.maxEdge ?: 0).commit()) { "无法保存导出设置，请检查存储空间" }
        }
        mutable.value=mutable.value.copy(exportRequest=destination,status="请选择保存位置")
    }
    fun cancelExportSelection() {mutable.value=mutable.value.copy(status="已取消导出")}
    fun consumeExportRequest() {mutable.value=mutable.value.copy(exportRequest=null)}
    private suspend fun exportPhase(phase: ExportPhase) = withContext(Dispatchers.Main.immediate) {
        mutable.value=mutable.value.copy(status=phase.label,canCancel=phase.cancellable)
    }
    fun exportProject(uri: Uri) = action("正在导出工程") {
        saveCurrentIfNeeded()
        val project=requireNotNull(mutable.value.project)
        withContext(Dispatchers.IO) { repository.exportProject(project,uri,::exportPhase) }
        mutable.value=mutable.value.copy(status="可编辑工程已导出")
    }
    fun exportPhoto(uri: Uri, format: PhotoFormat? = null) = action("正在导出照片") {
        saveCurrentIfNeeded();renderJob?.cancelAndJoin()
        val state=mutable.value
        withContext(Dispatchers.Default) { pixelWork.withLock {
            repository.exportPhoto(requireNotNull(state.project),requireNotNull(source),uri,
                state.exportSettings.copy(format=format ?: state.exportSettings.format),::exportPhase)
        }}
        mutable.value=mutable.value.copy(status="照片已导出，未复制原图元数据")
    }
    fun sharePhoto() = action("正在准备分享") {
        saveCurrentIfNeeded();renderJob?.cancelAndJoin();val state=mutable.value
        val uri=withContext(Dispatchers.Default) { pixelWork.withLock {
            repository.sharePhoto(requireNotNull(state.project),requireNotNull(source),state.exportSettings,::exportPhase)
        }}
        mutable.value=mutable.value.copy(sharedPhoto=SharedPhoto(uri,state.exportSettings.format.mime),status="照片已准备好")
    }
    fun consumeShare() { mutable.value=mutable.value.copy(sharedPhoto=null) }
}
