package com.xpl.a2uichat.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReplyDecoderTest {
    private val ui = buildJsonObject {
        put("version", "v0.9")
        put("updateDataModel", buildJsonObject {
            put("surfaceId", "s_demo")
            put("value", "含有 } ] 和 \\\" 引号")
        })
    }
    private val reply = AgentReply("你好 🌿\n他说：\"保持好奇\"。\\ / \t", listOf(ui, ui))
    private val raw = agentJson.encodeToString(reply)

    @Test fun everySplitProducesSameTextAndExactlyOnceMessages() {
        for (split in 0..raw.length) {
            val decoder = ReplyDecoder()
            val events = decoder.append(raw.take(split)) + decoder.append(raw.drop(split))
            assertEquals(reply, decoder.finish().reply)
            assertEquals(reply.text, events.filterIsInstance<AgentEvent.Text>().last().text)
            assertEquals(reply.a2uiMessages, events.filterIsInstance<AgentEvent.UiMessage>().map { it.message })
        }
    }

    @Test fun oneCharacterChunksAndUnicodeEscapes() {
        val decoder = ReplyDecoder()
        val events = """{"text":"\u4f60\u597d \ud83c\udf3f","a2uiMessages":[]}""".flatMap { decoder.append(it.toString()) }
        assertEquals("你好 🌿", decoder.finish().reply.text)
        assertTrue(events.filterIsInstance<AgentEvent.Text>().all { !it.text.last().isHighSurrogate() })
    }

    @Test fun messagesBeforeTextStillWork() {
        val decoder = ReplyDecoder()
        decoder.append("""{"a2uiMessages":[$ui],"text":"完成"}""")
        assertEquals(AgentReply("完成", listOf(ui)), decoder.finish().reply)
    }

    @Test fun textIsVisibleBeforeJsonFinishes() {
        val events = ReplyDecoder().append("""{"text":"正在逐字出现""")
        assertEquals("正在逐字出现", (events.single() as AgentEvent.Text).text)
    }

    @Test fun incompleteUiNeverEmitsAMessage() {
        val decoder = ReplyDecoder()
        val events = decoder.append("""{"text":"已显示文字","a2uiMessages":[{"version":"v0.9"""")
        assertTrue(events.none { it is AgentEvent.UiMessage })
        assertThrows(AgentException::class.java) { decoder.finish() }
    }

    @Test fun invalidAndEmptyEnvelopesAreRejected() {
        listOf("{}", "[]", "null", """{"text":"","a2uiMessages":[]}""",
            """{"text":"hi","a2uiMessages":[null]}""", """{"text":"hello"}""").forEach { raw ->
            assertThrows(Exception::class.java) { ReplyDecoder().apply { append(raw); finish() } }
        }
    }

    @Test fun responseSizeIsBounded() {
        assertThrows(AgentException::class.java) { ReplyDecoder().append("x".repeat(ReplyDecoder.MAX_RESPONSE_CHARS + 1)) }
    }

    @Test fun malformedRuleReplyRetainsBoundedCandidateOnlyForRepair() {
        val broken = """{"text":"游戏","a2uiMessages":[{"component":"RuleGame","spec":{"version":1]}}]}"""
        val fault = assertThrows(ReplyFormatException::class.java) { ReplyDecoder().append(broken) }
        assertTrue(fault.containsRuleGame)
        assertEquals(broken, fault.partialReply)
        assertFalse(fault.message!!.contains("spec"))
        val ordinary = assertThrows(ReplyFormatException::class.java) { ReplyDecoder().append("""{"text":"聊天" ]""") }
        assertFalse(ordinary.containsRuleGame)
    }

    @Test fun sseHandlesCommentsMultilineAndDone() {
        val decoder = SseDecoder()
        assertNull(decoder.line(": keepalive"))
        assertNull(decoder.line("event: message"))
        assertNull(decoder.line("data: {"))
        assertNull(decoder.line("data: \"ok\":true}"))
        assertEquals("{\n\"ok\":true}", decoder.line(""))
        assertNull(decoder.line("data: [DONE]"))
        assertEquals("[DONE]", decoder.line(""))
        assertNull(decoder.line(""))
    }
}
