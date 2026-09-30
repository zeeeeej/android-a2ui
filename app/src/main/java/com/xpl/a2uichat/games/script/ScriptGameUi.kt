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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.draw.clipToBounds
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
    LaunchedEffect(frame.program) { if (frame.program != null) host.store.onRulesChanged() }
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
                BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment = Alignment.Center) {
                    val ratio = v.width.toFloat()/v.height
                    val boardWidth = minOf(maxWidth, (if (expanded) 420.dp else 280.dp)*ratio)
                    ScriptBoard(v,Modifier.size(boardWidth,boardWidth/ratio),playable,play)
                }
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
            if (frame.program?.version == 1) Text("旧协议 · 未经过新需求验收；可手动让 AI 升级规则",style = MaterialTheme.typography.labelSmall)
            frame.program?.json?.get("players")?.jsonArray?.let { players ->
                Text(players.joinToString(" · ") { p -> p.jsonObject.word("label") + if (p.jsonObject.word("controller") == "bot") "（电脑）" else "（玩家）" }, style = MaterialTheme.typography.labelSmall)
            }
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
    var selected by remember(view,enabled) { mutableStateOf<Pair<Int,Int>?>(null) }
    val colors = remember(view.palette) { view.palette.map { Color(it.toColorInt()) } }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER } }
    Canvas(modifier.clipToBounds().testTag("script_board").semantics { contentDescription = "${view.width}乘${view.height} ${view.status}" }
        .pointerInput(view,selected,enabled) { if (enabled) detectTapGestures { p ->
            val x = (p.x / size.width * view.width).toInt().coerceIn(0,view.width-1)
            val y = (p.y / size.height * view.height).toInt().coerceIn(0,view.height-1)
            if (view.version == 2) {
                val choice = selectScriptAction(view.bindings,selected,x to y)
                selected = choice.first
                choice.second?.let(onAction)
            } else onAction(buildJsonObject { put("type",view.tapType);put("x",x);put("y",y) })
        } }) {
        val cw = size.width / view.width; val ch = size.height / view.height
        drawRect(colors[0])
        drawScriptLayer(view,"background",paint)
        if (view.autoGrid && view.intersections) {
            for (x in 0 until view.width) drawLine(Color.DarkGray,Offset((x+.5f)*cw,ch/2),Offset((x+.5f)*cw,size.height-ch/2),1f)
            for (y in 0 until view.height) drawLine(Color.DarkGray,Offset(cw/2,(y+.5f)*ch),Offset(size.width-cw/2,(y+.5f)*ch),1f)
        }
        if (view.autoGrid && !view.intersections) {
            val line = Color.Black.copy(alpha = .22f)
            for (x in 0..view.width) drawLine(line,Offset(x*cw,0f),Offset(x*cw,size.height),1.dp.toPx())
            for (y in 0..view.height) drawLine(line,Offset(0f,y*ch),Offset(size.width,y*ch),1.dp.toPx())
        }
        drawScriptLayer(view,"grid",paint)
        drawScriptLayer(view,"decoration",paint)
        view.cells.forEachIndexed { i,c ->
            val x = i % view.width; val y = i / view.width
            val center = Offset((x+.5f)*cw,(y+.5f)*ch)
            if (view.intersections) { if (c != 0) drawCircle(colors[c],minOf(cw,ch)*.42f,center) }
            else if (c != 0) drawRect(colors[c],Offset(x*cw+1,y*ch+1),Size(cw-2,ch-2))
            if (view.marks[i] != 0) {
                val d = minOf(cw,ch)*.18f
                if (view.marks[i] == 1) drawCircle(Color.Red,d/2,center)
                else { drawLine(Color.Red,center-Offset(d,d),center+Offset(d,d),3f);drawLine(Color.Red,center-Offset(d,-d),center+Offset(d,-d),3f) }
            }
            if (view.labels[i].isNotEmpty()) {
                paint.textSize = minOf(cw,ch)*.45f
                paint.color = view.textColors.getOrNull(i)?.toColorInt() ?: if (colors[c].luminance() < .4f) android.graphics.Color.WHITE else android.graphics.Color.BLACK
                drawContext.canvas.nativeCanvas.drawText(view.labels[i],center.x,center.y-(paint.ascent()+paint.descent())/2,paint)
            }
        }
        selected?.let { p ->
            drawCircle(Color(0xFF2196F3),minOf(cw,ch)*.46f,Offset((p.first+.5f)*cw,(p.second+.5f)*ch),style = Stroke(2.dp.toPx()))
            view.bindings.filter { it.clicks.size == 2 && it.clicks.first() == p }.forEach { b ->
                val target = b.clicks.last()
                drawCircle(Color(0xFF2196F3),minOf(cw,ch)*.12f,Offset((target.first+.5f)*cw,(target.second+.5f)*ch))
            }
        }
    }
}

private fun DrawScope.drawScriptLayer(view: ScriptView, layer: String, paint: android.graphics.Paint) {
    view.drawings.filter { it.word("layer") == layer }.forEach { d ->
        fun n(k: String, default: Float = 0f) = (d[k] as? JsonPrimitive)?.floatOrNull ?: default
        val color = Color(d.word("color").toColorInt())
        val start = Offset(n("x")*size.width,n("y")*size.height)
        val stroke = n("stroke",.003f)*minOf(size.width,size.height)
        val style = if ((d["fill"] as? JsonPrimitive)?.booleanOrNull == true) Fill else Stroke(stroke)
        when (d.word("type")) {
            "line" -> drawLine(color,start,Offset(n("x2")*size.width,n("y2")*size.height),stroke)
            "polyline" -> d["points"]!!.jsonArray.zipWithNext().forEach { (a,b) ->
                fun point(p: JsonElement) = Offset(p.jsonArray[0].jsonPrimitive.float*size.width,p.jsonArray[1].jsonPrimitive.float*size.height)
                drawLine(color,point(a),point(b),stroke)
            }
            "rect" -> drawRect(color,start,Size(n("width")*size.width,n("height")*size.height),style = style)
            "circle" -> drawCircle(color,n("radius")*minOf(size.width,size.height),start,style = style)
            "text" -> {
                paint.color = d.word("color").toColorInt(); paint.textSize = n("size")*minOf(size.width,size.height)
                drawContext.canvas.nativeCanvas.drawText(d.word("text"),start.x,start.y-(paint.ascent()+paint.descent())/2,paint)
            }
        }
    }
}
