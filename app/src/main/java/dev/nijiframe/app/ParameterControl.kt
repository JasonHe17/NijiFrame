// SPDX-License-Identifier: GPL-3.0-only
package dev.nijiframe.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.*

internal fun parameterText(value: Float, step: Float, unit: String): String {
    val digits=if(step<.1f) 2 else if(step<1f) 1 else 0
    val number=String.format(Locale.ROOT,"%.${digits}f",if(abs(value)<step/2) 0f else value)
    return number+if(unit.isEmpty()) "" else " $unit"
}

/** Native semantics, keyboard and exact input accompany the custom drawing. */
@Composable
internal fun ParameterControl(
    id: String, label: String, value: Float, range: ClosedFloatingPointRange<Float>, neutral: Float,
    unit: String="", ruler: Boolean=true, step: Float=1f, fineStep: Float=step,
    description: String="${label}调整", enabled: Boolean=true,
    onChange: (Float)->Unit, onFinished: ()->Unit,
    modifier: Modifier=Modifier
) {
    var focused by remember {mutableStateOf(false)}
    var fine by rememberSaveable(id) {mutableStateOf(false)}
    var exact by rememberSaveable(id) {mutableStateOf(false)}
    val current by rememberUpdatedState(value)
    // Key callback holders by parameter: disposal of an old gesture must never write to its successor.
    val change=remember(id) {mutableStateOf(onChange)}
    val finish=remember(id) {mutableStateOf(onFinished)}
    SideEffect {change.value=onChange;finish.value=onFinished}
    val activeStep=if(fine) fineStep else step
    val spacing=if(fine && fineStep==step) 24.dp else 8.dp
    val foreground=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled) 1f else .38f)
    val secondary=MaterialTheme.colorScheme.onSurfaceVariant
    val outline=MaterialTheme.colorScheme.outline.copy(alpha=if(enabled) 1f else .38f)
    val surface=MaterialTheme.colorScheme.surface
    val formatted=parameterText(value,fineStep,unit)
    val expandedHeader=LocalDensity.current.fontScale>1.3f
    Column(modifier.fillMaxWidth()) {
        if(expandedHeader) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(label,style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f))
                TextButton(onClick={onChange(neutral);onFinished()},enabled=enabled) {Text("复位")}
            }
            TextButton(onClick={exact=true},enabled=enabled,modifier=Modifier.align(Alignment.CenterHorizontally).semantics {contentDescription="精确输入$label"}) {
                Text(formatted,fontSize=24.sp,style=MaterialTheme.typography.titleLarge.copy(fontFeatureSettings="tnum"))
            }
        } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(label,style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f))
            TextButton(onClick={exact=true},enabled=enabled,modifier=Modifier.semantics {contentDescription="精确输入$label"}) {
                Text(formatted,fontSize=24.sp,color=if(enabled) foreground else secondary,
                    style=MaterialTheme.typography.titleLarge.copy(fontFeatureSettings="tnum"))
            }
            Box(Modifier.weight(1f),contentAlignment=Alignment.CenterEnd) {
                TextButton(onClick={onChange(neutral);onFinished()},enabled=enabled) {Text("复位",style=MaterialTheme.typography.labelMedium)}
            }
        }
        val input=Modifier.fillMaxWidth().height(68.dp).systemGestureExclusion()
            .semantics {
                contentDescription=description
                stateDescription=formatted
                progressBarRangeInfo=ProgressBarRangeInfo(value.coerceIn(range),range)
                if(!enabled) disabled()
                setProgress {requested ->
                    if(!enabled || !requested.isFinite()) false else {
                        change.value(requested.coerceIn(range));finish.value();true
                    }
                }
            }
            .then(if(focused) Modifier.border(1.dp,outline,MaterialTheme.shapes.small) else Modifier)
            .onFocusChanged {focused=it.isFocused}
            .onKeyEvent {event ->
                val delta=when(event.key) {Key.DirectionRight,Key.DirectionUp->activeStep;Key.DirectionLeft,Key.DirectionDown->-activeStep;else->null}
                if(!enabled || delta==null) false else {
                    if(event.type==KeyEventType.KeyDown) change.value((current+delta).coerceIn(range))
                    else if(event.type==KeyEventType.KeyUp) finish.value()
                    true
                }
            }.focusable(enabled)
            .pointerInput(id,enabled,ruler,fine,range) {
                if(!enabled) return@pointerInput
                awaitEachGesture {
                    val down=awaitFirstDown(requireUnconsumed=false)
                    val start=current
                    val callback=change.value
                    val completedCallback=finish.value
                    val motion=RulerMotion(start,range,activeStep,spacing.toPx())
                    var previous=down.position
                    var active=false
                    var completed=false
                    try {
                        while(true) {
                            val event=awaitPointerEvent()
                            if(event.changes.count {it.pressed}>1) break
                            val point=event.changes.firstOrNull {it.id==down.id} ?: break
                            if(point.isConsumed) break
                            if(!point.pressed) {
                                if(!ruler && !active) {
                                    val inset=8.dp.toPx()
                                    callback(range.start+((point.position.x-inset)/(size.width-2*inset)).coerceIn(0f,1f)*(range.endInclusive-range.start))
                                    active=true
                                }
                                completed=true;break
                            }
                            if(!active) {
                                val distance=point.position-down.position
                                if(abs(distance.y)>viewConfiguration.touchSlop && abs(distance.y)>abs(distance.x)) break
                                if(abs(distance.x)<viewConfiguration.touchSlop) continue
                                active=true
                            }
                            point.consume()
                            if(ruler) callback(motion.move(point.position.x-previous.x))
                            else {
                                val inset=8.dp.toPx()
                                callback(range.start+((point.position.x-inset)/(size.width-2*inset)).coerceIn(0f,1f)*(range.endInclusive-range.start))
                            }
                            previous=point.position
                        }
                    } finally {
                        if(active) {
                            if(completed) completedCallback() else callback(start)
                        }
                    }
                }
            }
        Canvas(input) {
            if(ruler) {
                val gap=spacing.toPx()
                val center=value/activeStep
                val half=size.width/gap/2
                val minIndex=max(ceil(range.start/activeStep).toInt(),floor(center-half).toInt())
                val maxIndex=min(floor(range.endInclusive/activeStep).toInt(),ceil(center+half).toInt())
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color=secondary.toArgb();textSize=11.sp.toPx();textAlign=android.graphics.Paint.Align.CENTER
                }
                val labelWidth=paint.measureText(parameterText(range.start,activeStep,""))+8.dp.toPx()
                val labelEvery=(ceil(labelWidth/(gap*5)).toInt().coerceAtLeast(1))*5
                for(index in minIndex..maxIndex) {
                    val x=size.width/2+(index-center)*gap
                    val major=index%5==0
                    drawLine(if(index==0) foreground else outline,Offset(x,16.dp.toPx()),
                        Offset(x,(if(major) 36 else 28).dp.toPx()),if(index==0) 2.dp.toPx() else 1.dp.toPx())
                    if(index%labelEvery==0 && x>24.dp.toPx() && x<size.width-24.dp.toPx()) {
                        val n=index*activeStep
                        val text=parameterText(n,activeStep,"").trimEnd('0').trimEnd('.').let {if(it.isEmpty() || it=="-") "0" else it}
                        // Integer labels must retain their trailing zero (e.g. 100).
                        drawContext.canvas.nativeCanvas.drawText(if(activeStep>=1f) n.roundToInt().toString() else text,x,57.dp.toPx(),paint)
                    }
                }
                drawLine(foreground,Offset(size.width/2,6.dp.toPx()),Offset(size.width/2,41.dp.toPx()),2.dp.toPx())
            } else {
                val inset=8.dp.toPx();val y=size.height/2
                val x=inset+(size.width-2*inset)*(value-range.start)/(range.endInclusive-range.start)
                drawLine(outline,Offset(inset,y),Offset(size.width-inset,y),2.dp.toPx(),StrokeCap.Round)
                drawLine(foreground,Offset(inset,y),Offset(x,y),2.dp.toPx(),StrokeCap.Round)
                drawRoundRect(surface,Offset(x-4.dp.toPx(),y-11.dp.toPx()),androidx.compose.ui.geometry.Size(8.dp.toPx(),22.dp.toPx()),androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                drawRoundRect(foreground,Offset(x-4.dp.toPx(),y-11.dp.toPx()),androidx.compose.ui.geometry.Size(8.dp.toPx(),22.dp.toPx()),androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),style=Stroke(1.5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(if(ruler) "滑动刻度 · 点数值精调" else "${parameterText(range.start,fineStep,unit)} — ${parameterText(range.endInclusive,fineStep,unit)}",
                style=MaterialTheme.typography.labelSmall,color=secondary,modifier=Modifier.weight(1f))
            if(ruler) TextButton(onClick={fine=!fine},enabled=enabled,modifier=Modifier.semantics {selected=fine}) {
                Text(if(fine) "精细 ✓" else "精细",style=MaterialTheme.typography.labelMedium)
            }
        }
    }
    if(exact) ExactValueDialog(label,value,range,fineStep,unit,{exact=false}) {
        onChange(it);onFinished();exact=false
    }
}

