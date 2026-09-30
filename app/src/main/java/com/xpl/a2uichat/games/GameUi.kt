package com.xpl.a2uichat.games

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs

data class GameHost(val store: GameSessionStore, val surfaceId: String, val enabled: Boolean, val viewport: Rect)
val LocalGameHost = staticCompositionLocalOf<GameHost?> { null }

@Composable
fun NativeGame(type: String, gameId: String, title: String, difficulty: Difficulty,
    modifier: Modifier = Modifier, onResult: (Map<String, Any>) -> Unit) {
    val host = LocalGameHost.current ?: return
    val session = remember(host.store, host.surfaceId, gameId, type) {
        host.store.get(GameKey(host.surfaceId, gameId, type), difficulty)
    }
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
    LaunchedEffect(session, session.running, allowed, expanded) {
        if (allowed && session.running) runSnakeClock(session) { latestAllowed }
    }
    val move: (Direction) -> Unit = { if (latestAllowed) session.move(it) }
    val toggleExpanded = { host.store.pauseAll(); expanded = !expanded }
    val result = {
        if (latestAllowed) {
            session.pause()
            val snapshot = session.result()
            expanded = false
            onResult(snapshot)
        }
    }
    Card(modifier.fillMaxWidth().testTag("game_${host.surfaceId}_$gameId"),
        shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        if (expanded) {
            Text("$title · 已全屏展开", Modifier.padding(20.dp))
        } else {
            GamePanel(session, title, difficulty, allowed, false, move,
                onRun = { if (latestAllowed) { if (session.running) session.pause() else host.store.resume(session) } },
                onRestart = { if (latestAllowed) session.restart(difficulty) }, onExpand = toggleExpanded, onResult = result,
                expandEnabled = host.enabled && resumed,
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    val clipped = bounds.intersect(host.viewport)
                    visible = clipped.width > 1f && clipped.height > 1f
                    // Pause immediately even before the next composition/clock effect.
                    if (!visible) session.pause()
                })
        }
    }
    if (expanded) Dialog(onDismissRequest = { session.pause(); expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val boardSize = minOf(maxWidth - 32.dp, maxHeight - 32.dp, 420.dp).coerceAtLeast(120.dp)
                GamePanel(session, title, difficulty, allowed, true, move,
                    onRun = { if (latestAllowed) { if (session.running) session.pause() else host.store.resume(session) } },
                    onRestart = { if (latestAllowed) session.restart(difficulty) }, onExpand = toggleExpanded, onResult = result,
                    landscape = maxWidth > maxHeight, fullBoardSize = boardSize)
            }
        }
    }
}

