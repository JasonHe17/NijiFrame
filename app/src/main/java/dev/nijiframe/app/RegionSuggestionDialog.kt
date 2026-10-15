// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
internal fun RegionSuggestionDialog(ui: EditorUi, editor: EditorViewModel) {
    AlertDialog(onDismissRequest=editor::dismissSuggestion,title={Text("检查建议区域")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(240.dp),contentAlignment=Alignment.Center) {
                ui.preview?.let {Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
                ui.suggestionOverlay?.let {Image(it.asImageBitmap(),"建议区域预览",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)}
            }
            Text("这是关键点生成的大致面部区域，不是精确皮肤识别。请检查眼镜、胡须、头发与遮挡，采用后可手工补画或擦除。")
            Text("采用会替换当前区域及笔画，保留调整强度；可撤销。分析不会自动开启柔肤。")
        }},
        confirmButton={TextButton(onClick=editor::acceptSuggestion) {Text("采用并修正")}},
        dismissButton={TextButton(onClick=editor::dismissSuggestion) {Text("保留原区域")}})
}
