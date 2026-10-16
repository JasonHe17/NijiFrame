// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal fun editorSaveLabel(ui: EditorUi): String = when {
    ui.draft!=null && ui.draft!=ui.project?.manifest?.edit -> "调整待应用"
    ui.saving -> "自动保存中"
    ui.dirty -> "未保存"
    else -> "已保存"
}

@Composable
internal fun ProjectInfoDialog(ui: EditorUi, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("工程与照片信息")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(ui.project?.manifest?.name.orEmpty(),style=MaterialTheme.typography.titleMedium)
            Text("工程："+when {ui.saving->"正在保存已应用的修改";ui.dirty->"仍有修改未保存";else->"已应用的修改已保存到本机"})
            if(ui.draft!=null && ui.draft!=ui.project?.manifest?.edit)
                Text("当前工具还有草稿。应用后自动保存，取消可放弃本次调整；草稿不会在关闭应用后保留。")
            Text(ui.status)
            ui.project?.manifest?.let {Text("原图：${it.width} × ${it.height}")}
            ui.sourceNotice?.let {Text(it)}
            Text("工程保存用于继续编辑；导出照片用于查看或分享。导出不覆盖应用内的原图副本。")
            Text("双指缩放，拖动画布；双击切换缩放。局部绘制时，先切换移动画布再缩放定位。")
        }
    },confirmButton={TextButton(onClick=onDismiss) {Text("关闭")}})
}
