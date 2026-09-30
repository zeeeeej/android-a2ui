package com.xpl.a2uichat.games.rules

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Composable
fun RuleGame(gameId: String, title: String, source: JsonObject, modifier: Modifier = Modifier,
    onAction: (String, Map<String, Any>) -> Unit) {
    val host = LocalGameHost.current ?: return
    val key = GameKey(host.surfaceId, gameId, "RuleGame")
    // Never instantiate interpreters for a preview or validation-only processor.
    if (!host.enabled && host.store.findRule(key) == null) { Text("正在验证游戏规则…", modifier.padding(16.dp)); return }
    val session = remember(host.store, key) { host.store.rule(key, source) }
    val frame by session.frame.collectAsState()
    LaunchedEffect(frame.spec) { if (frame.spec != null) host.store.onRulesChanged() }
    LaunchedEffect(source) { if (host.enabled) session.offer(source) }
    // An update may arrive while the surface is disabled; offer it when the transaction commits.
    LaunchedEffect(host.enabled) { if (host.enabled) session.offer(source) else session.pause() }
    var expanded by remember(session) { mutableStateOf(false) }
    var visible by remember(session) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(session, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) session.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session.pause() }
    }
    val allowed = host.enabled && resumed && (expanded || visible)
    val latestAllowed by rememberUpdatedState(allowed)
    LaunchedEffect(allowed) { if (!allowed) session.pause() }
    LaunchedEffect(session, frame.paused, frame.state?.winner, frame.spec, allowed) {
        val tick = frame.spec?.tickMs ?: 0
        if (allowed && !frame.paused && frame.state?.winner == null && tick > 0) {
            while (true) { delay(tick); if (latestAllowed) session.send(RuleEvent("tick")) else break }
        }
    }
    val toggle = { host.store.pauseAll(); expanded = !expanded }
    val play: (RuleEvent) -> Unit = { if (latestAllowed) session.send(it) }
    val run = { if (latestAllowed) { if (frame.paused) host.store.resume(session) else session.pause() } }
    val send: (Boolean) -> Unit = { repair ->
        if (host.enabled && resumed) {
            session.pause(); expanded = false
            onAction(if (repair) "rule_repair" else "game_result",
                session.result() + mapOf("title" to title) + if (repair) mapOf("error" to (frame.error ?: "规则不符合预期"),
                    "activeSpecJson" to (frame.spec?.source?.toString() ?: source.toString())) else emptyMap())
        }
    }
    val panel: @Composable (Boolean) -> Unit = { fullscreen ->
        RulePanel(frame, title, host.enabled && resumed, allowed, fullscreen,
            onPlay = play, onRun = run, onExpand = toggle,
            onReset = { if (host.enabled) session.restart() }, onApply = { if (host.enabled) session.restart(true) },
            onResult = { send(false) }, onRepair = { send(true) },
            modifier = if (fullscreen) Modifier else Modifier.onGloballyPositioned {
                val clipped = it.boundsInWindow().intersect(host.viewport)
                visible = clipped.width > 1 && clipped.height > 1
                if (!visible) session.pause()
            })
    }
    Card(modifier.fillMaxWidth().testTag("rule_game"), shape = RoundedCornerShape(20.dp)) {
        if (expanded) Text("$title · 已全屏展开", Modifier.padding(20.dp)) else panel(false)
    }
    if (expanded) Dialog(onDismissRequest = { session.pause(); expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { panel(true) }
    }
}

