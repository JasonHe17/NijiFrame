// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import dev.nijiframe.editor.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val editor: EditorViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ui by editor.ui.collectAsStateWithLifecycle()
            NijiTheme(ui.appearance,ui.canvasBackground) {
                val dark=LocalEditorDark.current
                SideEffect {WindowCompat.getInsetsController(window,window.decorView).apply {
                    isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
                }}
                NijiFrame(editor)
            }
        }
    }
}

@Composable
internal fun NijiFrame(editor: EditorViewModel) {
    val ui by editor.ui.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(editor::importPhoto) }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(editor::open) }
    val projectExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { if(it==null) editor.cancelExportSelection() else editor.exportProject(it) }
    val jpegExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { if(it==null) editor.cancelExportSelection() else editor.exportPhoto(it,PhotoFormat.JPEG) }
    val pngExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { if(it==null) editor.cancelExportSelection() else editor.exportPhoto(it,PhotoFormat.PNG) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showReset by rememberSaveable { mutableStateOf(false) }
    var showBack by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showAppearance by rememberSaveable {mutableStateOf(false)}
    var backToHome by rememberSaveable {mutableStateOf(false)}
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    fun back(homeRequested: Boolean=false) {
        if(ui.comparing) {editor.compare();return}
        if(!homeRequested && ui.tool==EditorTool.LOCAL && ui.retouchStep==RetouchStep.EFFECT) {
            editor.retouchStep(RetouchStep.REGION);return
        }
        if(ui.draft!=null && ui.draft!=ui.project?.manifest?.edit) {backToHome=homeRequested;showBack=true}
        else if(ui.draft!=null) {editor.cancelTool();if(homeRequested) editor.home()}
        else editor.home()
    }
    BackHandler(enabled=!ui.home && !ui.busy) { back() }
    LaunchedEffect(ui.exportRequest) {
        ui.exportRequest?.let { destination ->
            if(destination==ExportDestination.PROJECT) projectExport.launch("NijiFrame.nijiproj")
            else if(ui.exportSettings.format==PhotoFormat.JPEG) jpegExport.launch("NijiFrame.jpg")
            else pngExport.launch("NijiFrame.png")
            editor.consumeExportRequest()
        }
    }
    LaunchedEffect(ui.sharedPhoto) {
        ui.sharedPhoto?.let { photo ->
            val intent=Intent(Intent.ACTION_SEND).apply {
                type=photo.mime;putExtra(Intent.EXTRA_STREAM,photo.uri)
                clipData=ClipData.newUri(context.contentResolver,"NijiFrame",photo.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent,"分享照片"))
            editor.consumeShare()
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            if(ui.home) {
                Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("NijiFrame",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f))
                    TextButton(onClick={showAppearance=true}) {Text("外观")}
                    TextButton(onClick={showAbout=true}) { Text("关于") }
                }
                FlowRow(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(shape=androidx.compose.foundation.shape.RoundedCornerShape(8.dp),onClick={import.launch(PhotoInput.mimeTypes)},enabled=!ui.busy) { Text("导入照片") }
                    OutlinedButton(onClick={open.launch(arrayOf("*/*"))},enabled=!ui.busy) { Text("打开工程") }
                }
                Text("我的工程",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(16.dp))
                if(ui.projects.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.Center,
                        horizontalAlignment=Alignment.CenterHorizontally) {
                        Text("从一张照片开始",style=MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(12.dp))
                        Text("调整明暗与颜色，重新构图，随时保存并继续编辑。")
                        Text("支持 JPEG、PNG、WebP、HEIC/HEIF、AVIF；照片在本机处理。",style=MaterialTheme.typography.bodySmall,
                            modifier=Modifier.padding(top=12.dp))
                    }
                } else LazyVerticalGrid(columns=GridCells.Adaptive(164.dp),modifier=Modifier.weight(1f),
                    contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),
                    horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(ui.projects,key={it.id}) { entry ->
                        ProjectCard(entry,editor,onRename={renameId=entry.id;renameText=entry.name},onDelete={deleteId=entry.id})
                    }
                }
                Text(ui.status,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(16.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={back(true)},enabled=!ui.busy,modifier=Modifier.semantics {contentDescription="项目"}) {EditorGlyph(Glyph.BACK)}
                    TextButton(onClick={showInfo=true},modifier=Modifier.weight(1f)
                        .semantics {contentDescription="工程与照片信息"}) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(ui.project?.manifest?.name.orEmpty(),maxLines=1,overflow=TextOverflow.Ellipsis,
                                style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurface)
                            Text(editorSaveLabel(ui),style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                    TextButton(onClick={showExport=true},enabled=!ui.busy) {Text("导出")}
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick={menu=true},enabled=!ui.busy,modifier=Modifier.semantics {contentDescription="编辑菜单"}) { EditorGlyph(Glyph.MORE) }
                        DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                            DropdownMenuItem(text={Text("工程与照片信息")},onClick={menu=false;showInfo=true})
                            DropdownMenuItem(text={Text("外观与查看")},onClick={menu=false;showAppearance=true})
                            DropdownMenuItem(text={Text("原尺寸细节")},enabled=!ui.rendering && ui.preview!=null,onClick={menu=false;editor.openDetail()})
                            DropdownMenuItem(text={Text("保存工程")},onClick={menu=false;editor.applyTool();editor.save()})
                            DropdownMenuItem(text={Text("重命名")},onClick={menu=false;renameId=ui.projectId;renameText=ui.project?.manifest?.name.orEmpty()})
                            DropdownMenuItem(text={Text("重置全部调整")},onClick={menu=false;showReset=true})
                            DropdownMenuItem(text={Text("关于与许可")},onClick={menu=false;showAbout=true})
                        }
                    }
                }
                if(ui.status.startsWith("照片已导出") || ui.status=="可编辑工程已导出" || ui.status=="已取消导出") {
                    Text(ui.status,Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall)
                }
                ui.sourceWarning?.let {Text(it,Modifier.padding(horizontal=16.dp,vertical=4.dp),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                BoxWithConstraints(Modifier.weight(1f)) {
                    val panelMaxHeight=minOf(if(ui.tool==EditorTool.LOCAL) 360.dp else 330.dp,maxHeight*.62f)
                    if(maxWidth>600.dp) Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            EditorCanvas(ui,editor,Modifier.weight(1f).fillMaxWidth())
                            EditorActions(ui,editor)
                        }
                        ToolPanel(ui,editor,Modifier.width(330.dp).fillMaxHeight())
                    } else Column(Modifier.fillMaxSize()) {
                        EditorCanvas(ui,editor,Modifier.weight(1f).fillMaxWidth())
                        EditorActions(ui,editor)
                        ToolPanel(ui,editor,Modifier.fillMaxWidth().heightIn(max=panelMaxHeight))
                    }
                }
            }
        }
    }
    if(showAppearance) AppearanceDialog(ui,editor) {showAppearance=false}
    if(showInfo) ProjectInfoDialog(ui) {showInfo=false}
    if(ui.suggestion!=null) RegionSuggestionDialog(ui,editor)
    if(ui.detail!=null) DetailInspector(ui,editor)
    if(showExport) ExportSheet(ui,editor,onDismiss={showExport=false},onSavePhoto={
        showExport=false
        editor.applyTool();editor.prepareExport(ExportDestination.PHOTO)
    },onSaveProject={showExport=false;editor.applyTool();editor.prepareExport(ExportDestination.PROJECT)},onShare={showExport=false;editor.applyTool();editor.sharePhoto()})
    if(showReset) AlertDialog(onDismissRequest={showReset=false},title={Text("重置全部调整？")},
        text={Text("将恢复原图构图、明暗和颜色。这一步可以撤销。")},
        confirmButton={TextButton(onClick={showReset=false;editor.cancelTool();editor.commit(Edit())}) {Text("重置")}},
        dismissButton={TextButton(onClick={showReset=false}) {Text("取消")}})
    if(showBack) AlertDialog(onDismissRequest={showBack=false},title={Text("当前工具还有未完成的调整")},
        text={Text("应用后会自动保存，或放弃本次工具中的修改。")},
        confirmButton={TextButton(onClick={showBack=false;editor.applyTool();if(backToHome) editor.home()}) {Text("应用并返回")}},
        dismissButton={Column {
            TextButton(onClick={showBack=false;editor.cancelTool();if(backToHome) editor.home()}) {Text("放弃本次调整")}
            TextButton(onClick={showBack=false}) {Text("继续编辑")}
        }})
    renameId?.let { id -> AlertDialog(onDismissRequest={renameId=null},title={Text("重命名工程")},
        text={OutlinedTextField(value=renameText,onValueChange={renameText=it.take(80)},singleLine=true,label={Text("工程名称")})},
        confirmButton={TextButton(enabled=renameText.isNotBlank(),onClick={renameId=null;editor.renameProject(id,renameText)}) {Text("保存名称")}},
        dismissButton={TextButton(onClick={renameId=null}) {Text("取消")}}) }
    deleteId?.let { id -> AlertDialog(onDismissRequest={deleteId=null},title={Text("删除此工程？")},
        text={Text("将删除本应用内的原图副本和编辑记录。外部原图及已导出文件不受影响。")},
        confirmButton={TextButton(onClick={deleteId=null;editor.deleteProject(id)}) {Text("删除工程")}},
        dismissButton={TextButton(onClick={deleteId=null}) {Text("取消")}}) }
    if(showAbout) AboutDialog { showAbout=false }
    if(ui.busy) Dialog(onDismissRequest={}) { Surface(shape=MaterialTheme.shapes.large) {
        Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator();Text(ui.status)
            if(ui.canCancel) TextButton(onClick=editor::cancelOperation) {Text("取消操作")}
        }
    }}
    ui.error?.let { AlertDialog(onDismissRequest=editor::dismissError,title={Text("操作未完成")},
        text={Text(it)},confirmButton={TextButton(onClick=editor::dismissError) {Text("知道了")}},
        dismissButton={if(ui.dirty) TextButton(onClick={editor.dismissError();editor.save()}) {Text("重试保存")}}) }
}

