// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import dev.nijiframe.editor.PhotoFormat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nijiframe.editor.Geometry
import dev.nijiframe.editor.ImageSizing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportSheet(ui: EditorUi,editor: EditorViewModel,onDismiss: ()->Unit,
                         onSavePhoto: ()->Unit,onSaveProject: ()->Unit,onShare: ()->Unit) {
    var projectOutput by rememberSaveable {mutableStateOf(false)}
    var sizes by remember {mutableStateOf(false)}
    val settings=ui.exportSettings
    val project=requireNotNull(ui.project)
    val edit=ui.draft ?: project.manifest.edit
    val (width,height)=Geometry.outputSize(project.manifest.width,project.manifest.height,edit)
    val (outWidth,outHeight)=settings.dimensions(width,height)
    val pending=ui.draft!=null && ui.draft!=project.manifest.edit
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("导出与分享",style=MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=!projectOutput,onClick={projectOutput=false},label={Text("照片")})
                FilterChip(selected=projectOutput,onClick={projectOutput=true},label={Text("可编辑工程")})
            }
            if(projectOutput) Text("包含原始照片、编辑参数与选区，可继续修改。",style=MaterialTheme.typography.bodyMedium)
            else {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    PhotoFormat.entries.forEach {format ->
                        FilterChip(selected=settings.format==format,onClick={editor.setExportSettings(settings.copy(format=format))},label={Text(format.name)})
                    }
                }
                if(settings.format==PhotoFormat.JPEG) ParameterControl("export-quality","JPEG 质量",settings.quality.toFloat(),1f..100f,95f,
                    ruler=false,description="JPEG 质量",onChange={editor.setExportSettings(settings.copy(quality=it.toInt()))},onFinished={})
                Box {
                    OutlinedButton(onClick={sizes=true},modifier=Modifier.fillMaxWidth()) {Text("$outWidth × $outHeight · ${settings.maxEdge?.let {"长边 $it"} ?: "原尺寸"}")}
                    DropdownMenu(expanded=sizes,onDismissRequest={sizes=false}) {
                        listOf(null to "原尺寸",4000 to "长边 4000",2048 to "长边 2048",1280 to "长边 1280").forEach {(edge,label) ->
                            DropdownMenuItem(text={Text(label)},onClick={sizes=false;editor.setExportSettings(settings.copy(maxEdge=edge))})
                        }
                    }
                }
                if(ImageSizing.previewSample(project.manifest.width,project.manifest.height)>1)
                    Text("原图完整保留。原尺寸输出需要更多内存，可选择较小尺寸降低负担。",style=MaterialTheme.typography.bodySmall)
                Text("照片输出为 8 位 sRGB / SDR，不附带位置等原始元数据。",style=MaterialTheme.typography.bodySmall)
            }
            if(pending) Text("确认后会应用并保存当前调整；关闭面板可继续编辑。",style=MaterialTheme.typography.bodySmall)
            Button(shape=androidx.compose.foundation.shape.RoundedCornerShape(8.dp),onClick=if(projectOutput) onSaveProject else onSavePhoto,modifier=Modifier.fillMaxWidth()) {
                Text(if(pending) "应用并导出" else if(projectOutput) "导出可编辑工程" else "保存照片")
            }
            if(!projectOutput) TextButton(onClick=onShare,modifier=Modifier.fillMaxWidth()) {Text(if(pending) "应用并分享" else "分享照片")}
            TextButton(onClick=onDismiss,modifier=Modifier.fillMaxWidth()) {Text("取消")}
            Spacer(Modifier.height(12.dp))
        }
    }
}
