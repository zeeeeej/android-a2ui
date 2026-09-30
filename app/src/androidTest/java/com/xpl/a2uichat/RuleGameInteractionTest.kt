package com.xpl.a2uichat

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import com.xpl.a2uichat.a2ui.CHAT_CATALOG_ID
import com.xpl.a2uichat.chat.*
import com.xpl.a2uichat.games.GameKey
import com.xpl.a2uichat.games.rules.*
import com.xpl.a2uichat.network.*
import com.xpl.a2uichat.ui.ChatTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal fun ruleReply(surface: String, spec: JsonObject, update: Boolean = false): String = """
    {"text":"规则游戏已准备好","a2uiMessages":[
    ${if (update) "" else """{"version":"v0.9","createSurface":{"surfaceId":"$surface","catalogId":"$CHAT_CATALOG_ID","sendDataModel":true}},"""}
    {"version":"v0.9","updateComponents":{"surfaceId":"$surface","components":[{"id":"root","component":"RuleGame","gameId":"root","title":"规则游戏","spec":$spec}]}}]}
""".trimIndent()

class RuleGameInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: ChatViewModel
    private val requests = mutableListOf<List<ChatMessage>>()
    private fun launch(reply: suspend (Int, List<ChatMessage>) -> String) {
        compose.runOnUiThread {
            vm = ChatViewModel(object : AgentClient {
                override fun reply(messages: List<ChatMessage>, stream: Boolean) = flow {
                    requests += messages
                    val raw = reply(requests.size, messages)
                    val decoder = ReplyDecoder()
                    decoder.append(raw).forEach { emit(it) }; emit(decoder.finish())
                }
            })
            store.put("rules", vm)
            compose.activity.setContent { ChatTheme { ChatScreen(vm) } }
            vm.send("生成规则游戏")
        }
    }
    private fun prefix(messages: List<ChatMessage>) = Regex("本轮新 surfaceId 前缀：(s_[a-f0-9]+_)").find(messages.first().content)!!.groupValues[1] + "game"
    private fun invalid() = JsonObject(RuleExamples.connection() + ("width" to JsonPrimitive(999)))
    private fun ready(): RuleGameSession {
        compose.waitUntil(15_000) { !vm.state.value.busy }
        compose.runOnIdle { assertEquals(vm.state.value.messages.last().error, MessageStatus.Complete, vm.state.value.messages.last().status) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("rule_board").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("rule_board").performScrollTo()
        val key = GameKey(vm.surfaces.value.single().id, "root", "RuleGame")
        compose.waitUntil(5000) { vm.games.findRule(key)?.frame?.value?.state != null }
        return vm.games.findRule(key)!!
    }
    @After fun cleanup() { compose.runOnUiThread { store.clear() } }

    @Test fun automaticRepairIsBoundedAndOnlyCorrectedReplyEntersHistory() {
        launch { n, messages ->
            if (n <= 2) ruleReply(prefix(messages), if (n == 1) invalid() else RuleExamples.connection(8,8,4))
            else """{"text":"继续对话","a2uiMessages":[]}"""
        }
        ready()
        assertEquals(2, requests.size)
        assertTrue(requests[1].last().content.contains("校验失败"))
        compose.runOnUiThread { vm.send("你好") }
        compose.waitUntil { requests.size == 3 && !vm.state.value.busy }
        assertEquals(4, vm.state.value.messages.size)
        assertFalse(requests[2].drop(1).any { it.content.contains("999") || it.content.contains("次自动修复") })
    }

    @Test fun fourthInvalidCandidateFailsWithoutFifthRequest() {
        launch { _, messages -> ruleReply(prefix(messages), invalid()) }
        compose.waitUntil(15_000) { !vm.state.value.busy }
        assertEquals(4, requests.size)
        assertEquals(MessageStatus.Failed, vm.state.value.messages.last().status)
        assertTrue(vm.state.value.messages.last().error!!.contains("已自动修复 3 次"))
    }

    @Test fun malformedRuleJsonCanSucceedOnThirdRepair() {
        launch { n, messages ->
            val raw = ruleReply(prefix(messages), RuleExamples.connection(8, 8, 4))
            if (n <= 3) raw.replace("\"version\":1", "\"version\":1]") else raw
        }
        ready()
        assertEquals(4, requests.size)
        requests.drop(1).forEach { assertTrue(it.last().content.contains("格式无效")) }
    }

    @Test fun stoppingRepairDoesNotStartAnotherAttemptOrLeakHistory() {
        launch { n, messages -> if (n == 1) ruleReply(prefix(messages), invalid()) else awaitCancellation() }
        compose.waitUntil(15_000) { requests.size == 2 }
        compose.runOnUiThread { vm.stop() }
        compose.waitForIdle()
        assertFalse(vm.state.value.busy)
        assertEquals(MessageStatus.Stopped, vm.state.value.messages.last().status)
        assertEquals(2, requests.size)
    }

    @Test fun failedUpdatePreservesOldBoardAndSuccessfulUpdateRequiresApply() {
        var surface = ""
        launch { n, messages ->
            if (n == 1) { surface = prefix(messages); ruleReply(surface, RuleExamples.connection(8,8,4)) }
            else ruleReply(surface, if (n < 6) invalid() else RuleExamples.connection(6,6,3), update = true)
        }
        val session = ready()
        compose.onNodeWithTag("rule_expand").performScrollTo().performClick()
        compose.onNodeWithTag("rule_run").performClick()
        compose.onNodeWithTag("rule_board").performTouchInput { click(center) }
        compose.waitUntil(5000) { session.frame.value.state!!.moves >= 2 && !session.frame.value.computing }
        compose.onNodeWithTag("rule_run").performClick()
        compose.onNodeWithTag("rule_expand").performClick()
        val before = session.frame.value.state
        compose.runOnUiThread { vm.send("更新规则") }
        compose.waitUntil(15_000) { !vm.state.value.busy }
        assertEquals(5, requests.size)
        assertEquals(MessageStatus.Failed, vm.state.value.messages.last().status)
        assertEquals(before, session.frame.value.state)
        compose.runOnUiThread { vm.send("再试一次") }
        compose.waitUntil(15_000) { !vm.state.value.busy }
        // A real upward-history gesture disables the chat's follow-latest-output behavior.
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.onNodeWithTag("messages").performScrollToIndex(1)
        compose.onNodeWithTag("rule_board").performScrollTo()
        compose.waitUntil { session.frame.value.pending != null }
        assertEquals(8, session.frame.value.spec!!.width)
        compose.onNodeWithTag("rule_apply").performScrollTo().performClick()
        compose.waitUntil { session.frame.value.spec?.width == 6 }
    }

    @Test fun fallingGameRunsLocallyAndPausesAcrossFullscreenAndBackground() {
        launch { _, messages -> ruleReply(prefix(messages), RuleExamples.falling()) }
        val session = ready()
        compose.onNodeWithTag("rule_expand").performScrollTo().performClick()
        compose.onNodeWithTag("rule_drop").assertIsDisplayed()
        compose.onNodeWithTag("rule_result").assertIsDisplayed()
        compose.onNodeWithTag("rule_run").performClick()
        compose.onNodeWithTag("rule_drop").performClick()
        compose.waitUntil { session.frame.value.state!!.cells.any { it != 0 } }
        compose.onNodeWithTag("rule_run").performClick()
        val played = session.frame.value.state
        compose.onNodeWithTag("rule_expand").performClick()
        assertEquals(played, session.frame.value.state)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(session.frame.value.paused)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(played, session.frame.value.state)
        assertEquals(1, requests.size)
    }

    @Test fun runtimeFaultRollsBackAndManualRepairCarriesActiveRules() {
        val broken = Json.parseToJsonElement("""{"version":1,"width":4,"height":4,"initial":{"score":0},"handlers":{"tap":[{"set":"score","value":{"op":"add","args":[{"var":"score"},1]}},{"if":{"op":"ge","args":[{"var":"score"},3]},"then":[{"gridSet":[100,0,1]}]}]}}""").jsonObject
        val fixed = JsonObject(broken + ("handlers" to Json.parseToJsonElement("""{"tap":[{"set":"score","value":{"op":"add","args":[{"var":"score"},1]}}]}""")))
        var surface = ""
        launch { n, messages ->
            if (n == 1) { surface = prefix(messages); ruleReply(surface, broken) }
            else ruleReply(surface, fixed, update = true)
        }
        val session = ready()
        compose.onNodeWithTag("rule_expand").performScrollTo().performClick()
        compose.onNodeWithTag("rule_run").performClick()
        repeat(2) { i ->
            compose.onNodeWithTag("rule_board").performTouchInput { click(center) }
            compose.waitUntil { session.frame.value.state!!.moves == i + 1 }
        }
        val before = session.frame.value.state
        compose.onNodeWithTag("rule_board").performTouchInput { click(center) }
        compose.waitUntil { session.frame.value.error != null }
        assertTrue(session.frame.value.paused)
        assertEquals(before, session.frame.value.state)
        compose.onNodeWithTag("rule_repair").performClick()
        compose.waitUntil(15_000) { requests.size == 2 && !vm.state.value.busy }
        assertTrue(requests[1].last().content.contains("rule_repair"))
        assertTrue(requests[1].last().content.contains("activeSpecJson"))
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.onNodeWithTag("messages").performScrollToIndex(1)
        compose.onNodeWithTag("rule_board").performScrollTo()
        compose.waitUntil { session.frame.value.pending != null }
        assertEquals(before, session.frame.value.state)
    }
}
