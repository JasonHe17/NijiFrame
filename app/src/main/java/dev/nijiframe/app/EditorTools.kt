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
import androidx.compose.ui.unit.dp
import dev.nijiframe.editor.*
import java.util.Locale

private enum class Adjustment(val label: String, val range: ClosedFloatingPointRange<Float>) {
    EXPOSURE("曝光",-3f..3f), CONTRAST("对比度",-1f..1f), HIGHLIGHTS("高光",-1f..1f), SHADOWS("阴影",-1f..1f),
    WHITES("白色",-1f..1f), BLACKS("黑色",-1f..1f), SATURATION("饱和度",0f..2f), VIBRANCE("鲜艳度",-1f..1f),
    WARMTH("色温",-1f..1f), TINT("色调",-1f..1f), SHARPEN("锐化",0f..2f), VIGNETTE("暗角",-1f..1f);
    fun get(e: Edit): Float = when(this) {
        EXPOSURE->e.exposureEv;CONTRAST->e.contrast;HIGHLIGHTS->e.highlights;SHADOWS->e.shadows;
        WHITES->e.whites;BLACKS->e.blacks;SATURATION->e.saturation;VIBRANCE->e.vibrance;
        WARMTH->e.warmth;TINT->e.tint;SHARPEN->e.sharpen;VIGNETTE->e.vignette
    }
    fun set(e: Edit,v: Float): Edit = when(this) {
        EXPOSURE->e.copy(exposureEv=v);CONTRAST->e.copy(contrast=v);HIGHLIGHTS->e.copy(highlights=v);SHADOWS->e.copy(shadows=v);
        WHITES->e.copy(whites=v);BLACKS->e.copy(blacks=v);SATURATION->e.copy(saturation=v);VIBRANCE->e.copy(vibrance=v);
        WARMTH->e.copy(warmth=v);TINT->e.copy(tint=v);SHARPEN->e.copy(sharpen=v);VIGNETTE->e.copy(vignette=v)
    }
}

@Composable
internal fun ToolPanel(ui: EditorUi, editor: EditorViewModel, modifier: Modifier=Modifier) {
    Surface(modifier,tonalElevation=3.dp) {
        Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                EditorTool.entries.forEach { tool ->
                    FilterChip(selected=ui.tool==tool,onClick={editor.beginTool(tool)},enabled=!ui.busy,label={Text(tool.label)})
                }
            }
            val tool=ui.tool;val draft=ui.draft
            if(tool==null || draft==null) {
                Text("选择工具开始调整",style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(12.dp))
                Text("双指缩放，拖动画布；双击切换缩放。已应用的调整会自动保存。",style=MaterialTheme.typography.bodySmall,
                    modifier=Modifier.padding(horizontal=12.dp,vertical=4.dp))
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                when(tool) {
                    EditorTool.LOCAL -> LocalPanel(ui,editor,draft)
                    EditorTool.LIGHT -> AdjustmentPanel(draft,editor,listOf(Adjustment.EXPOSURE,Adjustment.CONTRAST,
                        Adjustment.HIGHLIGHTS,Adjustment.SHADOWS,Adjustment.WHITES,Adjustment.BLACKS))
                    EditorTool.COLOR -> AdjustmentPanel(draft,editor,listOf(Adjustment.SATURATION,Adjustment.VIBRANCE,Adjustment.WARMTH,Adjustment.TINT))
                    EditorTool.DETAILS -> AdjustmentPanel(draft,editor,listOf(Adjustment.SHARPEN,Adjustment.VIGNETTE))
                    EditorTool.CROP -> {
                        Text("拖动四角调整范围，拖动框内移动",style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            val manifest=requireNotNull(ui.project).manifest
                            val (w,h)=Geometry.canvasSize(manifest.width,manifest.height,draft.turns)
                            listOf(null to "自由",w.toFloat()/h to "原图",1f to "1:1",4f/3 to "4:3",3f/4 to "3:4",16f/9 to "16:9",9f/16 to "9:16").forEach { (ratio,label) ->
                                FilterChip(selected=ui.cropAspect==ratio,onClick={editor.cropAspect(ratio)},label={Text(label)})
                            }
                        }
                        val crop=draft.crop
                        Text(if(crop==null) "整张照片" else "${crop.width} × ${crop.height}",style=MaterialTheme.typography.labelMedium)
                        TextButton(onClick={editor.cropAspect(null);editor.updateDraft(draft.copy(crop=null))}) {Text("恢复完整画面")}
                    }
                    EditorTool.GEOMETRY -> {
                        val project=requireNotNull(ui.project).manifest
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick={editor.updateDraft(Geometry.rotate(draft,project.width,project.height))}) {Text("旋转 90°")}
                            TextButton(onClick={editor.updateDraft(Geometry.flip(draft,project.width,project.height,true))}) {Text("水平翻转")}
                            TextButton(onClick={editor.updateDraft(Geometry.flip(draft,project.width,project.height,false))}) {Text("垂直翻转")}
                        }
                        Text("校正 ${String.format(Locale.ROOT,"%.1f°",draft.straighten)}",style=MaterialTheme.typography.labelLarge)
                        Slider(value=draft.straighten,onValueChange={editor.updateDraft(draft.copy(straighten=it))},valueRange=-45f..45f,
                            modifier=Modifier.semantics {contentDescription="校正角度"})
                        TextButton(onClick={editor.updateDraft(draft.copy(straighten=0f))}) {Text("校正归零")}
                    }
                    EditorTool.LOOKS -> {
                        Text("预设替换明暗、颜色与细节参数，保留构图；之后仍可精调。",style=MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            Look.entries.forEach { look -> TextButton(onClick={editor.updateDraft(look.apply(draft))}) {Text(look.label)} }
                        }
                    }
                }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=editor::cancelTool) {Text("取消调整")}
                    Button(onClick=editor::applyTool) {Text("应用调整")}
                }
            }
        }
    }
}