@Composable
private fun RulePanel(frame: RuleFrame, title: String, enabled: Boolean, playable: Boolean, fullscreen: Boolean,
    onPlay: (RuleEvent) -> Unit, onRun: () -> Unit, onExpand: () -> Unit, onReset: () -> Unit,
    onApply: () -> Unit, onResult: () -> Unit, onRepair: () -> Unit, modifier: Modifier = Modifier) {
    val spec = frame.spec
    val state = frame.state
    val controls: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onExpand, enabled = enabled || fullscreen, modifier = Modifier.testTag("rule_expand")) { Text(if (fullscreen) "收起" else "全屏") }
            }
            Text("得分 ${sessionScore(state)}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(when {
                frame.error != null -> "规则已暂停"
                state == null -> "正在准备游戏…"
                state.winner == 0 -> if (spec?.players == 1) "游戏结束" else "平局"
                state.winner != null -> "玩家 ${state.winner} 获胜"
                frame.computing || state.turn == spec?.botPlayer && !frame.paused -> "电脑思考中…"
                frame.paused -> "已暂停 · 点击开始 / 继续"
                spec?.players == 2 -> "轮到玩家 ${state.turn}"
                else -> "进行中"
            }, modifier = Modifier.testTag("rule_status"), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRun, enabled = playable && state != null && state.winner == null && frame.error == null,
                modifier = Modifier.testTag("rule_run")) { Text(if (frame.paused) "开始 / 继续" else "暂停") }
            frame.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRepair, enabled = enabled, modifier = Modifier.testTag("rule_repair")) { Text("让 AI 修复规则") }
            }
            if (frame.pending != null) TextButton(onClick = onApply, enabled = enabled, modifier = Modifier.testTag("rule_apply")) { Text("应用新规则并重开") }
        }
    }
    val footer: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            spec?.controls?.chunked(4)?.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { control ->
                        FilledTonalButton(onClick = { onPlay(RuleEvent(control.id)) }, enabled = playable && !frame.paused && !frame.computing && state?.winner == null,
                            modifier = Modifier.testTag("rule_${control.id}"), contentPadding = PaddingValues(horizontal = 12.dp)) { Text(control.label) }
                    }
                }
            }
            Row {
                TextButton(onClick = onReset, enabled = enabled && spec != null, modifier = Modifier.testTag("rule_reset")) { Text("重新开始") }
                TextButton(onClick = onResult, enabled = enabled && state != null, modifier = Modifier.testTag("rule_result")) { Text("让 AI 点评") }
            }
            Text("AI 生成规则 · 原生本地运行", style = MaterialTheme.typography.labelSmall)
        }
    }
    val board: @Composable (Dp, Dp) -> Unit = { width, height ->
        if (spec != null && state != null) {
            val cell = minOf(width / spec.width, height / spec.height)
            RuleBoard(spec, state, modifier.width(cell * spec.width).height(cell * spec.height),
                playable && !frame.paused && !frame.computing && state.winner == null && state.turn != spec.botPlayer, onPlay)
        } else Box(modifier.size(120.dp), contentAlignment = Alignment.Center) { Text("规则校验中") }
    }
    BoxWithConstraints(Modifier.then(if (fullscreen) Modifier.fillMaxSize().safeDrawingPadding() else Modifier.fillMaxWidth())) {
        val availableWidth = maxWidth
        val availableHeight = maxHeight
        if (fullscreen && availableWidth > availableHeight) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                board(availableWidth * .55f, availableHeight - 32.dp)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { controls(); footer() }
            }
        } else {
            val controlRows = ((spec?.controls?.size ?: 0) + 3) / 4
            val chromeHeight = 300.dp + 54.dp * controlRows +
                (if (frame.error != null) 80.dp else 0.dp) + (if (frame.pending != null) 48.dp else 0.dp)
            Column(Modifier.then(if (fullscreen) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                controls()
                board(availableWidth - 24.dp, if (fullscreen) (availableHeight - chromeHeight).coerceAtLeast(120.dp) else 280.dp)
                footer()
            }
        }
    }
}

private fun sessionScore(state: RuleState?) = (state?.vars?.get("score") as? JsonPrimitive)?.intOrNull ?: 0

@Composable
private fun RuleBoard(spec: RuleSpec, state: RuleState, modifier: Modifier, enabled: Boolean, onPlay: (RuleEvent) -> Unit) {
    val palette = remember(spec.palette) { spec.palette.map { Color(it.toColorInt()) } }
    Canvas(modifier.testTag("rule_board").background(palette[0], RoundedCornerShape(8.dp))
        .semantics { contentDescription = "${spec.width}×${spec.height}棋盘，已操作 ${state.moves} 次" }
        .pointerInput(spec, enabled) {
            if (enabled && "tap" in spec.handlers) detectTapGestures { offset ->
                onPlay(RuleEvent("tap", (offset.x / size.width * spec.width).toInt().coerceIn(0, spec.width - 1),
                    (offset.y / size.height * spec.height).toInt().coerceIn(0, spec.height - 1)))
            }
        }) {
        val cw = size.width / spec.width; val ch = size.height / spec.height
        if (spec.intersections) {
            repeat(spec.width) { x -> drawLine(Color(0xFF9B856B), Offset((x + .5f) * cw, ch / 2), Offset((x + .5f) * cw, size.height - ch / 2)) }
            repeat(spec.height) { y -> drawLine(Color(0xFF9B856B), Offset(cw / 2, (y + .5f) * ch), Offset(size.width - cw / 2, (y + .5f) * ch)) }
        } else {
            repeat(spec.width + 1) { x -> drawLine(Color.Black.copy(alpha = .07f), Offset(x * cw, 0f), Offset(x * cw, size.height)) }
            repeat(spec.height + 1) { y -> drawLine(Color.Black.copy(alpha = .07f), Offset(0f, y * ch), Offset(size.width, y * ch)) }
        }
        fun stone(x: Int, y: Int, value: Int) {
            if (value == 0 || x !in 0 until spec.width || y !in 0 until spec.height) return
            if (spec.intersections) {
                drawCircle(Color.DarkGray, minOf(cw, ch) * .43f, Offset((x + .5f) * cw, (y + .5f) * ch))
                drawCircle(palette[value], minOf(cw, ch) * .39f, Offset((x + .5f) * cw, (y + .5f) * ch))
            } else drawRect(palette[value], Offset(x * cw + 1, y * ch + 1), Size((cw - 2).coerceAtLeast(1f), (ch - 2).coerceAtLeast(1f)))
        }
        state.cells.forEachIndexed { i, value -> stone(i % spec.width, i / spec.width, value) }
        spec.overlay?.let { overlay ->
            val x = state.vars.getValue(overlay.x).integer(); val y = state.vars.getValue(overlay.y).integer()
            RuleEngine.points(state.vars.getValue(overlay.shape)).forEach { (px, py) -> stone(x + px, y + py, overlay.color) }
        }
    }
}
