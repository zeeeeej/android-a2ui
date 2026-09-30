package com.xpl.a2uichat.games.script

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.graphics.toColorInt
import com.xpl.a2uichat.games.GameKey
import com.xpl.a2uichat.games.LocalGameHost
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

@Composable
fun ScriptGame(gameId: String,title: String,source: JsonObject,modifier: Modifier = Modifier,onAction: (String,Map<String,Any>) -> Unit) {
    val host = LocalGameHost.current ?: return
    val key = GameKey(host.surfaceId,gameId,"ScriptGame")
    if (!host.enabled && host.store.findScript(key) == null) { Text("正在校验隔离游戏…",modifier.padding(16.dp)); return }
    val session = remember(host.store,key) { host.store.script(key,source) }
    val frame by session.frame.collectAsState()
    var expanded by remember(session) { mutableStateOf(false) }
    var visible by remember(session) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(session,lifecycle) {
        val observer = LifecycleEventObserver { _,_ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED); if (!resumed) session.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session.pause() }
    }
    val allowed = host.enabled && resumed && (visible || expanded)
    val currentAllowed by rememberUpdatedState(allowed)
    LaunchedEffect(host.enabled,source) { if (host.enabled) session.offer(source) else session.pause() }
    LaunchedEffect(allowed) { if (!allowed && frame.snapshot != null) session.pause() }
    LaunchedEffect(allowed,frame.paused,frame.program) {
        val tick = frame.program?.tickMs ?: 0
        if (allowed && !frame.paused && tick > 0) while (true) {
            delay(tick.toLong()); if (!currentAllowed) break
            session.send(buildJsonObject { put("type","tick") })
        }
    }
    val play: (JsonObject) -> Unit = { if (currentAllowed) session.send(it) }
    val send: (Boolean) -> Unit = { repair ->
        if (host.enabled && resumed) {
            session.pause(); expanded = false
            onAction(if (repair) "script_repair" else "game_result",session.result() + mapOf("title" to title) +
                if (repair) mapOf("error" to (frame.error ?: "规则不符合预期"),"activeSpecJson" to (frame.program?.json ?: source).toString()) else emptyMap())
        }
    }
    val panel: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().then(if (expanded) Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()) else Modifier).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically) {
                Text(title,Modifier.weight(1f),style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { session.pause(); expanded = !expanded },modifier = Modifier.testTag("script_expand")) { Text(if (expanded) "收起" else "全屏") }
            }
            Text(frame.snapshot?.view?.status ?: "正在初始化规则…",Modifier.testTag("script_status"))
            Text(if (frame.computing) "隔离计算中…" else if (frame.paused) "已暂停" else "进行中",style = MaterialTheme.typography.labelSmall)
            Row {
                Button(onClick = { if (frame.paused) host.store.resume(session) else session.pause() },enabled = allowed && !frame.computing,
                    modifier = Modifier.testTag("script_run")) { Text(if (frame.paused) "开始 / 继续" else "暂停") }
                TextButton(onClick = { session.restart() },enabled = host.enabled,modifier = Modifier.testTag("script_reset")) { Text("重开") }
            }
            frame.error?.let { Text(it,color = MaterialTheme.colorScheme.error,style = MaterialTheme.typography.bodySmall) }
            if (frame.pending != null) TextButton(onClick = { session.restart(true) },enabled = host.enabled,modifier = Modifier.testTag("script_apply")) { Text("应用新规则并重开") }
            frame.snapshot?.let { snapshot ->
                val v = snapshot.view
                val playable = allowed && !frame.paused && !frame.computing && v.phase != "finished" && (v.phase != "playing" || v.turn != frame.program?.botPlayer)
                ScriptBoard(v,Modifier.fillMaxWidth().heightIn(max = if (expanded) 420.dp else 280.dp).aspectRatio(v.width.toFloat()/v.height),playable,play)
                v.buttons.chunked(3).forEach { buttons -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    buttons.forEach { button -> FilledTonalButton(onClick = { play(button.action) },enabled = playable && button.action in snapshot.actions,
                        modifier = Modifier.testTag("script_action_${button.action.word("type")}"),contentPadding = PaddingValues(horizontal = 10.dp)) { Text(button.label) } }
                } }
            }
            Row {
                TextButton(onClick = { send(false) },enabled = host.enabled && frame.snapshot != null) { Text("让 AI 点评") }
                TextButton(onClick = { send(true) },enabled = host.enabled,modifier = Modifier.testTag("script_repair")) { Text("让 AI 修复规则") }
            }
            Text("AI 生成规则 · 原生界面 · 隔离执行",style = MaterialTheme.typography.labelSmall)
        }
    }
    Card(modifier.fillMaxWidth().testTag("script_game").onGloballyPositioned {
        val clipped = it.boundsInWindow().intersect(host.viewport)
        visible = clipped.width > 1 && clipped.height > 1
        if (!visible && !expanded && frame.snapshot != null) session.pause()
    }) { if (expanded) Text("$title · 已展开",Modifier.padding(16.dp)) else panel() }
    if (expanded) Dialog(onDismissRequest = { session.pause(); expanded = false },properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { panel() }
    }
}