@Composable
private fun GamePanel(session: GameSession, title: String, difficulty: Difficulty, enabled: Boolean,
    fullScreen: Boolean, onMove: (Direction) -> Unit, onRun: () -> Unit, onRestart: () -> Unit,
    onExpand: () -> Unit, onResult: () -> Unit, modifier: Modifier = Modifier,
    landscape: Boolean = false, fullBoardSize: Dp = 320.dp, expandEnabled: Boolean = enabled) {
    val b = session.board2048
    val s = session.snake
    val score = if (session.isSnake) s.score else b.score
    val status = if (session.isSnake) "${s.difficulty.label} · ${s.status.label}" else when {
        b.over -> "游戏结束"; b.awaitingContinue -> "达到 2048！"; b.won -> "已达成 2048 · 继续挑战"; else -> "合并方块，挑战 2048"
    }
    val controls: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                TextButton(onClick = onExpand, enabled = expandEnabled || fullScreen,
                    modifier = Modifier.testTag(if (fullScreen) "game_collapse" else "game_expand")) {
                    Text(if (fullScreen) "收起" else "全屏")
                }
            }
            Text("得分 $score", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("game_score"))
            Text(status, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("game_status"))
            if (session.isSnake && difficulty != s.difficulty) Text("${difficulty.label}难度将在下一局生效", style = MaterialTheme.typography.bodySmall)
            if (session.isSnake) Button(onClick = onRun, enabled = enabled && s.status !in listOf(SnakeStatus.Lost, SnakeStatus.Won),
                modifier = Modifier.testTag("game_run")) {
                Text(when (s.status) { SnakeStatus.Running -> "暂停"; SnakeStatus.Ready -> "开始"; else -> "继续" })
            }
            if (!session.isSnake && b.awaitingContinue) Button(onClick = { session.continue2048() }, enabled = enabled) { Text("继续挑战") }
        }
    }
    val buttons: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Direction.entries.forEach { direction ->
                    FilledTonalIconButton(onClick = { onMove(direction) }, enabled = enabled &&
                        (if (session.isSnake) session.running else !b.over && !b.awaitingContinue),
                        modifier = Modifier.size(48.dp).testTag("game_${direction.name.lowercase()}")
                            .semantics { contentDescription = direction.label }) {
                        Text(direction.arrow, fontSize = 24.sp)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRestart, enabled = enabled, modifier = Modifier.testTag("game_restart")) { Text("重新开始") }
                TextButton(onClick = onResult, enabled = enabled, modifier = Modifier.testTag("game_result")) { Text("让 AI 点评") }
            }
        }
    }
    val board: @Composable (Dp) -> Unit = { size ->
        GameBoard(session, modifier.size(size).testTag("game_board").pointerInput(session, enabled) {
            if (enabled) {
                var drag = Offset.Zero
                detectDragGestures(onDragStart = { drag = Offset.Zero }, onDragCancel = { drag = Offset.Zero },
                    onDragEnd = {
                        if (drag.getDistance() >= 20.dp.toPx()) onMove(
                            if (abs(drag.x) > abs(drag.y)) { if (drag.x > 0) Direction.Right else Direction.Left }
                            else if (drag.y > 0) Direction.Down else Direction.Up)
                    }) { change, amount -> change.consume(); drag += amount }
            }
        })
    }
    if (landscape) {
        Row(Modifier.fillMaxSize().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            board(fullBoardSize)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                controls(); buttons()
            }
        }
    } else {
        Column(Modifier.then(if (fullScreen) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier)
            .padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            controls()
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                board(minOf(maxWidth, if (fullScreen) 420.dp else 300.dp))
            }
            buttons()
            Text("棋盘内滑动或使用方向键 · 本地运行", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GameBoard(session: GameSession, modifier: Modifier) {
    if (session.isSnake) {
        val board = session.snake
        val colors = MaterialTheme.colorScheme
        Canvas(modifier.background(colors.surfaceContainerHighest, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "贪吃蛇棋盘，长度 ${board.snake.size}，头部 ${board.snake.first().x},${board.snake.first().y}" }) {
            val cell = size.width / 20
            board.snake.forEachIndexed { index, point ->
                drawRoundRect(if (index == 0) colors.primary else colors.tertiary,
                    topLeft = Offset(point.x * cell + 1, point.y * cell + 1), size = Size(cell - 2, cell - 2),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(cell * .2f))
            }
            board.food?.let { drawCircle(colors.error, cell * .38f, Offset((it.x + .5f) * cell, (it.y + .5f) * cell)) }
        }
    } else {
        val cells = session.board2048.cells
        BoxWithConstraints(modifier.background(Color(0xFFB9ADA0), RoundedCornerShape(12.dp)).padding(6.dp)
            .semantics { contentDescription = "2048棋盘：${cells.joinToString(",")}" }) {
            val font = (maxWidth.value / 11).coerceIn(12f, 30f).sp
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(4) { row ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        repeat(4) { col ->
                            val value = cells[row * 4 + col]
                            Box(Modifier.weight(1f).fillMaxHeight().background(tileColor(value), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                                if (value != 0) Text(value.toString(), fontSize = if (value >= 1024) font * .7f else font,
                                    fontWeight = FontWeight.Bold, color = if (value <= 4) Color(0xFF655D54) else Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun tileColor(value: Int) = Color(when (value) {
    0 -> 0xFFCEC3B6; 2 -> 0xFFEEE4DA; 4 -> 0xFFEDE0C8; 8 -> 0xFFE9AA70
    16 -> 0xFFED925A; 32 -> 0xFFE97654; 64 -> 0xFFE65A36; 128 -> 0xFFCFAB44
    256 -> 0xFFC99B33; 512 -> 0xFFBA8C24; 1024 -> 0xFFAE7C18; else -> 0xFF8F671A
})
