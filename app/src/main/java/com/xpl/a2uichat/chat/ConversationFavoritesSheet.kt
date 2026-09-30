package com.xpl.a2uichat.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationFavoritesSheet(state: ChatState, onDismiss: () -> Unit,
    onOpen: (SavedConversation) -> Unit, onRemove: (String) -> Unit) {
    var removing by remember { mutableStateOf<SavedConversation?>(null) }
    val locale = LocalConfiguration.current.locales[0]
    val dates = remember(locale) { SimpleDateFormat("MM-dd HH:mm", locale) }
    val enabled = !state.busy && !state.loading && !state.storageBusy && !state.storageBlocked
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("收藏对话", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
        Text("文字和卡片保存在本机 · 游戏重新开局", Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall)
        if (state.favorites.isEmpty()) Text("还没有收藏，点击对话顶部的 ☆ 即可保存。", Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("favorite_items"), contentPadding = PaddingValues(16.dp)) {
            items(state.favorites, key = { it.id }) { item ->
                ListItem(headlineContent = { Text(item.title, maxLines = 2) },
                    supportingContent = { Column {
                        Text(item.messages.lastOrNull { it.text.isNotBlank() }?.text?.take(80) ?: "", maxLines = 2)
                        Text(dates.format(Date(item.updatedAt)) +
                            if (item.id == state.conversationId) " · 当前对话" else "")
                    } },
                    trailingContent = { Column {
                        TextButton(onClick = { onOpen(item) }, enabled = enabled, modifier = Modifier.testTag("open_favorite_${item.id}")) { Text("打开") }
                        TextButton(onClick = { if (item.id == state.conversationId) onRemove(item.id) else removing = item },
                            enabled = enabled, modifier = Modifier.testTag("remove_favorite_${item.id}")) { Text("取消收藏") }
                    } })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    removing?.let { item ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("取消收藏？") },
            text = { Text("“${item.title}”不是当前对话，取消收藏将移除这条本地记录，无法撤销。") },
            confirmButton = { TextButton(onClick = { removing = null; onRemove(item.id) }, enabled = enabled) { Text("移除记录") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } })
    }
}
