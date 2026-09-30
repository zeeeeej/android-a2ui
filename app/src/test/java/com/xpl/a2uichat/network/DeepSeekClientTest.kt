package com.xpl.a2uichat.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DeepSeekClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DeepSeekClient
    private val raw = """{"text":"你好","a2uiMessages":[]}"""
    @Before fun setup() {
        server = MockWebServer()
        server.start()
        client = DeepSeekClient("test-key", "test-model", server.url("/chat/completions").toString())
    }
    @After fun teardown() { server.shutdown() }

    @Test fun completeReplyAndRequestContract() = runBlocking {
        server.enqueue(MockResponse().setBody(buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("finish_reason", "stop")
                put("message", buildJsonObject { put("content", raw) })
            }) })
        }.toString()))
        val history = listOf(ChatMessage("system", "output JSON"), ChatMessage("user", "你好"))
        val result = client.reply(history, false).toList()
        assertEquals("你好", (result.last() as AgentEvent.Complete).reply.text)
        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        val body = agentJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertFalse(body.getValue("stream").jsonPrimitive.boolean)
        assertEquals("json_object", body.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(2, body.getValue("messages").jsonArray.size)
    }

    private fun streamBody(finish: String = "stop", done: Boolean = true): String = buildString {
        append(": keepalive\n\n")
        raw.chunked(3).forEach { text ->
            append("data: ")
            append(buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                put("delta", buildJsonObject { put("content", text) }); put("finish_reason", JsonNull)
            }) }) })
            append("\n\n")
        }
        append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$finish\"}]}\n\n")
        if (done) append("data: [DONE]\n\n")
    }

    @Test fun streamedReplyMatchesCompleteReply() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(streamBody()))
        val events = client.reply(listOf(ChatMessage("user", "你好")), true).toList()
        assertEquals("你好", (events.last() as AgentEvent.Complete).reply.text)
        assertTrue(events.any { it is AgentEvent.Text })
    }

    @Test fun missingDoneAndTokenLimitAreFailures() = runBlocking {
        for (body in listOf(streamBody(done = false), streamBody(finish = "length"))) {
            server.enqueue(MockResponse().setBody(body))
            val error = runCatching { client.reply(emptyList(), true).toList() }.exceptionOrNull()
            assertTrue(error is AgentException)
        }
    }

    @Test fun httpFailuresAreSanitizedAndNeverExposeResponseBody() = runBlocking {
        for (code in listOf(401, 402, 429, 503)) {
            server.enqueue(MockResponse().setResponseCode(code).setBody("secret-test-key"))
            val error = runCatching { client.reply(emptyList(), false).toList() }.exceptionOrNull()
            assertTrue(error is AgentException)
            assertFalse(error!!.message.orEmpty().contains("secret"))
        }
    }

    @Test fun cancellingDuringBodyReadClosesRequestPromptly() = runBlocking {
        server.enqueue(MockResponse().setBody(streamBody()).setBodyDelay(3, TimeUnit.SECONDS))
        val task = launch(Dispatchers.Default) { client.reply(emptyList(), true).toList() }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)) }
        delay(100)
        withTimeout(2000) { task.cancelAndJoin() }
        assertTrue(task.isCancelled)
    }
}
