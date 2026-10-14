// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.nijiframe.editor.*
import kotlin.math.*

@Composable
internal fun EditorCanvas(ui: EditorUi, editor: EditorViewModel, modifier: Modifier=Modifier) {
    var size by remember {mutableStateOf(IntSize.Zero)}
    var zoom by remember(ui.projectId,ui.preview?.width,ui.preview?.height) {mutableFloatStateOf(1f)}
    var pan by remember(ui.projectId,ui.preview?.width,ui.preview?.height) {mutableStateOf(Offset.Zero)}
    val bitmap=ui.preview
    val cropMode=ui.tool==EditorTool.CROP && !ui.comparing
    LaunchedEffect(cropMode) {zoom=1f;pan=Offset.Zero}
    val state=rememberTransformableState { factor,delta,_ ->
        if(!cropMode && bitmap!=null) {
            zoom=(zoom*factor).coerceIn(1f,8f)
            val scale=min(size.width.toFloat()/bitmap.width,size.height.toFloat()/bitmap.height)
            val maxX=max(0f,(bitmap.width*scale*zoom-size.width)/2)
            val maxY=max(0f,(bitmap.height*scale*zoom-size.height)/2)
            pan=Offset((pan.x+delta.x).coerceIn(-maxX,maxX),(pan.y+delta.y).coerceIn(-maxY,maxY))
        }
    }
    Box(modifier.clipToBounds().background(Color(0xff101114)).onSizeChanged {size=it}
        .then(if(cropMode) Modifier else Modifier.transformable(state).pointerInput(ui.projectId) {
            detectTapGestures(onDoubleTap={zoom=if(zoom>1f) 1f else 2f;pan=Offset.Zero})
        }),contentAlignment=Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(),if(ui.comparing) "原图对比" else "编辑预览",
            Modifier.fillMaxSize().graphicsLayer {scaleX=zoom;scaleY=zoom;translationX=pan.x;translationY=pan.y},contentScale=ContentScale.Fit) }
        if(cropMode && !ui.rendering && ui.project!=null && ui.draft!=null && bitmap!=null) {
            val (width,height)=Geometry.canvasSize(ui.project.manifest.width,ui.project.manifest.height,ui.draft.turns)
            CropOverlay(ui.draft.crop ?: Crop(0,0,width,height),width,height,ui.cropAspect) {
                editor.updateDraft(ui.draft.copy(crop=it))
            }
        }
        if(ui.rendering) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        if(ui.comparing) Surface(Modifier.align(Alignment.TopStart).padding(12.dp),shape=MaterialTheme.shapes.small) {
            Text("原图",Modifier.padding(8.dp),style=MaterialTheme.typography.labelMedium)
        }
        if(!cropMode) TextButton(onClick={zoom=1f;pan=Offset.Zero},modifier=Modifier.align(Alignment.BottomEnd)) {Text("适应画布")}
    }
}

private fun imageBounds(size: IntSize,width: Int,height: Int): Rect {
    val scale=min(size.width.toFloat()/width,size.height.toFloat()/height)
    val w=width*scale;val h=height*scale
    return Rect((size.width-w)/2,(size.height-h)/2,(size.width+w)/2,(size.height+h)/2)
}

@Composable
private fun CropOverlay(crop: Crop,width: Int,height: Int,aspect: Float?,onCrop: (Crop)->Unit) {
    var size by remember {mutableStateOf(IntSize.Zero)}
    val current by rememberUpdatedState(crop)
    val callback by rememberUpdatedState(onCrop)
    Canvas(Modifier.fillMaxSize().onSizeChanged {size=it}.semantics {contentDescription="裁剪框，拖动四角或框内调整"}
        .pointerInput(width,height,aspect,size) {
            var start=current;var delta=Offset.Zero;var handle: CropHandle?=null
            val frame=imageBounds(size,width,height)
            if(frame.width<=0) return@pointerInput
            val scale=frame.width/width
            fun screenRect(c: Crop)=Rect(frame.left+c.x*scale,frame.top+c.y*scale,
                frame.left+(c.x+c.width)*scale,frame.top+(c.y+c.height)*scale)
            detectDragGestures(onDragStart={position ->
                start=current;delta=Offset.Zero
                val rectangle=screenRect(start)
                val corners=listOf(CropHandle.TOP_LEFT to rectangle.topLeft,CropHandle.TOP_RIGHT to rectangle.topRight,
                    CropHandle.BOTTOM_LEFT to rectangle.bottomLeft,CropHandle.BOTTOM_RIGHT to rectangle.bottomRight)
                val nearest=corners.minBy { (it.second-position).getDistance() }
                handle=if((nearest.second-position).getDistance()<=32.dp.toPx()) nearest.first
                    else if(rectangle.contains(position)) CropHandle.MOVE else null
            },onDrag={change,amount ->
                handle?.let { selected ->
                    change.consume();delta+=amount
                    callback(CropGesture.drag(start,delta.x/scale,delta.y/scale,width,height,selected,aspect))
                }
            },onDragEnd={handle=null},onDragCancel={handle=null})
        }) {
        val frame=imageBounds(IntSize(size.width.toInt(),size.height.toInt()),width,height)
        val scale=frame.width/width
        val r=Rect(frame.left+crop.x*scale,frame.top+crop.y*scale,frame.left+(crop.x+crop.width)*scale,frame.top+(crop.y+crop.height)*scale)
        val shade=Color.Black.copy(alpha=.55f)
        drawRect(shade,Offset.Zero,Size(this.size.width,max(0f,r.top)))
        drawRect(shade,Offset(0f,r.bottom),Size(this.size.width,max(0f,this.size.height-r.bottom)))
        drawRect(shade,Offset(0f,r.top),Size(max(0f,r.left),r.height))
        drawRect(shade,Offset(r.right,r.top),Size(max(0f,this.size.width-r.right),r.height))
        drawRect(Color.White,r.topLeft,r.size,style=Stroke(2.dp.toPx()))
        for(i in 1..2) {
            drawLine(Color.White.copy(alpha=.6f),Offset(r.left+r.width*i/3,r.top),Offset(r.left+r.width*i/3,r.bottom),1.dp.toPx())
            drawLine(Color.White.copy(alpha=.6f),Offset(r.left,r.top+r.height*i/3),Offset(r.right,r.top+r.height*i/3),1.dp.toPx())
        }
        for(corner in listOf(r.topLeft,r.topRight,r.bottomLeft,r.bottomRight)) drawCircle(Color.White,6.dp.toPx(),corner)
    }
}
