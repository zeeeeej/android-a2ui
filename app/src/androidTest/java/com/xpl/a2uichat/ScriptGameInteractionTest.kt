package com.xpl.a2uichat

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import com.xpl.a2uichat.a2ui.CHAT_CATALOG_ID
import com.xpl.a2uichat.chat.*
import com.xpl.a2uichat.games.GameKey
import com.xpl.a2uichat.games.script.*
import com.xpl.a2uichat.network.*
import com.xpl.a2uichat.ui.ChatTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

internal fun scriptReply(surface: String,spec: JsonObject,update: Boolean=false) = """
{"text":"动态游戏已准备好","a2uiMessages":[
${if(update)"" else """{"version":"v0.9","createSurface":{"surfaceId":"$surface","catalogId":"$CHAT_CATALOG_ID","sendDataModel":true}},"""}
{"version":"v0.9","updateComponents":{"surfaceId":"$surface","components":[{"id":"root","component":"ScriptGame","gameId":"root","title":"9路围棋","spec":$spec}]}}]}
""".trimIndent()

class ScriptGameInteractionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val store=ViewModelStore()
    private lateinit var vm: ChatViewModel
    private val requests=mutableListOf<List<ChatMessage>>()
    private fun prefix(m: List<ChatMessage>)=Regex("本轮新 surfaceId 前缀：(s_[a-f0-9]+_)").find(m.first().content)!!.groupValues[1]+"game"
    private fun launch(reply: suspend (Int,List<ChatMessage>)->String) {
        compose.runOnUiThread {
            vm=ChatViewModel(object: AgentClient {
                override fun reply(messages: List<ChatMessage>,stream: Boolean)=flow {
                    requests+=messages;val raw=reply(requests.size,messages);val decoder=ReplyDecoder()
                    decoder.append(raw).forEach { emit(it) };emit(decoder.finish())
                }
            });store.put("script",vm);compose.activity.setContent { ChatTheme { ChatScreen(vm) } };vm.send("创建一个围棋我玩玩")
        }
    }
    private fun ready(): ScriptGameSession {
        compose.waitUntil(20_000){!vm.state.value.busy}
        assertEquals(vm.state.value.messages.last().error,MessageStatus.Complete,vm.state.value.messages.last().status)
        val key=GameKey(vm.surfaces.value.single().id,"root","ScriptGame")
        compose.waitUntil(10_000){vm.games.findScript(key)?.frame?.value?.snapshot!=null}
        compose.onNodeWithTag("script_board").performScrollTo()
        return vm.games.findScript(key)!!
    }
    @After fun cleanup(){compose.runOnUiThread {store.clear()}}
    @Test fun nativeBoardBotScoringAndLifecycle() {
        launch { _,m->scriptReply(prefix(m),scriptFixture("go",9,true).json) }
        val s=ready()
        compose.onNodeWithTag("script_expand").performScrollTo().performClick()
        compose.onNodeWithTag("script_run").performScrollTo().performClick()
        compose.onNodeWithTag("script_board").performTouchInput { click(center) }
        compose.waitUntil(10_000){s.frame.value.moves>=2&&!s.frame.value.computing}
        compose.onNodeWithTag("script_run").performScrollTo().performClick()
        val before=s.frame.value.snapshot
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(s.frame.value.paused)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(before,s.frame.value.snapshot)
        assertEquals(1,requests.size)
    }
    @Test fun failedCompilationRepairsOnceWithoutHistoryLeak() {
        val good=scriptFixture("go").json
        launch { n,m->if(n>2)"""{"text":"你好","a2uiMessages":[]}""" else scriptReply(prefix(m),if(n==1)JsonObject(good+("source" to JsonPrimitive("(() => INVALID_SOURCE"))) else good) }
        ready();assertEquals(2,requests.size)
        compose.runOnUiThread { vm.send("你好") }
        compose.waitUntil(10_000){requests.size==3&&!vm.state.value.busy}
        assertFalse(requests[2].drop(1).any {it.content.contains("INVALID_SOURCE")})
    }
    @Test fun thirdRepairSucceedsWithLatestCandidateAndNoHistoryLeak() {
        val good=scriptFixture("go").json
        launch { n,m->
            if(n in 2..4) {
                assertEquals("正在修复游戏规则（${n-1}/3）…",vm.state.value.messages.last().text)
                assertTrue(m.last().content.contains("第 ${n-1}/3 次自动修复"))
                assertTrue(m[m.lastIndex-1].content.contains("INVALID_SOURCE_${n-1}"))
                assertEquals(4,m.size) // System + original user + latest failed reply/error only.
            }
            when {
                n<=3 -> scriptReply(prefix(m),JsonObject(good+("source" to JsonPrimitive("(() => INVALID_SOURCE_$n"))))
                n==4 -> scriptReply(prefix(m),good)
                else -> """{"text":"你好","a2uiMessages":[]}"""
            }
        }
        ready();assertEquals(4,requests.size)
        compose.runOnUiThread {vm.send("你好")}
        compose.waitUntil(10_000){requests.size==5&&!vm.state.value.busy}
        assertFalse(requests[4].drop(1).any {it.content.contains("INVALID_SOURCE")||it.content.contains("次自动修复")})
    }
    @Test fun repeatedInvalidScriptFailsAfterThreeRepairs() {
        val bad=JsonObject(scriptFixture("go").json+("source" to JsonPrimitive("(() => INVALID_SOURCE")))
        launch { _,m->scriptReply(prefix(m),bad) }
        compose.waitUntil(20_000){!vm.state.value.busy}
        assertEquals(4,requests.size);assertEquals(MessageStatus.Failed,vm.state.value.messages.last().status)
        assertTrue(vm.state.value.messages.last().error!!.contains("已自动修复 3 次"))
        assertNull(vm.games.findScript(GameKey(vm.surfaces.value.firstOrNull()?.id ?: "","root","ScriptGame")))
    }
    @Test fun stoppingThirdRepairDoesNotSendAnotherRequest() {
        val bad=JsonObject(scriptFixture("go").json+("source" to JsonPrimitive("(() => INVALID_SOURCE")))
        launch { n,m->if(n<4)scriptReply(prefix(m),bad)else awaitCancellation() }
        compose.waitUntil(20_000){requests.size==4}
        compose.runOnUiThread {vm.stop()}
        compose.waitForIdle()
        assertFalse(vm.state.value.busy)
        assertEquals(MessageStatus.Stopped,vm.state.value.messages.last().status)
        assertEquals(4,requests.size)
    }
    @Test fun networkFailureDoesNotAutomaticallyRetry() {
        launch { _,_->throw AgentException("网络连接失败") }
        compose.waitUntil(10_000){!vm.state.value.busy}
        assertEquals(1,requests.size)
        assertEquals(MessageStatus.Failed,vm.state.value.messages.last().status)
        assertEquals("网络连接失败",vm.state.value.messages.last().error)
    }
    @Test fun ruleUpdateIsStagedUntilExplicitRestart() {
        var surface=""
        launch { n,m->if(n==1){surface=prefix(m);scriptReply(surface,scriptFixture("go").json)}else scriptReply(surface,scriptFixture("go",7).json,true) }
        val s=ready()
        compose.runOnUiThread {vm.send("改成7路")}
        compose.waitUntil(20_000){!vm.state.value.busy}
        compose.onNodeWithTag("messages").performTouchInput {swipeDown()}
        compose.onNodeWithTag("messages").performScrollToIndex(1)
        compose.onNodeWithTag("script_board").performScrollTo()
        compose.waitUntil {s.frame.value.pending!=null}
        assertEquals(9,s.frame.value.snapshot!!.view.width)
        compose.onNodeWithTag("script_apply").performScrollTo().performClick()
        compose.waitUntil(10_000){s.frame.value.snapshot?.view?.width==7}
    }
    @Test fun runtimeTimeoutKeepsBoardAndRepairRequiresApply() {
        val good=scriptFixture("go")
        val broken=JsonObject(good.json+("source" to JsonPrimitive(good.source.replace("function step(s,a) {","function step(s,a) { if(s.history.length>=5)while(true){}"))))
        var surface=""
        launch { n,m->if(n==1){surface=prefix(m);scriptReply(surface,broken)}else scriptReply(surface,good.json,true) }
        val s=ready()
        compose.onNodeWithTag("script_expand").performScrollTo().performClick()
        compose.onNodeWithTag("script_run").performScrollTo().performClick()
        repeat(4){i->
            compose.runOnUiThread {s.send(s.frame.value.snapshot!!.actions.first())}
            compose.waitUntil(5000){s.frame.value.moves==i+1&&!s.frame.value.computing}
        }
        val before=s.frame.value.snapshot
        compose.runOnUiThread {s.send(s.frame.value.snapshot!!.actions.first())}
        compose.waitUntil(5000){s.frame.value.error!=null}
        assertEquals(before,s.frame.value.snapshot);assertTrue(s.frame.value.paused)
        compose.onNodeWithTag("script_repair").performScrollTo().performClick()
        compose.waitUntil(20_000){requests.size==2&&!vm.state.value.busy}
        assertTrue(requests[1].last().content.contains("script_repair"));assertTrue(requests[1].last().content.contains("activeSpecJson"))
        compose.onNodeWithTag("messages").performTouchInput {swipeDown()}
        compose.onNodeWithTag("messages").performScrollToIndex(1)
        compose.onNodeWithTag("script_board").performScrollTo()
        compose.waitUntil {s.frame.value.pending!=null}
        assertEquals(before,s.frame.value.snapshot)
        compose.onNodeWithTag("script_apply").performScrollTo().performClick()
        compose.waitUntil(10_000){s.frame.value.error==null&&s.frame.value.pending==null&&!s.frame.value.computing}
        assertEquals(0,s.frame.value.moves)
    }
}
