package com.xpl.a2uichat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.xpl.a2uichat.a2ui.AgentPrompt
import com.xpl.a2uichat.chat.*
import com.xpl.a2uichat.network.*
import com.xpl.a2uichat.ui.ChatTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var vm: ChatViewModel

    private fun launch(client: AgentClient) {
        compose.runOnUiThread { vm = ChatViewModel(client); store.put("chat", vm) }
        compose.setContent { ChatTheme { ChatScreen(vm) } }
    }
    @After fun cleanup() { compose.runOnUiThread { store.clear() } }

    @Test fun formInputIsSentBackAndNewChatClearsSurfaces() {
        val requests = mutableListOf<List<ChatMessage>>()
        launch(object : AgentClient {
            override fun reply(messages: List<ChatMessage>, stream: Boolean) = flow {
                requests += messages
                val raw = if (requests.size == 1) {
                    val prefix = Regex("本轮新 surfaceId 前缀：(s_[a-f0-9]+_)").find(messages.first().content)!!.groupValues[1]
                    AgentPrompt.formExample("${prefix}form")
                } else """{"text":"已收到上海的户外偏好","a2uiMessages":[]}"""
                val decoder = ReplyDecoder()
                decoder.append(raw).forEach { emit(it) }
                emit(decoder.finish())
            }
        })
        compose.onNodeWithTag("chat_input").performTextInput("生成偏好表单")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.surfaces.value.isNotEmpty() }
        compose.onNodeWithText("想去的城市").performScrollTo().performTextInput("上海")
        compose.onNodeWithText("喜欢户外活动").performScrollTo().performClick()
        compose.onNodeWithText("生成建议").performScrollTo().performClick()
        compose.waitUntil(10_000) { requests.size == 2 && !vm.state.value.busy }
        val action = agentJson.parseToJsonElement(requests.last().last().content).jsonObject
        assertEquals("上海", action["context"]!!.jsonObject["city"]!!.jsonPrimitive.content)
        assertTrue(action["context"]!!.jsonObject["outdoors"]!!.jsonPrimitive.boolean)
        assertTrue(action["clientDataModel"]!!.jsonObject.isNotEmpty())
        compose.onNodeWithText("已收到上海的户外偏好").assertExists()
        compose.onNodeWithTag("new_chat").performClick()
        compose.onNodeWithText("新建对话").performClick()
        compose.waitUntil { vm.state.value.messages.isEmpty() && vm.surfaces.value.isEmpty() }
        compose.onNodeWithText("把想法，\n聊成行动。").assertExists()
    }

    @Test fun stopAndRetryDoNotDuplicateUserOrAssistantMessages() {
        var attempts = 0
        launch(object : AgentClient {
            override fun reply(messages: List<ChatMessage>, stream: Boolean) = flow {
                attempts++
                if (attempts == 1) {
                    emit(AgentEvent.Text("尚未完成"))
                    awaitCancellation()
                } else {
                    val decoder = ReplyDecoder()
                    decoder.append("""{"text":"已完成","a2uiMessages":[]}""").forEach { emit(it) }
                    emit(decoder.finish())
                }
            }
        })
        compose.onNodeWithTag("chat_input").performTextInput("你好")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil { vm.state.value.messages.lastOrNull()?.text == "尚未完成" }
        compose.onNodeWithTag("stop").performClick()
        compose.onNodeWithText("已停止生成").assertExists()
        compose.onNodeWithTag("retry").performClick()
        compose.waitUntil { !vm.state.value.busy }
        assertEquals(2, attempts)
        assertEquals(2, vm.state.value.messages.size)
        assertEquals("已完成", vm.state.value.messages.last().text)
    }

    @Test fun failedReplyIsExcludedFromNextConversationContext() {
        val requests = mutableListOf<List<ChatMessage>>()
        launch(object : AgentClient {
            override fun reply(messages: List<ChatMessage>, stream: Boolean) = flow {
                requests += messages
                if (requests.size == 1) {
                    emit(AgentEvent.Text("不完整的内容"))
                    throw AgentException("模拟连接中断")
                }
                val decoder = ReplyDecoder()
                decoder.append("""{"text":"第二次回复","a2uiMessages":[]}""").forEach { emit(it) }
                emit(decoder.finish())
            }
        })
        compose.runOnUiThread { vm.send("第一次问题") }
        compose.waitUntil { vm.state.value.retryId != null }
        compose.runOnUiThread { vm.send("第二次问题") }
        compose.waitUntil { requests.size == 2 && !vm.state.value.busy }
        assertEquals(listOf("system", "user"), requests.last().map { it.role })
        assertEquals("第二次问题", requests.last().last().content)
    }

    @Test fun invalidUpdatePreservesExistingFormAndEnteredData() {
        var surfaceId = ""
        var attempts = 0
        launch(object : AgentClient {
            override fun reply(messages: List<ChatMessage>, stream: Boolean) = flow {
                attempts++
                val raw = if (attempts == 1) {
                    val prefix = Regex("本轮新 surfaceId 前缀：(s_[a-f0-9]+_)").find(messages.first().content)!!.groupValues[1]
                    surfaceId = "${prefix}form"
                    AgentPrompt.formExample(surfaceId)
                } else """{"text":"保留这段有效文字","a2uiMessages":[{"version":"v0.9","updateComponents":{"surfaceId":"$surfaceId","components":[{"id":"name","component":"TextField","label":123}]}}]}"""
                val decoder = ReplyDecoder()
                decoder.append(raw).forEach { emit(it) }
                emit(decoder.finish())
            }
        })
        compose.runOnUiThread { vm.send("生成表单") }
        compose.waitUntil(10_000) { !vm.state.value.busy }
        compose.onNodeWithText("想去的城市").performScrollTo().performTextInput("上海")
        compose.runOnUiThread { vm.send("更新表单") }
        compose.waitUntil(10_000) { !vm.state.value.busy }
        assertEquals(MessageStatus.Failed, vm.state.value.messages.last().status)
        assertEquals("保留这段有效文字", vm.state.value.messages.last().text)
        // Hiding the IME can temporarily detach the test host's semantics root.
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("messages").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.onNodeWithTag("messages").performScrollToIndex(1)
        compose.onNodeWithText("想去的城市").assertExists()
        compose.onNodeWithText("上海").assertExists()
        assertTrue(vm.canInteract(surfaceId))
    }

    @Test fun newChatCancelsPendingResponseWithoutLeakingIntoNextSession() {
        launch(object : AgentClient {
            override fun reply(messages: List<ChatMessage>, stream: Boolean) = flow {
                emit(AgentEvent.Text("生成中"))
                awaitCancellation()
            }
        })
        compose.runOnUiThread { vm.send("长回复") }
        compose.waitUntil { vm.state.value.messages.lastOrNull()?.text == "生成中" }
        compose.runOnUiThread { vm.newChat() }
        compose.waitForIdle()
        assertFalse(vm.state.value.busy)
        assertTrue(vm.state.value.messages.isEmpty())
        assertTrue(vm.surfaces.value.isEmpty())
        assertNull(vm.state.value.retryId)
    }
}