@Composable
private fun ScriptBoard(view: ScriptView,modifier: Modifier,enabled: Boolean,onAction: (JsonObject) -> Unit) {
    val colors = remember(view.palette) { view.palette.map { Color(it.toColorInt()) } }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER } }
    Canvas(modifier.testTag("script_board").semantics { contentDescription = "${view.width}乘${view.height} ${view.status}" }
        .pointerInput(view.width,view.height,view.tapType,enabled) { if (enabled) detectTapGestures { p ->
            val x = (p.x / size.width * view.width).toInt().coerceIn(0,view.width-1)
            val y = (p.y / size.height * view.height).toInt().coerceIn(0,view.height-1)
            onAction(buildJsonObject { put("type",view.tapType);put("x",x);put("y",y) })
        } }) {
        val cw = size.width / view.width; val ch = size.height / view.height
        drawRect(colors[0])
        if (view.intersections) {
            for (x in 0 until view.width) drawLine(Color.DarkGray,Offset((x+.5f)*cw,ch/2),Offset((x+.5f)*cw,size.height-ch/2),1f)
            for (y in 0 until view.height) drawLine(Color.DarkGray,Offset(cw/2,(y+.5f)*ch),Offset(size.width-cw/2,(y+.5f)*ch),1f)
        }
        view.cells.forEachIndexed { i,c ->
            val x = i % view.width; val y = i / view.width
            val center = Offset((x+.5f)*cw,(y+.5f)*ch)
            if (view.intersections) { if (c != 0) drawCircle(colors[c],minOf(cw,ch)*.42f,center) }
            else drawRect(colors[c],Offset(x*cw+1,y*ch+1),Size(cw-2,ch-2))
            if (view.marks[i] != 0) {
                val d = minOf(cw,ch)*.18f
                if (view.marks[i] == 1) drawCircle(Color.Red,d/2,center)
                else { drawLine(Color.Red,center-Offset(d,d),center+Offset(d,d),3f);drawLine(Color.Red,center-Offset(d,-d),center+Offset(d,-d),3f) }
            }
            if (view.labels[i].isNotEmpty()) {
                paint.textSize = minOf(cw,ch)*.45f; paint.color = if (c == 1) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                drawContext.canvas.nativeCanvas.drawText(view.labels[i],center.x,center.y-(paint.ascent()+paint.descent())/2,paint)
            }
        }
        if (!view.intersections) {
            val line = Color.Black.copy(alpha = .22f)
            for (x in 0..view.width) drawLine(line,Offset(x*cw,0f),Offset(x*cw,size.height),1.dp.toPx())
            for (y in 0..view.height) drawLine(line,Offset(0f,y*ch),Offset(size.width,y*ch),1.dp.toPx())
        }
    }
}
