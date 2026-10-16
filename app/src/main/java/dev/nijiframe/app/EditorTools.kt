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
import dev.nijiframe.editor.*

internal enum class EditorCategory(val label: String) {
    ADJUST("调整"), RETOUCH("修饰"), COMPOSITION("构图"), PRESETS("预设");
    companion object {
        fun of(tool: EditorTool)=when(tool) {
            EditorTool.LOCAL->RETOUCH
            EditorTool.CROP,EditorTool.GEOMETRY->COMPOSITION
            EditorTool.LOOKS->PRESETS
            else->ADJUST
        }
    }
}

@Composable
internal fun ToolPanel(ui: EditorUi, editor: EditorViewModel, modifier: Modifier=Modifier) {
    var category by rememberSaveable {mutableStateOf(EditorCategory.ADJUST)}
    LaunchedEffect(ui.tool) {ui.tool?.let {category=EditorCategory.of(it)}}
    Surface(modifier,color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal=16.dp,vertical=4.dp)) {
            val tool=ui.tool;val draft=ui.draft
            if(tool==null || draft==null) {
                if(category==EditorCategory.RETOUCH) {
                    Column {
                        Row(Modifier.fillMaxWidth()) {
                            ToolEntry("柔肤（实验）",Glyph.FACE,Modifier.weight(1f)) {editor.beginParameter(EditorParameter.SOFTEN)}
                            ToolEntry("局部曝光",Glyph.SUN,Modifier.weight(1f)) {editor.beginParameter(EditorParameter.LOCAL_EXPOSURE)}
                        }
                        Row(Modifier.fillMaxWidth()) {
                            ToolEntry("局部颜色",Glyph.COLOR,Modifier.weight(1f)) {editor.beginParameter(EditorParameter.LOCAL_WARMTH)}
                            ToolEntry("编辑选区",Glyph.BRUSH,Modifier.weight(1f)) {editor.beginTool(EditorTool.LOCAL);editor.regionMode(RegionMode.MOVE)}
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        when(category) {
                            EditorCategory.ADJUST -> {
                                ToolEntry("明暗",Glyph.SUN) {editor.beginTool(EditorTool.LIGHT)}
                                ToolEntry("颜色",Glyph.COLOR) {editor.beginTool(EditorTool.COLOR)}
                                ToolEntry("锐化与暗角",Glyph.SHARPEN) {editor.beginTool(EditorTool.DETAILS)}
                            }
                            EditorCategory.RETOUCH -> Unit
                            EditorCategory.COMPOSITION -> {
                                ToolEntry("裁剪",Glyph.CROP) {editor.beginTool(EditorTool.CROP)}
                                ToolEntry("旋转",Glyph.ROTATE) {editor.beginTool(EditorTool.GEOMETRY)}
                            }
                            EditorCategory.PRESETS -> ToolEntry("选择预设",Glyph.PRESET) {editor.beginTool(EditorTool.LOOKS)}
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.SpaceBetween) {
                    EditorCategory.entries.forEach { value ->
                        TextButton(onClick={category=value},enabled=!ui.busy,modifier=Modifier.semantics {selected=category==value}) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                Text(value.label,color=if(category==value) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                if(category==value) HorizontalDivider(Modifier.width(16.dp),thickness=2.dp,color=MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).semantics {contentDescription="工具设置"}) {
                    when(tool) {
                        EditorTool.LOCAL -> LocalPanel(ui,editor,draft)
                        EditorTool.LIGHT,EditorTool.COLOR,EditorTool.DETAILS -> AdjustmentPanel(ui,editor)
                        EditorTool.CROP -> {
                            Text("拖动四角调整范围，拖动框内移动",style=MaterialTheme.typography.bodySmall)
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                val manifest=requireNotNull(ui.project).manifest
                                val (w,h)=Geometry.canvasSize(manifest.width,manifest.height,draft.turns)
                                listOf(null to "自由",w.toFloat()/h to "原图",1f to "1:1",4f/3 to "4:3",3f/4 to "3:4",16f/9 to "16:9",9f/16 to "9:16").forEach { (ratio,label) ->
                                    FilterChip(selected=ui.cropAspect==ratio,enabled=!ui.comparing,onClick={editor.cropAspect(ratio);editor.localCheckpoint()},label={Text(label)})
                                }
                            }
                            val crop=draft.crop
                            Text(if(crop==null) "整张照片" else "${crop.width} × ${crop.height}",style=MaterialTheme.typography.labelMedium)
                            TextButton(enabled=!ui.comparing,onClick={editor.cropAspect(null);editor.updateDraft(draft.copy(crop=null));editor.localCheckpoint()}) {Text("恢复完整画面")}
                        }
                        EditorTool.GEOMETRY -> {
                            val project=requireNotNull(ui.project).manifest
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(enabled=!ui.comparing,onClick={editor.updateDraft(Geometry.rotate(draft,project.width,project.height));editor.localCheckpoint()}) {Text("旋转 90°")}
                                TextButton(enabled=!ui.comparing,onClick={editor.updateDraft(Geometry.flip(draft,project.width,project.height,true));editor.localCheckpoint()}) {Text("水平翻转")}
                                TextButton(enabled=!ui.comparing,onClick={editor.updateDraft(Geometry.flip(draft,project.width,project.height,false));editor.localCheckpoint()}) {Text("垂直翻转")}
                            }
                            AdjustmentPanel(ui,editor)
                        }
                        EditorTool.LOOKS -> {
                            Text("预设替换明暗、颜色与细节参数，保留构图；之后仍可精调。",style=MaterialTheme.typography.bodySmall)
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                Look.entries.forEach { look -> TextButton(enabled=!ui.comparing,onClick={editor.updateDraft(look.apply(draft));editor.localCheckpoint()}) {Text(look.label)} }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=editor::cancelTool,enabled=!ui.busy,modifier=Modifier.semantics {contentDescription="取消调整"}) {
                        EditorGlyph(Glyph.CLOSE);Spacer(Modifier.width(4.dp));Text("取消")
                    }
                    Text(tool.label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick=editor::applyTool,enabled=!ui.busy,modifier=Modifier.semantics {contentDescription="应用调整"}) {
                        EditorGlyph(Glyph.CHECK);Spacer(Modifier.width(4.dp));Text("完成")
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolEntry(label: String,glyph: Glyph,modifier: Modifier=Modifier,onClick: ()->Unit) {
    TextButton(onClick=onClick,modifier=modifier.widthIn(min=80.dp).heightIn(min=72.dp)) {
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
            EditorGlyph(glyph);Text(label,style=MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AdjustmentPanel(ui: EditorUi,editor: EditorViewModel) {
    val choices=EditorParameter.entries.filter {it.tool==ui.tool}
    val selected=choices.firstOrNull {it.name==ui.selectedParameter} ?: choices.first()
    val draft=requireNotNull(ui.draft)
    key(ui.toolSession,selected) {
        ParameterControl(id="${ui.toolSession}:${selected.name}",label=selected.label,value=selected.get(draft),
            range=selected.range,neutral=selected.neutral,unit=selected.unit,ruler=selected.ruler,
            step=selected.step,fineStep=selected.fineStep,description=selected.description,enabled=!ui.busy && !ui.comparing,
            onChange={editor.setParameter(selected,it,ui.toolSession)},onFinished={editor.toolCheckpoint(ui.toolSession)})
    }
    var allParameters by remember {mutableStateOf(false)}
    if(choices.size>1) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
      Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
        choices.forEach { parameter ->
            val changed=kotlin.math.abs(parameter.get(draft)-parameter.neutral)>.0001f
            TextButton(onClick={editor.selectParameter(parameter)},enabled=!ui.comparing,
                modifier=Modifier.widthIn(min=64.dp).semantics {this.selected=selected==parameter;stateDescription=if(changed) "已调整" else "默认值"}) {
                Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Box {
                        EditorGlyph(parameter.glyph,color=if(selected==parameter) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        if(changed) Text("•",Modifier.align(Alignment.TopEnd).offset(x=8.dp,y=(-8).dp),style=MaterialTheme.typography.labelSmall)
                    }
                    Text(parameter.label,style=MaterialTheme.typography.labelMedium)
                    if(selected==parameter) HorizontalDivider(Modifier.width(16.dp),thickness=2.dp,color=MaterialTheme.colorScheme.onSurface)
                    else Spacer(Modifier.height(2.dp))
                }
            }
        }
      }
      Box {
          TextButton(onClick={allParameters=true},enabled=!ui.comparing,
              modifier=Modifier.semantics {contentDescription="全部参数"}) {
              Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                  EditorGlyph(Glyph.MORE);Text("全部",style=MaterialTheme.typography.labelMedium)
              }
          }
          DropdownMenu(expanded=allParameters,onDismissRequest={allParameters=false}) {
              choices.forEach {parameter ->
                  DropdownMenuItem(text={Text(parameter.label)},onClick={allParameters=false;editor.selectParameter(parameter)})
              }
          }
      }
    }
}

@Composable
private fun LocalPanel(ui: EditorUi, editor: EditorViewModel, draft: Edit) {
    val effects=ui.retouchStep==RetouchStep.EFFECT
    RegionPicker(ui,editor)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        FilterChip(selected=!effects,onClick={editor.retouchStep(RetouchStep.REGION)},enabled=!ui.comparing,label={Text("选区与修正")})
        FilterChip(selected=effects,onClick={editor.retouchStep(RetouchStep.EFFECT)},enabled=!ui.comparing,label={Text("调整效果")})
    }
    if(!effects) {
        TextButton(onClick=editor::suggestRegion,enabled=!ui.busy && !ui.rendering && !ui.comparing) {Text("建议人像区域（实验）")}
        RegionModes(ui,editor,enabled=!ui.comparing)
        RegionVisibility(ui,editor,enabled=!ui.comparing)
        BrushSettings(ui,editor,enabled=!ui.comparing && ui.regionMode!=RegionMode.MOVE)
        Text("先检查作用范围，再调整效果；建议失败时仍可手工涂选。",style=MaterialTheme.typography.bodySmall)
    } else {
        Text(if(draft.activeLocal.region.isEmpty) "尚未选择区域 · 请先返回选区与修正" else "作用于当前选区 · 柔肤为实验效果",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        AdjustmentPanel(ui,editor)
    }
}
