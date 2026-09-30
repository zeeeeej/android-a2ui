package com.xpl.a2uichat.chat

import androidx.a2ui.model.processor.A2uiSurfaceModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xpl.a2uichat.BuildConfig

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val surfaces by viewModel.surfaces.collectAsStateWithLifecycle()
    ChatContent(state, surfaces, viewModel::setDraft, { viewModel.send() }, viewModel::send,
        viewModel::stop, viewModel::retry, viewModel::newChat, viewModel::setStreaming, viewModel::canInteract)
}

@Composable
fun ChatContent(
    state: ChatState,
    surfaces: List<A2uiSurfaceModel>,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onSuggestion: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onNewChat: () -> Unit,
    onStreaming: (Boolean) -> Unit,
    canInteract: (String) -> Boolean,
) {
    var confirmNew by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    var followOutput by remember { mutableStateOf(true) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(dragging) {
        if (dragging) followOutput = false
        else if (!listState.canScrollForward) followOutput = true
    }
    LaunchedEffect(state.busy) {
        if (state.busy) { focus.clearFocus(); keyboard?.hide() }
    }
    LaunchedEffect(state.messages.size) {
        followOutput = true
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.let { it.index to it.size } }
            .collect {
                if (followOutput && listState.layoutInfo.totalItemsCount > 0 && listState.canScrollForward) {
                    listState.scrollToItem(listState.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE)
                }
            }
    }
    LaunchedEffect(state.messages.lastOrNull()?.text, surfaces.size, state.messages.lastOrNull()?.status) {
        if (followOutput && state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex, Int.MAX_VALUE)
    }
    Surface(Modifier.fillMaxSize(), color = colors.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Spark(Modifier.size(38.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("A2UI Chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("DEEPSEEK  /  原生交互", fontSize = 10.sp, letterSpacing = 1.sp, color = colors.onSurfaceVariant)
                }
                OutlinedButton(onClick = { if (state.messages.isEmpty()) onNewChat() else confirmNew = true },
                    contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.testTag("new_chat")) {
                    Text("＋ 新对话", fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.messages.isEmpty()) {
                    Welcome(onSuggestion)
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("messages"),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        items(state.messages, key = { it.id }) { message ->
                            MessageItem(message, surfaces, state.busy, canInteract,
                                onRetry = onRetry, retryEnabled = state.retryId == message.id && !state.busy)
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 18.dp).padding(bottom = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(colors.primary, CircleShape))
                    Text(if (state.busy) "正在生成${if (state.streaming) " · 流式" else " · 完整回复"}" else BuildConfig.DEEPSEEK_MODEL,
                        Modifier.weight(1f).padding(start = 7.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text("流式", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Switch(checked = state.streaming, onCheckedChange = onStreaming, enabled = !state.busy,
                        modifier = Modifier.padding(start = 6.dp).testTag("streaming"))
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = state.draft, onValueChange = onDraft,
                        placeholder = { Text("聊点什么，或让 AI 生成一张卡片…", fontSize = 14.sp) },
                        modifier = Modifier.weight(1f).testTag("chat_input"), shape = RoundedCornerShape(22.dp),
                        minLines = 1, maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (!state.busy) onSend() }),
                        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = colors.outlineVariant))
                    FilledIconButton(onClick = if (state.busy) onStop else onSend,
                        enabled = state.busy || state.draft.isNotBlank(),
                        modifier = Modifier.size(56.dp).testTag(if (state.busy) "stop" else "send"),
                        shape = RoundedCornerShape(19.dp)) {
                        Text(if (state.busy) "■" else "↑", fontSize = 26.sp,
                            modifier = Modifier.clearAndSetSemantics { contentDescription = if (state.busy) "停止生成" else "发送" })
                    }
                }
                Text("AI 生成内容仅供参考", modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = .65f))
            }
        }
    }
    if (confirmNew) AlertDialog(onDismissRequest = { confirmNew = false },
        title = { Text("开启新对话？") }, text = { Text("当前消息和卡片将被清空。") },
        confirmButton = { TextButton(onClick = { confirmNew = false; onNewChat() }) { Text("新建对话") } },
        dismissButton = { TextButton(onClick = { confirmNew = false }) { Text("取消") } })
}

