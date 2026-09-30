package com.xpl.a2uichat.chat

import androidx.a2ui.model.processor.A2uiSurfaceModel
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xpl.a2uichat.BuildConfig
import com.xpl.a2uichat.a2ui.A2uiSession
import com.xpl.a2uichat.a2ui.AgentPrompt
import com.xpl.a2uichat.a2ui.SurfaceGuard
import com.xpl.a2uichat.a2ui.toJsonValue
import com.xpl.a2uichat.network.*
import com.xpl.a2uichat.games.GameSessionStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

enum class MessageStatus { Generating, Complete, Failed, Stopped }

data class UiMessage(
    val id: String,
    val isUser: Boolean,
    val text: String = "",
    val surfaceIds: List<String> = emptyList(),
    val status: MessageStatus = MessageStatus.Complete,
    val error: String? = null,
)

data class ChatState(
    val messages: List<UiMessage> = emptyList(),
    val draft: String = "",
    val busy: Boolean = false,
    val streaming: Boolean = true,
    val retryId: String? = null,
)

class ChatViewModel(
    private val client: AgentClient = DeepSeekClient(BuildConfig.DEEPSEEK_API_KEY,
        BuildConfig.DEEPSEEK_MODEL, BuildConfig.DEEPSEEK_URL),
) : ViewModel() {
    val games = GameSessionStore()
    private val mutableState = MutableStateFlow(ChatState())
    val state = mutableState.asStateFlow()
    private val mutableSurfaces = MutableStateFlow<List<A2uiSurfaceModel>>(emptyList())
    val surfaces = mutableSurfaces.asStateFlow()
    private val surfaceOwners = mutableMapOf<String, String>()
    private val invalidSurfaces = mutableSetOf<String>()
    private var guard = SurfaceGuard()
    private var history = emptyList<ChatMessage>()
    private var generation: Job? = null
    private var surfaceCollection: Job? = null
    private var session = createSession()
    private var pending: PendingTurn? = null
    private var epoch = 0

    private data class PendingTurn(
        val id: String,
        val apiContent: String,
        val baseHistory: List<ChatMessage>,
        val prefix: String,
        val created: MutableSet<String> = mutableSetOf(),
    )

    fun setDraft(value: String) { mutableState.value = state.value.copy(draft = value.take(8000)) }
    fun setStreaming(value: Boolean) { if (!state.value.busy) mutableState.value = state.value.copy(streaming = value) }

    fun send(text: String = state.value.draft) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || state.value.busy) return
        startTurn(trimmed.take(8000), trimmed.take(8000))
    }

    private fun startTurn(label: String, apiContent: String) {
        if (state.value.busy) return
        pending?.let { previous ->
            if (state.value.messages.find { it.id == previous.id }?.status != MessageStatus.Complete) {
                discardPartial(previous)
            }
        }
        val id = UUID.randomUUID().toString()
        val turn = PendingTurn(id, apiContent, history, "s_${id.take(8)}_")
        pending = turn
        mutableState.value = state.value.copy(
            messages = state.value.messages + UiMessage("user_$id", true, label) +
                UiMessage(id, false, status = MessageStatus.Generating),
            draft = "", retryId = null,
        )
        generate(turn)
    }

    fun retry() {
        val turn = pending ?: return
        if (state.value.busy || state.value.retryId != turn.id) return
        discardPartial(turn)
        update(turn.id) { it.copy(text = "", surfaceIds = emptyList(), error = null, status = MessageStatus.Generating) }
        generate(turn)
    }

    fun stop() {
        if (!state.value.busy) return
        epoch++
        generation?.cancel()
        generation = null
        pending?.let { turn -> update(turn.id) { it.copy(status = MessageStatus.Stopped, error = "已停止生成") } }
        mutableState.value = state.value.copy(busy = false, retryId = pending?.id)
    }

    fun newChat() {
        games.clear()
        epoch++
        generation?.cancel()
        surfaceCollection?.cancel()
        session.close()
        pending = null
        history = emptyList()
        guard = SurfaceGuard()
        surfaceOwners.clear()
        invalidSurfaces.clear()
        mutableSurfaces.value = emptyList()
        mutableState.value = ChatState(streaming = state.value.streaming)
        session = createSession()
    }

    private fun generate(turn: PendingTurn) {
        games.pauseAll()
        val ownEpoch = ++epoch
        mutableState.value = state.value.copy(busy = true, retryId = null)
        val transaction = guard.copy()
        val existing = guard.ids
        val deferred = mutableListOf<JsonObject>()
        val before = guard.snapshotMessages(session.snapshotData())
        val touched = mutableSetOf<String>()
        val stream = state.value.streaming
        generation = viewModelScope.launch {
            try {
                val system = AgentPrompt.system() + "\n本轮新 surfaceId 前缀：${turn.prefix}；可更新的已有 surfaceId：${existing.joinToString()}。"
                val messages = listOf(ChatMessage("system", system)) + turn.baseHistory + ChatMessage("user", turn.apiContent)
                if (messages.sumOf { it.content.length } > 220_000) {
                    throw AgentException("当前对话过长，请新建对话后继续。")
                }
                client.reply(messages, stream).collect { event ->
                    if (ownEpoch != epoch) return@collect
                    when (event) {
                        is AgentEvent.Text -> update(turn.id) { it.copy(text = event.text) }
                        is AgentEvent.UiMessage -> {
                            val id = transaction.accept(event.message, turn.prefix)
                            session.validate(event.message)
                            touched += id
                            if (id in existing) deferred += event.message
                            else {
                                turn.created += id
                                surfaceOwners[id] = turn.id
                                session.apply(event.message)
                                update(turn.id) { it.copy(surfaceIds = (it.surfaceIds + id).distinct()) }
                            }
                        }
                        is AgentEvent.Complete -> {
                            transaction.validateComplete()
                            A2uiSession.validateTransaction(before + event.reply.a2uiMessages, transaction.ids)
                            session.awaitApplied(existing + (transaction.ids - existing))
                            if (touched.any { it in invalidSurfaces }) throw AgentException("卡片无法显示，请重试。")
                            deferred.forEach { session.apply(it) }
                            session.awaitApplied(transaction.ids)
                            if (touched.any { it in invalidSurfaces }) throw AgentException("卡片更新失败，请重试。")
                            guard = transaction
                            games.retain(guard.gameKeys())
                            history = turn.baseHistory + ChatMessage("user", turn.apiContent) + ChatMessage("assistant", event.raw)
                            update(turn.id) { it.copy(text = event.reply.text, status = MessageStatus.Complete) }
                        }
                    }
                }
                if (state.value.messages.find { it.id == turn.id }?.status == MessageStatus.Generating) {
                    throw AgentException("回复尚未完成，请重试。")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (ownEpoch == epoch) {
                    update(turn.id) { it.copy(status = MessageStatus.Failed,
                        error = if (e is AgentException) e.message else "回复处理失败，请重试。") }
                    mutableState.value = state.value.copy(retryId = turn.id)
                }
            } finally {
                if (ownEpoch == epoch) mutableState.value = state.value.copy(busy = false)
            }
        }
    }

    private fun createSession(): A2uiSession {
        val created = A2uiSession(viewModelScope, ::canInteract, ::onAction) { surfaceId ->
            invalidSurfaces += surfaceId
            surfaceOwners[surfaceId]?.let { owner ->
                update(owner) { it.copy(error = "这张卡片无法显示，请重试或要求简化卡片。") }
            }
        }
        surfaceCollection = viewModelScope.launch { created.surfaces.collect { mutableSurfaces.value = it } }
        return created
    }

    fun canInteract(surfaceId: String): Boolean = !state.value.busy && surfaceId !in invalidSurfaces &&
        state.value.messages.any { it.id == surfaceOwners[surfaceId] && it.status == MessageStatus.Complete }

    private fun onAction(event: A2uiClientEventMessage) {
        val payload = buildJsonObject {
            put("type", "a2ui_user_action")
            put("name", event.type)
            put("surfaceId", event.surfaceId)
            put("componentId", event.componentId)
            put("timestamp", event.timestamp)
            put("context", event.context.toJsonValue())
            put("clientDataModel", event.clientDataModel?.surfaces.toJsonValue())
        }
        val details = event.context.values.filterNotNull().joinToString(" · ").take(100)
        val label = if (event.type == "game_result") {
            val name = if (event.context["game"] == "snake") "贪吃蛇" else "2048"
            "$name 得分 ${event.context["score"]}，请帮我点评"
        } else if (details.isBlank()) "已提交卡片" else "已提交：$details"
        startTurn(label, payload.toString())
    }

    private fun discardPartial(turn: PendingTurn) {
        games.removeSurfaces(turn.created)
        turn.created.forEach { session.delete(it); surfaceOwners.remove(it); invalidSurfaces.remove(it) }
        turn.created.clear()
        update(turn.id) { it.copy(surfaceIds = emptyList()) }
    }

    private fun update(id: String, transform: (UiMessage) -> UiMessage) {
        mutableState.value = state.value.copy(messages = state.value.messages.map { if (it.id == id) transform(it) else it })
    }

    override fun onCleared() { games.clear(); session.close() }
}
