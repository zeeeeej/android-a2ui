package com.xpl.a2uichat.a2ui

import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.engine.model.A2uiCoreSurfaceModel
import androidx.a2ui.model.processor.A2uiActionInterceptor
import androidx.a2ui.model.processor.A2uiSurfaceModel
import androidx.a2ui.model.protocol.*
import com.xpl.a2uichat.network.AgentException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.UUID

class A2uiSession(
    scope: CoroutineScope,
    canInteract: (String) -> Boolean,
    onAction: (A2uiClientEventMessage) -> Unit,
    onError: (String) -> Unit,
) {
    private val barriers = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val parser = A2uiMessageParser()
    private val processor = A2uiMessageProcessor(listOf(chatCatalog), listOf(A2uiActionInterceptor { action ->
        action.takeIf { canInteract(it.surfaceId) }
    }))
    val surfaces: StateFlow<List<A2uiSurfaceModel>> = processor.activeSurfaces
    private val jobs: List<Job> = listOf(
        scope.launch { processor.collectMessages() },
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            processor.outboundEvents.collect { event ->
                when (event) {
                    is A2uiClientEventMessage -> if (canInteract(event.surfaceId)) onAction(event)
                    is A2uiClientErrorMessage -> {
                        if (event.code == "APP_PROCESSING_BARRIER") barriers.remove(event.message)?.complete(Unit)
                        else onError(event.surfaceId)
                    }
                }
            }
        },
    )

    fun validate(message: JsonObject) {
        try { parser.parse(message.toString()) }
        catch (_: Exception) { throw AgentException("卡片协议无效，请重试。") }
    }

    fun apply(message: JsonObject) { processor.processMessage(parser.parse(message.toString())) }
    fun delete(id: String) { processor.processMessage(A2uiDeleteSurfaceMessage(id)) }
    fun close() { jobs.forEach { it.cancel() } }

    fun snapshotData(): Map<String, JsonElement> = surfaces.value.associate { surface ->
        surface.id to (surface as A2uiCoreSurfaceModel).dataModel[A2uiDataPath("/")].toJsonValue()
    }

    /** Wait for actor processing, not merely enqueueing. Markers never reach the model. */
    suspend fun awaitApplied(expectedIds: Set<String>) {
        try {
            withTimeout(5000) {
                surfaces.first { items -> items.map { it.id }.toSet() == expectedIds }
                expectedIds.forEach { id ->
                    val token = UUID.randomUUID().toString()
                    val done = CompletableDeferred<Unit>()
                    barriers[token] = done
                    try {
                        processor.processError(A2uiClientErrorMessage("APP_PROCESSING_BARRIER", id, token))
                        done.await()
                    } finally { barriers.remove(token) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw AgentException("卡片处理超时，请重试。")
        }
    }

    companion object {
        /** Validate a transaction against a disposable official processor before updating old cards. */
        suspend fun validateTransaction(messages: List<JsonObject>, expectedIds: Set<String>) = coroutineScope {
            var invalid = false
            val probe = A2uiSession(this, { false }, {}, { invalid = true })
            try {
                messages.forEach(probe::apply)
                probe.awaitApplied(expectedIds)
                if (invalid) throw AgentException("卡片字段无效，请重试或要求生成更简单的卡片。")
            } finally { probe.close() }
        }
    }
}

internal fun Any?.toJsonValue(): JsonElement = when (this) {
    null -> JsonNull
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { it.key.toString() to it.value.toJsonValue() })
    is Iterable<*> -> JsonArray(map { it.toJsonValue() })
    else -> JsonPrimitive(toString())
}
