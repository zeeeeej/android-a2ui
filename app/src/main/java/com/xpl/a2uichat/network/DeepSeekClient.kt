package com.xpl.a2uichat.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resumeWithException

interface AgentClient {
    fun reply(messages: List<ChatMessage>, stream: Boolean): Flow<AgentEvent>
}

class DeepSeekClient(
    private val apiKey: String,
    private val model: String,
    private val endpoint: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build(),
) : AgentClient {
    override fun reply(messages: List<ChatMessage>, stream: Boolean): Flow<AgentEvent> = flow {
        if (apiKey.isBlank()) throw AgentException("未配置 API Key，请在 .key 中配置后重新构建调试版。")
        val payload = buildJsonObject {
            put("model", model)
            put("messages", agentJson.encodeToJsonElement(messages))
            put("thinking", buildJsonObject { put("type", "disabled") })
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("max_tokens", 8192)
            put("stream", stream)
        }
        val call = http.newCall(Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build())
        try {
            coroutineScope {
                // Also cancel a blocking body read after headers have arrived.
                val cancelWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { call.cancel() }
                }
                try {
                    call.awaitResponse().use { response ->
                        if (!response.isSuccessful) throw AgentException(httpError(response.code))
                        val body = response.body ?: throw AgentException("服务返回空响应，请重试。")
                        val decoder = ReplyDecoder()
                        if (!stream) {
                            val source = body.source()
                            val buffer = okio.Buffer()
                            while (!source.exhausted()) {
                                source.read(buffer, 8192)
                                if (buffer.size > 2L * ReplyDecoder.MAX_RESPONSE_CHARS) {
                                    throw AgentException("服务响应过长，请缩小问题范围。")
                                }
                            }
                            val root = agentJson.parseToJsonElement(buffer.readUtf8()).jsonObject
                            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                                ?: throw AgentException("服务返回空回复，请重试。")
                            checkFinish(choice["finish_reason"]?.jsonPrimitive?.contentOrNull)
                            val content = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                                ?: throw AgentException("服务返回空回复，请重试。")
                            val events = decoder.append(content)
                            val complete = decoder.finish()
                            events.forEach { emit(it) }
                            emit(complete)
                        } else {
                            var finished = false
                            var done = false
                            val sse = SseDecoder()
                            val source = body.source()
                            while (!source.exhausted()) {
                                val line = source.readUtf8LineStrict(2L * ReplyDecoder.MAX_RESPONSE_CHARS)
                                val data = sse.line(line) ?: continue
                                if (data == "[DONE]") { done = true; break }
                                val chunk = agentJson.parseToJsonElement(data).jsonObject
                                if (chunk.containsKey("error")) throw AgentException("服务中断了回复，请重试。")
                                val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                                choice["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull?.let {
                                    decoder.append(it).forEach { event -> emit(event) }
                                }
                                choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let {
                                    checkFinish(it)
                                    finished = true
                                }
                            }
                            if (!done || !finished) throw AgentException("连接中断，回复尚未完成，请重试。")
                            emit(decoder.finish())
                        }
                    }
                } finally { cancelWatcher.cancel() }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: AgentException) { throw e }
        catch (_: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            throw AgentException("请求超时，请检查网络后重试。")
        }
        catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw AgentException("网络连接失败，请检查网络后重试。")
        }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw AgentException("回复格式异常，请重试。")
        }
    }.flowOn(Dispatchers.IO)

    private fun checkFinish(reason: String?) {
        if (reason != "stop") throw AgentException(when (reason) {
            "length" -> "回复达到长度限制，请缩小问题范围后重试。"
            "content_filter" -> "该请求暂时无法回答，请调整问题。"
            else -> "模型未能完成回复，请重试。"
        })
    }

    private fun httpError(code: Int) = when (code) {
        401, 403 -> "API Key 无效或没有访问权限，请检查 .key 后重新构建。"
        402 -> "DeepSeek 余额不足，请检查账户。"
        429 -> "请求过于频繁，请稍后重试。"
        in 500..599 -> "DeepSeek 服务暂时不可用，请稍后重试。"
        else -> "请求失败（HTTP $code），请检查模型配置后重试。"
    }
}

internal class SseDecoder {
    private val lines = mutableListOf<String>()
    private var size = 0
    fun line(line: String): String? {
        if (line.isEmpty()) {
            val data = lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
            lines.clear()
            size = 0
            return data
        }
        if (line.startsWith("data:")) {
            val data = line.removePrefix("data:").removePrefix(" ")
            size += data.length
            if (size > 2 * ReplyDecoder.MAX_RESPONSE_CHARS) throw AgentException("服务响应过长。")
            lines += data
        }
        return null
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
