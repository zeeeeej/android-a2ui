package com.xpl.a2uichat.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

val agentJson = Json { ignoreUnknownKeys = true }

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class AgentReply(val text: String, val a2uiMessages: List<JsonObject>)

sealed interface AgentEvent {
    data class Text(val text: String) : AgentEvent
    data class UiMessage(val message: JsonObject) : AgentEvent
    data class Complete(val reply: AgentReply, val raw: String) : AgentEvent
}

class AgentException(message: String) : Exception(message)

/** Frames complete A2UI objects without confusing JSON brackets inside strings.
 * Only the root text field is exposed incrementally; no raw JSON reaches the chat UI.
 * The entire response is validated again at EOF before entering conversation history.
 */
class ReplyDecoder {
    private val raw = StringBuilder()
    private val stack = mutableListOf<Char>()
    private var inString = false
    private var escaped = false
    private var stringStart = 0
    private var textStart = -1
    private var textEnd = -1
    private var rootKey = ""
    private var expectingKey = true
    private var messageArray = false
    private var objectStart = -1
    private var messageCount = 0
    private var emittedText = ""

    fun append(fragment: String): List<AgentEvent> {
        if (raw.length + fragment.length > MAX_RESPONSE_CHARS) {
            throw AgentException("回复过长，请缩小问题范围后重试。")
        }
        val events = mutableListOf<AgentEvent>()
        val offset = raw.length
        raw.append(fragment)
        for (i in offset until raw.length) {
            val c = raw[i]
            if (inString) {
                if (escaped) escaped = false
                else when (c) {
                    '\\' -> escaped = true
                    '"' -> {
                        inString = false
                        if (stack.size == 1) {
                            if (expectingKey) {
                                rootKey = agentJson.decodeFromString<String>(raw.substring(stringStart, i + 1))
                                expectingKey = false
                            } else if (stringStart == textStart) textEnd = i
                        }
                    }
                }
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    stringStart = i
                    if (stack.size == 1 && !expectingKey && rootKey == "text") textStart = i
                }
                '{', '[' -> {
                    if (c == '[' && stack.size == 1) messageArray = rootKey == "a2uiMessages"
                    if (c == '{' && stack.size == 2 && messageArray) objectStart = i
                    stack.add(c)
                    if (stack.size > 40) throw AgentException("回复结构过于复杂，请重试。")
                }
                '}', ']' -> {
                    val expected = if (c == '}') '{' else '['
                    if (stack.removeLastOrNull() != expected) throw AgentException("回复格式无效，请重试。")
                    if (c == '}' && stack.size == 2 && messageArray && objectStart >= 0) {
                        if (++messageCount > 64) throw AgentException("卡片数量过多，请缩小问题范围。")
                        events += AgentEvent.UiMessage(agentJson.decodeFromString<JsonObject>(raw.substring(objectStart, i + 1)))
                        objectStart = -1
                    }
                    if (c == ']' && stack.size == 1) messageArray = false
                }
                ',' -> if (stack.size == 1) expectingKey = true
            }
        }
        if (textStart >= 0) {
            val end = if (textEnd >= 0) textEnd else raw.length
            val text = decodePartialString(raw.substring(textStart + 1, end))
            if (text != emittedText) {
                emittedText = text
                events.add(0, AgentEvent.Text(text))
            }
        }
        return events
    }

    fun finish(): AgentEvent.Complete {
        val reply = try {
            agentJson.decodeFromString<AgentReply>(raw.toString())
        } catch (_: Exception) {
            throw AgentException("回复未完整生成或格式无效，请重试。")
        }
        if (reply.text.isBlank() && reply.a2uiMessages.isEmpty()) throw AgentException("模型返回了空回复，请重试。")
        if (reply.a2uiMessages.size > 64) throw AgentException("卡片数量过多，请缩小问题范围。")
        return AgentEvent.Complete(reply, raw.toString())
    }

    companion object {
        const val MAX_RESPONSE_CHARS = 262_144

        private fun decodePartialString(value: String): String {
            val result = StringBuilder()
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (c != '\\') {
                    if (c < ' ') throw AgentException("回复包含无效字符，请重试。")
                    result.append(c)
                    continue
                }
                if (i == value.length) break
                when (val escape = value[i++]) {
                    '"', '\\', '/' -> result.append(escape)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        if (i + 4 > value.length) break
                        val code = value.substring(i, i + 4).toIntOrNull(16)
                            ?: throw AgentException("回复包含无效字符，请重试。")
                        result.append(code.toChar())
                        i += 4
                    }
                    else -> throw AgentException("回复包含无效字符，请重试。")
                }
            }
            // Don't briefly expose half of a UTF-16 surrogate pair (e.g. an emoji).
            if (result.isNotEmpty() && result.last().isHighSurrogate()) result.setLength(result.length - 1)
            return result.toString()
        }
    }
}
