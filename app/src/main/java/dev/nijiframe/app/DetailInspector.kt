// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.nijiframe.editor.*
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun DetailInspector(ui: EditorUi, editor: EditorViewModel) {
    val detail=ui.detail ?: return
    val local=ui.tool==EditorTool.LOCAL
    Dialog(onDismissRequest=editor::closeDetail,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
                val compact=maxHeight<600.dp
                val controlsLimit=if(compact) maxHeight*.45f else Dp.Infinity
                Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("100% 细节",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                        TextButton(onClick=editor::detailCompare) {Text(if(detail.comparing) "查看调整" else "查看原图")}
                        TextButton(onClick=editor::closeDetail) {Text("关闭细节")}
                    }
                    // Keep a usable image window on short/landscape displays; controls remain scrollable.
                    Column(Modifier.fillMaxWidth().heightIn(max=controlsLimit).verticalScroll(rememberScrollState()),
                        verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("点击概览选择位置；下方按原尺寸显示。",style=MaterialTheme.typography.bodySmall)
                        var overviewSize by remember {mutableStateOf(IntSize.Zero)}
                        val ratio=min(overviewSize.width.toFloat()/detail.outputWidth,overviewSize.height.toFloat()/detail.outputHeight)
                        val pictureWidth=detail.outputWidth*ratio;val pictureHeight=detail.outputHeight*ratio
                        val left=(overviewSize.width-pictureWidth)/2;val top=(overviewSize.height-pictureHeight)/2
                        Canvas(Modifier.fillMaxWidth().height(132.dp).background(Color(0xff17181b))
                            .onSizeChanged {overviewSize=it}.semantics {contentDescription="选择细节区域"}
                            .pointerInput(overviewSize,detail.outputWidth,detail.outputHeight) {
                                detectTapGestures {point ->
                                    if(pictureWidth>0 && pictureHeight>0 && point.x in left..left+pictureWidth && point.y in top..top+pictureHeight)
                                        editor.detailCenter(MaskPoint((point.x-left)/pictureWidth,(point.y-top)/pictureHeight))
                                }
                            }) {
                            ui.preview?.let {drawImage(it.asImageBitmap(),dstOffset=IntOffset(left.roundToInt(),top.roundToInt()),
                                dstSize=IntSize(pictureWidth.roundToInt().coerceAtLeast(1),pictureHeight.roundToInt().coerceAtLeast(1)))}
                            detail.bounds?.let {b ->
                                val p=Offset(left+b.left*ratio,top+b.top*ratio)
                                val size=androidx.compose.ui.geometry.Size(b.width()*ratio,b.height()*ratio)
                                drawRect(Color.Black,p,size,style=Stroke(4.dp.toPx()))
                                drawRect(Color.White,p,size,style=Stroke(2.dp.toPx()))
                            }
                        }
                        Text(if(detail.comparing) "原图细节 · 保持当前构图" else "调整后细节",style=MaterialTheme.typography.labelMedium)
                        if(local) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                                FilterChip(selected=ui.regionMode==RegionMode.ADD,onClick={editor.regionMode(RegionMode.ADD)},label={Text("细节添加")})
                                FilterChip(selected=ui.regionMode==RegionMode.ERASE,onClick={editor.regionMode(RegionMode.ERASE)},label={Text("细节擦除")})
                                TextButton(onClick={editor.showRegion(!ui.showRegion)}) {Text(if(ui.showRegion) "隐藏覆盖" else "显示覆盖")}
                            }
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text("细节区域 · ${ui.draft?.local?.region?.strokes?.size ?: 0} 笔",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall)
                                TextButton(onClick=editor::localUndo,enabled=ui.localCanUndo && !ui.busy) {Text("撤销笔画")}
                                TextButton(onClick=editor::localRedo,enabled=ui.localCanRedo && !ui.busy) {Text("重做笔画")}
                            }
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                val manifest=requireNotNull(ui.project).manifest
                                Text("半径 ${(ui.brushRadius*min(manifest.width,manifest.height)).roundToInt()} 原图像素",style=MaterialTheme.typography.labelSmall)
                                Slider(value=ui.brushRadius,onValueChange=editor::brushRadius,valueRange=.005f.. .25f,
                                    modifier=Modifier.weight(1f).semantics {contentDescription="细节笔刷半径"})
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color(0xff101114)),
                        contentAlignment=Alignment.Center) {
                        val density=LocalDensity.current
                        val width=with(density) {maxWidth.roundToPx()}.coerceIn(1,DetailRenderer.MAX_EDGE)
                        val height=with(density) {maxHeight.roundToPx()}.coerceIn(1,DetailRenderer.MAX_EDGE)
                        LaunchedEffect(width,height,detail.center,detail.comparing,detail.revision) {editor.loadDetail(width,height)}
                        detail.bitmap?.let {Image(it.asImageBitmap(),"原尺寸细节图",Modifier.fillMaxSize(),contentScale=ContentScale.None)}
                        if(local && !detail.comparing && detail.bitmap!=null && !detail.loading) {
                            if(ui.showRegion) detail.regionOverlay?.let {
                                Image(it.asImageBitmap(),"细节区域覆盖",Modifier.fillMaxSize(),contentScale=ContentScale.None)
                            }
                            if(!ui.busy && ui.regionMode!=RegionMode.MOVE) DetailRegionInput(ui,editor)
                        }
                        if(detail.loading) CircularProgressIndicator()
                        detail.error?.let {Text(it,Modifier.padding(16.dp),color=Color.White)}
                    }
                    Text(if(compact && local) "1:1 显示 · 修改草稿，关闭后应用或取消"
                        else if(local) "1 个图像像素对应 1 个屏幕像素。涂抹修改当前草稿；关闭后应用或取消调整。原图对比时不能涂抹。"
                        else "1 个图像像素对应 1 个屏幕像素。此处仅查看，不更改或保存调整。",style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
