package com.xpl.a2uichat

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
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

class GameVerificationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val models = ViewModelStore()
    private lateinit var vm: ChatViewModel
    private val requests = mutableListOf<List<ChatMessage>>()
    private var surface = ""
    private val requirements = """[{"id":"R1","text":"中心标记","kind":"boardText","check":{"text":"TEST","region":[0.4,0.4,0.6,0.6]}},{"id":"R2","text":"完整移动","kind":"interaction","check":{"clicks":2}}]"""
    private fun plan(intent: String = "create") = """{"gamePlan":{"intent":"$intent","targetSurfaceId":"${if(intent=="create") "" else surface}","targetComponentId":"root","requirements":$requirements}}"""
    private fun review(pass: Boolean = true) = """{"review":{"requirements":[{"id":"R1","passed":$pass,"evidence":"view 中心绘图 TEST"},{"id":"R2","passed":true,"evidence":"完整 move 绑定起点终点，双方换手"}],"issues":[],"tests":[{"actions":[{"type":"move","from":0,"to":1},{"type":"move","from":5,"to":4}],"expect":{"turn":1,"cells":[[1,0,1],[0,2,2]]}}]}}"""
    private fun candidate(messages: List<ChatMessage>, bad: Boolean = false, update: Boolean = false): String {
        if (!update) surface = Regex("本轮新 surfaceId 前缀：(s_[a-f0-9]+_)").find(messages.first().content)!!.groupValues[1]+"game"
        val p = abstractV2()
        val spec = if (bad) JsonObject(p.json + ("source" to JsonPrimitive(p.source.replace("text:'TEST'","text:'MISSING'"))))
            else if (update) JsonObject(p.json + ("source" to JsonPrimitive(p.source.replace("status:'abstract'","status:'updated'")))) else p.json
        return scriptReply(surface,spec,update)
    }
    private fun launch(repository: ConversationRepository? = null, responses: suspend (Int,List<ChatMessage>) -> String) {
        compose.runOnUiThread {
            vm=ChatViewModel(object: AgentClient {
                override fun reply(messages: List<ChatMessage>,stream: Boolean)=flow {
                    requests+=messages
                    val decoder=ReplyDecoder();decoder.append(responses(requests.size,messages)).forEach { emit(it) };emit(decoder.finish())
                }
            },repository);models.put("v2",vm);compose.activity.setContent { ChatTheme { ChatScreen(vm) } }
        }
        compose.waitUntil(10000) { !vm.state.value.loading }
        compose.runOnUiThread { vm.send("生成抽象移动游戏，中心显示 TEST") }
    }
    private fun done() { compose.waitUntil(30000) { !vm.state.value.busy } }
    private fun ready(): ScriptGameSession {
        done();assertEquals(vm.state.value.messages.last().error,MessageStatus.Complete,vm.state.value.messages.last().status)
        val key=GameKey(surface,"root","ScriptGame")
        compose.waitUntil(10000) { vm.games.findScript(key)?.frame?.value?.snapshot != null }
        return vm.games.findScript(key)!!
    }
    @After fun cleanup() { compose.runOnUiThread { models.clear() } }
    @Test fun threeStagesAndNativeTwoClickSelection() {
        launch { n,m -> when(n) { 1->plan();2->candidate(m);else->review() } }
        val s=ready();assertEquals(3,requests.size);assertNotNull(vm.state.value.messages.last().verification)
        assertFalse(requests[2].last().content.contains("动态游戏已准备好"))
        compose.onNodeWithTag("script_expand").performScrollTo().performClick()
        compose.onNodeWithTag("script_run").performScrollTo().performClick()
        val board=compose.onNodeWithTag("script_board")
        board.performTouchInput { click(Offset(width*.25f,height/6f)) }
        compose.runOnIdle { assertEquals(0,s.frame.value.moves);assertEquals(1,s.frame.value.snapshot!!.view.turn) }
        board.performTouchInput { click(Offset(width*.75f,height/6f)) }
        compose.waitUntil(5000) { s.frame.value.moves==1 }
        assertEquals(2,s.frame.value.snapshot!!.view.turn)
        val bounds=board.getUnclippedBoundsInRoot()
        assertTrue(bounds.top>=compose.onNodeWithTag("script_run").getUnclippedBoundsInRoot().bottom)
        assertTrue(bounds.bottom<=compose.onNodeWithTag("script_repair").getUnclippedBoundsInRoot().top)
    }
    @Test fun failedReviewsUseSharedThreeRepairsAndPublishNothing() {
        launch { n,m -> if(n==1)plan() else if(n%2==0)candidate(m) else review(false) }
        done();assertEquals(9,requests.size);assertEquals(MessageStatus.Failed,vm.state.value.messages.last().status)
        assertTrue(vm.state.value.messages.last().error!!.contains("已自动修复 3 次"))
        assertTrue(vm.state.value.messages.last().surfaceIds.isEmpty())
    }
    @Test fun localVisualFailureSkipsReviewThenRepairs() {
        launch { n,m -> when(n) { 1->plan();2->candidate(m,true);3->candidate(m);else->review() } }
        ready();assertEquals(4,requests.size)
        assertTrue(requests[2].last().content.contains("中心标记"))
    }
    @Test fun inspectDoesNotGenerateOrReplaceGame() {
        launch { n,m -> when(n) { 1->plan();2->candidate(m);3->review();4->plan("inspect");else->review(false) } }
        val s=ready();val original=s.activeRules();val snapshot=s.frame.value.snapshot
        compose.runOnUiThread { vm.send("仔细检查结果，不要修改") }
        done();assertEquals(5,requests.size);assertEquals(original,s.activeRules());assertEquals(snapshot,s.frame.value.snapshot);assertNull(s.frame.value.pending)
        assertEquals(MessageStatus.Complete,vm.state.value.messages.last().status)
        assertTrue(vm.state.value.messages.last().text.contains("未修改现有游戏"))
        assertEquals(1,vm.surfaces.value.size)
    }
    @Test fun cancelDuringReviewDoesNotPublishCandidate() {
        launch { n,m -> when(n) { 1->plan();2->candidate(m);else->awaitCancellation() } }
        compose.waitUntil(15000) { requests.size==3 }
        compose.runOnUiThread { vm.stop() }
        compose.waitForIdle();assertEquals(3,requests.size);assertEquals(MessageStatus.Stopped,vm.state.value.messages.last().status)
        assertNull(vm.state.value.messages.last().verification)
        assertFalse(vm.canInteract(surface))
    }
    @Test fun networkFailureDuringReviewHasNoAutomaticRetry() {
        launch { n,m -> when(n) { 1->plan();2->candidate(m);else->throw AgentException("测试网络中断") } }
        done();assertEquals(3,requests.size);assertEquals("测试网络中断",vm.state.value.messages.last().error)
        assertNull(vm.state.value.messages.last().verification)
    }
    @Test fun verifiedUpdatesStayPendingAndInvalidateOldReport() {
        launch { n,m -> when(n) { 1->plan();2->candidate(m);3->review();4->plan("update");5->candidate(m,update=true);else->review() } }
        val s=ready();val old=s.activeRules()
        compose.runOnUiThread { vm.send("修改当前游戏") }
        done();assertEquals(6,requests.size)
        compose.onNodeWithTag("messages").performScrollToIndex(1)
        compose.onNodeWithTag("script_board").performScrollTo()
        compose.waitUntil(5000) { s.frame.value.pending!=null }
        assertEquals(old,s.activeRules());assertEquals(1,vm.surfaces.value.size)
        assertEquals(JsonPrimitive(false),vm.state.value.messages[1].verification?.get("current"))
        compose.runOnUiThread { s.restart(true) }
        compose.waitUntil(5000) { s.frame.value.snapshot?.view?.status=="updated" }
        assertNotEquals(old,s.activeRules())
    }
    @Test fun favoriteRestoresPlanAndReportWithoutNetwork() {
        val repo=MemoryConversationRepository()
        launch(repo) { n,m -> when(n) { 1->plan();2->candidate(m);else->review() } }
        ready()
        compose.runOnUiThread { vm.toggleFavorite() }
        compose.waitUntil(5000) { !vm.state.value.storageBusy && vm.state.value.favorite }
        val saved=repo.archive.conversations.single()
        assertNotNull(saved.messages.last().verification)
        compose.runOnUiThread {
            models.clear()
            vm=ChatViewModel(object: AgentClient {
                override fun reply(messages: List<ChatMessage>,stream: Boolean)=flow<AgentEvent> { error("Restore must not call model") }
            },repo)
            models.put("restored",vm);compose.activity.setContent { ChatTheme { ChatScreen(vm) } }
        }
        compose.waitUntil(15000) { !vm.state.value.loading }
        assertTrue(vm.state.value.favorite);assertEquals(3,requests.size)
        assertEquals(saved.messages.last().gamePlan,vm.state.value.messages.last().gamePlan)
        assertEquals(JsonPrimitive(true),vm.state.value.messages.last().verification?.get("current"))
    }
}