@Composable
private fun AdjustmentPanel(edit: Edit, editor: EditorViewModel, choices: List<Adjustment>) {
    var selected by rememberSaveable(choices.first()) { mutableStateOf(choices.first().name) }
    val adjustment=choices.firstOrNull {it.name==selected} ?: choices.first()
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        choices.forEach { value -> FilterChip(selected=value==adjustment,onClick={selected=value.name},label={Text(value.label)}) }
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text(adjustment.label,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
        val value=adjustment.get(edit)
        Text(if(adjustment==Adjustment.EXPOSURE) String.format(Locale.ROOT,"%.2f EV",value)
            else String.format(Locale.ROOT,"%.0f",(value-(if(adjustment==Adjustment.SATURATION) 1f else 0f))*100),
            style=MaterialTheme.typography.labelLarge)
        TextButton(onClick={editor.updateDraft(adjustment.set(edit,adjustment.get(Edit())))}) {Text("归零")}
    }
    Slider(value=adjustment.get(edit),onValueChange={editor.updateDraft(adjustment.set(edit,it))},valueRange=adjustment.range,
        modifier=Modifier.semantics {contentDescription="${adjustment.label}调整"})
}

@Composable
private fun LocalPanel(ui: EditorUi, editor: EditorViewModel, draft: Edit) {
    var setting by rememberSaveable {mutableStateOf("局部曝光")}
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        RegionMode.entries.forEach { mode -> FilterChip(selected=ui.regionMode==mode,
            onClick={editor.regionMode(mode)},label={Text(mode.label)}) }
    }
    Row(verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked=ui.showRegion,onCheckedChange=editor::showRegion,modifier=Modifier.semantics {contentDescription="显示区域"})
        Text("显示区域 · ${draft.local.region.strokes.size} 笔",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick=editor::clearRegion,enabled=draft.local.region.strokes.isNotEmpty()) {Text("清空区域")}
    }
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        listOf("局部曝光","柔肤（实验）","细节保护","笔刷大小","边缘硬度").forEach { item ->
            FilterChip(selected=setting==item,onClick={setting=item},label={Text(item)})
        }
    }
    when(setting) {
        "柔肤（实验）" -> {
            Text(if(draft.local.region.strokes.isEmpty()) "先涂选皮肤区域；未选区时不会改变照片。" else "实验效果，注意避开眼唇与发丝。",style=MaterialTheme.typography.bodySmall)
            Text(String.format(Locale.ROOT,"柔肤强度 %.0f%%",draft.local.soften*100),style=MaterialTheme.typography.labelMedium)
            Slider(value=draft.local.soften,onValueChange={editor.updateDraft(draft.copy(local=draft.local.copy(soften=it)))},
                onValueChangeFinished=editor::localCheckpoint,valueRange=0f..1f,
                modifier=Modifier.semantics {contentDescription="柔肤强度调整"})
        }
        "细节保护" -> {
            Text(String.format(Locale.ROOT,"细节保护 %.0f%% · 越高越保留局部变化",draft.local.detailProtection*100),style=MaterialTheme.typography.labelMedium)
            Slider(value=draft.local.detailProtection,onValueChange={editor.updateDraft(draft.copy(local=draft.local.copy(detailProtection=it)))},
                onValueChangeFinished=editor::localCheckpoint,valueRange=0f..1f,
                modifier=Modifier.semantics {contentDescription="柔肤细节保护"})
        }
        "笔刷大小" -> {
            Text("直径 ${String.format(Locale.ROOT,"%.1f%%",ui.brushRadius*200)} · 相对原图短边",style=MaterialTheme.typography.labelMedium)
            Slider(value=ui.brushRadius,onValueChange=editor::brushRadius,valueRange=.005f.. .25f,
                modifier=Modifier.semantics {contentDescription="区域笔刷大小"})
        }
        "边缘硬度" -> {
            Text(String.format(Locale.ROOT,"%.0f%%",ui.brushHardness*100),style=MaterialTheme.typography.labelMedium)
            Slider(value=ui.brushHardness,onValueChange=editor::brushHardness,valueRange=0f..1f,
                modifier=Modifier.semantics {contentDescription="区域边缘硬度"})
        }
        else -> {
            Text(String.format(Locale.ROOT,"%.2f EV · 仅影响涂选区域",draft.local.exposureEv),style=MaterialTheme.typography.labelMedium)
            Slider(value=draft.local.exposureEv,onValueChange={editor.updateDraft(draft.copy(local=draft.local.copy(exposureEv=it)))},
                onValueChangeFinished=editor::localCheckpoint,valueRange=-3f..3f,
                modifier=Modifier.semantics {contentDescription="局部曝光调整"})
        }
    }
}