@Composable
private fun ProjectCard(entry: ProjectLibrary.Entry, editor: EditorViewModel, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val thumbnail by produceState<android.graphics.Bitmap?>(null,entry.id,entry.modified) {
        value=withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(editor.thumbnail(entry.id).path) }
    }
    Card(onClick={editor.openProject(entry.id)}) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).background(MaterialTheme.colorScheme.surfaceContainerHigh),contentAlignment=Alignment.Center) {
            thumbnail?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop) }
                ?: Text(if(entry.damaged) "工程需要检查" else "照片工程")
        }
        Row(Modifier.padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall)
                Text("${entry.width} × ${entry.height}",style=MaterialTheme.typography.labelSmall)
                Text(DateFormat.getDateInstance(DateFormat.SHORT).format(Date(entry.modified)),style=MaterialTheme.typography.labelSmall)
            }
            Box {
                IconButton(onClick={menu=true},modifier=Modifier.semantics {contentDescription="${entry.name} 更多操作"}) {EditorGlyph(Glyph.MORE)}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text("重命名")},enabled=!entry.damaged,onClick={menu=false;onRename()})
                    DropdownMenuItem(text={Text("创建副本")},enabled=!entry.damaged,onClick={menu=false;editor.duplicateProject(entry.id)})
                    DropdownMenuItem(text={Text("删除工程")},onClick={menu=false;onDelete()})
                }
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context=LocalContext.current
    var licenses by remember { mutableStateOf(false) }
    val text by produceState("",licenses) { if(licenses) value=withContext(Dispatchers.IO) {
        context.assets.open("open-source/THIRD_PARTY_NOTICES.md").bufferedReader().use {it.readText()}+
            "\n\n"+context.assets.open("open-source/LICENSE").bufferedReader().use {it.readText()}
    }}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(licenses) "开源许可" else "关于 NijiFrame")},
        text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
            Text(if(licenses) text else "${BuildConfig.VERSION_NAME} · 构建 ${BuildConfig.VERSION_CODE}\n源码 ${BuildConfig.SOURCE_REVISION}\n\n本地照片编辑器 · GPL-3.0-only\n\n照片在本机处理。支持常见广色域 JPEG、8/16 位 PNG、WebP，以及系统可解码的 HEIC/HEIF、AVIF；按 sRGB／SDR 编辑和导出，保留原文件。暂不支持 RAW、TIFF、透明像素或独立 HLG/PQ HDR，当前上限 2.1 亿像素、64 MiB，单边不超过 32768 像素。\n\n工程自动保存在本应用中；卸载前请导出重要工程。提供实验柔肤；Windows 接力仍在后续规划中。")
            if(!licenses) TextButton(onClick={licenses=true}) {Text("查看开源许可")}
        }},confirmButton={TextButton(onClick=onDismiss) {Text("关闭")}})
}