@Composable
private fun ExactValueDialog(label: String,value: Float,range: ClosedFloatingPointRange<Float>,step: Float,unit: String,
                             onDismiss: ()->Unit,onConfirm: (Float)->Unit) {
    var input by rememberSaveable {mutableStateOf(parameterText(value,step,""))}
    var error by rememberSaveable {mutableStateOf(false)}
    fun confirm() {val number=parseParameter(input,range,step);if(number==null) error=true else onConfirm(number)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("$label · 精确数值")},text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(value=input,onValueChange={input=it;error=false},singleLine=true,isError=error,
                label={Text("$label${if(unit.isEmpty()) "" else " ($unit)"}")},
                supportingText={Text(if(error) "请输入范围内的有效数值" else "${range.start} 至 ${range.endInclusive}")},
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done),
                keyboardActions=KeyboardActions(onDone={confirm()}),modifier=Modifier.semantics {contentDescription="${label}数值"})
            if(range.start<0f) TextButton(onClick={
                parseParameter(input,range,step)?.let {number ->
                    val opposite=-number
                    if(opposite in range) {input=parameterText(opposite,step,"");error=false} else error=true
                } ?: run {error=true}
            },modifier=Modifier.semantics {contentDescription="切换正负"}) {Text("± 正负切换")}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(modifier=Modifier.semantics {contentDescription="减少$label"},onClick={parseParameter(input,range,step)?.let {input=parameterText((it-step).coerceIn(range),step,"");error=false}}) {Text("−",fontSize=24.sp)}
                Text("步进 ${parameterText(step,step,unit)}",Modifier.align(Alignment.CenterVertically),style=MaterialTheme.typography.bodySmall)
                TextButton(modifier=Modifier.semantics {contentDescription="增加$label"},onClick={parseParameter(input,range,step)?.let {input=parameterText((it+step).coerceIn(range),step,"");error=false}}) {Text("+",fontSize=24.sp)}
            }
        }
    },confirmButton={TextButton(onClick=::confirm){Text("使用此数值")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