@Composable
private fun Welcome(onSuggestion: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 26.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 22.dp, bottom = 26.dp)) {
                Surface(shape = RoundedCornerShape(50), color = colors.primaryContainer) {
                    Text("不止于对话", Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                        color = colors.onPrimaryContainer, style = MaterialTheme.typography.labelMedium)
                }
                Text("把想法，\n聊成行动。", Modifier.padding(top = 22.dp), fontSize = 36.sp,
                    lineHeight = 47.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
                Text("提一个问题，获得答案。\n说一个想法，让 AI 为你生成可操作的卡片。",
                    Modifier.padding(top = 16.dp), color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium, lineHeight = 23.sp)
            }
        }
        item { Suggestion("01", "规划今天", "把待办变成可勾选的任务清单", "帮我生成一张今天的学习任务清单，包含可勾选的任务和提交按钮。", onSuggestion) }
        item { Suggestion("02", "安排周末", "填写偏好，获得专属的出行建议", "制作一个周末出行偏好表单，让我填写城市、预算，勾选是否喜欢户外，再提交生成建议。", onSuggestion) }
        item { Suggestion("03", "随便聊聊", "探索一个新知识，或理清一个想法", "用一个生活中的例子，简单解释什么是 AI Agent。", onSuggestion) }
    }
}

@Composable
private fun Suggestion(number: String, title: String, subtitle: String, prompt: String, onClick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = { onClick(prompt) }, shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .6f)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(number, color = colors.primary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(32.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, Modifier.padding(top = 5.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text("↗", color = colors.primary, fontSize = 22.sp)
        }
    }
}

@Composable
private fun MessageItem(message: UiMessage, surfaces: List<A2uiSurfaceModel>, busy: Boolean,
    canInteract: (String) -> Boolean, onRetry: () -> Unit, retryEnabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start) {
        if (message.isUser) {
            Surface(shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp), color = colors.primaryContainer,
                modifier = Modifier.widthIn(max = 330.dp)) {
                SelectionContainer { Text(message.text, Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                    style = MaterialTheme.typography.bodyLarge, color = colors.onPrimaryContainer) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spark(Modifier.size(25.dp))
                Text("DeepSeek", Modifier.padding(start = 9.dp), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
            if (message.text.isNotEmpty()) SelectionContainer {
                Text(message.text, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp)
            }
            if (message.status == MessageStatus.Generating) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(if (message.text.isEmpty()) "正在构思…" else "正在生成…", Modifier.padding(start = 8.dp),
                        color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                }
            }
            message.surfaceIds.forEach { id ->
                val surface = surfaces.find { it.id == id } ?: return@forEach
                val enabled = !busy && canInteract(id)
                Box(Modifier.padding(top = 14.dp).fillMaxWidth().testTag("surface_$id")
                    .then(if (enabled) Modifier else Modifier.alpha(.65f).blockInteraction())) {
                    A2uiSurface(surfaceModel = surface, modifier = Modifier.fillMaxWidth(),
                        errorContent = { Text("卡片暂时无法显示，请重试。", color = colors.error, modifier = Modifier.padding(16.dp)) },
                        loadingContent = { Text("正在准备卡片…", color = colors.onSurfaceVariant, modifier = Modifier.padding(16.dp)) })
                }
            }
            message.error?.let { error ->
                Text(error, Modifier.padding(top = 10.dp), color = if (message.status == MessageStatus.Stopped) colors.onSurfaceVariant else colors.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (retryEnabled) TextButton(onClick = onRetry, modifier = Modifier.testTag("retry")) { Text("重新生成") }
        }
    }
}

private fun Modifier.blockInteraction(): Modifier = clearAndSetSemantics { disabled() }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }

@Composable
private fun Spark(modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier.background(colors.primary, RoundedCornerShape(12.dp)).padding(6.dp)) {
        val cx = size.width / 2
        val cy = size.height / 2
        val path = Path().apply {
            moveTo(cx, 0f); quadraticTo(cx * 1.18f, cy * .82f, size.width, cy)
            quadraticTo(cx * 1.18f, cy * 1.18f, cx, size.height)
            quadraticTo(cx * .82f, cy * 1.18f, 0f, cy)
            quadraticTo(cx * .82f, cy * .82f, cx, 0f); close()
        }
        drawPath(path, colors.onPrimary)
        drawCircle(colors.onPrimary.copy(alpha = .65f), size.width * .06f, Offset(size.width * .9f, size.height * .1f))
    }
}
