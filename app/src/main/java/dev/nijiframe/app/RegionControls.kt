// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

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
        val region=ui.draft?.local?.region
        Text("显示区域 · ${region?.strokes?.size ?: 0} 笔"+if(region?.base!=null) " · 含建议" else "",
            style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
        TextButton(onClick=editor::clearRegion,enabled=enabled && region?.isEmpty==false) {Text("清空区域")}
    }
}

@Composable
internal fun BrushSettings(ui: EditorUi, editor: EditorViewModel, enabled: Boolean=true) {
    var hardness by rememberSaveable {mutableStateOf(false)}
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        FilterChip(selected=!hardness,onClick={hardness=false},label={Text("笔刷大小")},enabled=enabled)
        FilterChip(selected=hardness,onClick={hardness=true},label={Text("边缘硬度")},enabled=enabled)
    }
    if(hardness) {
        Text(String.format(Locale.ROOT,"边缘硬度 %.0f%%",ui.brushHardness*100),style=MaterialTheme.typography.labelMedium)
        Slider(value=ui.brushHardness,onValueChange=editor::brushHardness,valueRange=0f..1f,enabled=enabled,
            modifier=Modifier.systemGestureExclusion().semantics {contentDescription="区域边缘硬度"})
    } else {
        val m=ui.project?.manifest
        val pixels=if(m==null) 0 else (ui.brushRadius*2*min(m.width,m.height)).roundToInt()
        Text(String.format(Locale.ROOT,"直径 %.1f%% · %d 原图像素",ui.brushRadius*200,pixels),style=MaterialTheme.typography.labelMedium)
        Slider(value=ui.brushRadius,onValueChange=editor::brushRadius,valueRange=.005f.. .25f,enabled=enabled,
            modifier=Modifier.systemGestureExclusion().semantics {contentDescription="区域笔刷大小"})
    }
}
