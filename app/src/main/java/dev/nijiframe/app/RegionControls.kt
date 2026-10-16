// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt
import dev.nijiframe.editor.*

@Composable
internal fun RegionPicker(ui: EditorUi, editor: EditorViewModel) {
    val draft=ui.draft ?: return
    val regions=draft.localRegions
    val active=regions.firstOrNull {it.id==draft.selectedRegionId} ?: regions.first()
    val enabled=!ui.busy && !ui.comparing
    var manage by remember {mutableStateOf(false)}
    var choose by remember {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick={choose=true},enabled=enabled,modifier=Modifier.semantics {
                contentDescription="切换选区"
                stateDescription="${active.name}，第 ${regions.indexOf(active)+1} 个，共 ${regions.size} 个，"+if(active.enabled) "效果已启用" else "效果已关闭"
            }) {
                Text("${active.name} · ${regions.indexOf(active)+1}/${regions.size}"+if(active.enabled) "" else " · 已关闭",maxLines=1,
                    overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded=choose,onDismissRequest={choose=false}) {
                regions.forEach {r ->
                    DropdownMenuItem(text={Text(r.name+if(r.enabled) "" else " · 已关闭")},
                        onClick={choose=false;editor.selectRegion(r.id)},
                        modifier=Modifier.semantics {
                            contentDescription="选择选区 ${r.name}";selected=r.id==active.id
                            stateDescription=if(r.enabled) "效果已启用" else "效果已关闭"
                        })
                }
            }
        }
        TextButton(onClick=editor::addRegion,enabled=enabled && regions.size<LocalRegion.MAX_REGIONS) {Text("新增选区")}
        TextButton(onClick={manage=true},enabled=enabled) {Text("管理选区")}
    }
    if(manage) {
        var name by remember(active.id) {mutableStateOf(active.name)}
        AlertDialog(onDismissRequest={manage=false},title={Text("管理选区 · ${active.name}")},text={
            Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value=name,onValueChange={name=it.take(40)},label={Text("选区名称")},singleLine=true)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=active.enabled,onCheckedChange=editor::enableRegion,
                        modifier=Modifier.semantics {contentDescription="启用选区效果"})
                    Text("启用选区效果")
                }
                TextButton(onClick=editor::resetRegionEffects) {Text("复位本区效果")}
                TextButton(onClick={editor.deleteRegion();manage=false}) {Text("删除选区")}
                Text("${regions.size} / ${LocalRegion.MAX_REGIONS} 个选区 · 可撤销本次操作",style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton={TextButton(onClick={editor.renameRegion(name);manage=false},
            enabled=name.isNotBlank() && name.none {it.isISOControl()}) {Text("保存名称")}},
            dismissButton={TextButton(onClick={manage=false}) {Text("关闭")}})
    }
}

@Composable
internal fun RegionModes(ui: EditorUi, editor: EditorViewModel, detail: Boolean=false, enabled: Boolean=true) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        RegionMode.entries.forEach { mode ->
            FilterChip(selected=ui.regionMode==mode,onClick={editor.regionMode(mode)},enabled=enabled,
                label={Text(if(detail && mode==RegionMode.MOVE) "仅查看" else mode.label)})
        }
    }
    val instruction=when {
        !enabled -> "原图对比中 · 暂停绘制"
        ui.regionMode==RegionMode.ADD -> "添加区域 · 涂抹需要调整的位置"
        ui.regionMode==RegionMode.ERASE -> "擦除区域 · 移除不需要调整的位置"
        detail -> "仅查看 · 点击上方概览定位"
        else -> "移动画布 · 双指缩放，不绘制"
    }
    Text(instruction,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
}

@Composable
internal fun RegionVisibility(ui: EditorUi, editor: EditorViewModel, enabled: Boolean=true) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked=ui.showRegion,onCheckedChange=editor::showRegion,enabled=enabled,
            modifier=Modifier.semantics {contentDescription="显示区域"})
        val region=ui.draft?.activeLocal?.region
        Text("显示区域 · ${(region?.strokes?.size ?: 0)+(region?.refinements?.size ?: 0)} 笔"+if(region?.base!=null) " · 含建议" else "",
            style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
        TextButton(onClick=editor::clearRegion,enabled=enabled && region?.isEmpty==false) {Text("清空区域")}
    }
}

@Composable
internal fun BrushSettings(ui: EditorUi, editor: EditorViewModel, enabled: Boolean=true, detail: Boolean=false) {
    var hardness by rememberSaveable {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        FilterChip(selected=!hardness,onClick={hardness=false},label={Text("笔刷大小")},enabled=enabled)
        FilterChip(selected=hardness,onClick={hardness=true},label={Text("边缘硬度")},enabled=enabled)
    }
    if(hardness) {
        key("hardness") {
            ParameterControl("brush-hardness","边缘硬度",ui.brushHardness*100,0f..100f,50f,"%",ruler=false,
                description="区域边缘硬度",enabled=enabled,onChange={editor.brushHardness(it/100)},onFinished={})
        }
    } else if(detail) {
        key("fine-radius") {
            ParameterControl("fine-brush-radius","精细笔刷直径",ui.fineBrushDiameter,1f..128f,8f,"px",ruler=false,
                description="原图像素笔刷直径",enabled=enabled,onChange=editor::fineBrushDiameter,onFinished={})
        }
        Text("按原图像素修正，仅影响当前选区",style=MaterialTheme.typography.labelSmall)
    } else {
        val m=ui.project?.manifest
        val pixels=if(m==null) 0 else (ui.brushRadius*2*min(m.width,m.height)).roundToInt()
        key("radius") {
            ParameterControl("brush-radius","笔刷直径",ui.brushRadius*200,1f..50f,12f,"%",ruler=false,step=.1f,
                description="区域笔刷大小",enabled=enabled,onChange={editor.brushRadius(it/200)},onFinished={})
        }
        Text("$pixels 原图像素",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
