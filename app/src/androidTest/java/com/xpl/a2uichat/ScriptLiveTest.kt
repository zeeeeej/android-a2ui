package com.xpl.a2uichat

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.xpl.a2uichat.chat.*
import com.xpl.a2uichat.games.GameKey
import com.xpl.a2uichat.games.script.*
import com.xpl.a2uichat.network.*
import com.xpl.a2uichat.ui.ChatTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.*
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.After

/** Opt-in real DeepSeek generation. Never logs source, credentials or conversation payloads. */
class ScriptLiveTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val models=ViewModelStore()
    @After fun cleanup(){compose.runOnUiThread {models.clear()}}
    @Test fun generatedGo() = live(0,"创建一个围棋我玩玩，9路，人执黑对休闲电脑，包含提子、禁自杀、全局同形禁着、双停后的死子标记与确认计分。")
    @Test fun generatedReversi() = live(1,"生成8×8黑白棋让我和电脑玩，必须夹子翻转，并处理无棋可下时停一手。")
    @Test fun generatedMines() = live(2,"生成6×6扫雷，首次点击安全，可以插旗。")
    @Test fun generatedGoVariant() = live(3,"创建7路围棋让我和电脑玩，白贴5.5目，支持死子标记和确认计分。")
    private fun live(index: Int,prompt: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runScriptLive")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"verification").apply {mkdirs()}
        val delegate=DeepSeekClient(BuildConfig.DEEPSEEK_API_KEY,BuildConfig.DEEPSEEK_MODEL,BuildConfig.DEEPSEEK_URL)
        var candidate=0
        val vm=ChatViewModel(object: AgentClient {
            override fun reply(messages: List<ChatMessage>,stream: Boolean)=delegate.reply(messages,stream).onEach {event->
                if(event is AgentEvent.UiMessage) File(folder,"script-candidate-$index-${candidate++}.json").writeText(event.message.toString())
            }
        })
        compose.runOnUiThread {models.put("live",vm);compose.activity.setContent {ChatTheme {ChatScreen(vm)}}}
        run {
            compose.runOnUiThread {vm.newChat();vm.setStreaming(index%2==0);vm.send(prompt+" 使用 ScriptGame 作为 root。")}
            compose.waitUntil(240_000){!vm.state.value.busy}
            val message=vm.state.value.messages.last()
            assertEquals("游戏 $index: ${message.error}",MessageStatus.Complete,message.status)
            val key=GameKey(vm.surfaces.value.single().id,"root","ScriptGame")
            compose.waitUntil(15_000){vm.games.findScript(key)?.frame?.value?.snapshot!=null}
            val s=vm.games.findScript(key)!!
            File(folder,"script-live-$index.json").writeText(s.frame.value.program!!.json.toString())
            assertEquals(listOf(9,8,6,7)[index],s.frame.value.snapshot!!.view.width)
            if(index==0 || index==3) runBlocking {
                val p=s.frame.value.program!!;val r=ScriptSandbox.shared()
                var board=r.execute(p,"init")
                assertTrue("围棋必须提供停一手",board.view.buttons.any {it.action==scriptAction("pass")})
                // Actual generated rules, not the test fixture: capture and immediate ko.
                for(a in listOf(scriptAction("tap",0,1),scriptAction("tap",1,1),scriptAction("tap",1,0),scriptAction("tap",0,2),
                    scriptAction("tap",2,1),scriptAction("tap",2,2),scriptAction("pass"),scriptAction("tap",1,3),scriptAction("tap",1,2))) {
                    board=r.execute(p,"step",board.state,a);assertTrue("围棋布局动作被拒绝",board.accepted)
                }
                assertEquals(0,board.view.cells[board.view.width+1])
                assertFalse("必须禁止打劫立即回提",r.execute(p,"step",board.state,scriptAction("tap",1,1)).accepted)
                board=r.execute(p,"step",board.state,scriptAction("pass"));board=r.execute(p,"step",board.state,scriptAction("pass"))
                assertEquals("scoring",board.view.phase)
                assertTrue(board.view.buttons.any {it.action==scriptAction("confirm")})
                val marked=r.execute(p,"step",board.state,scriptAction("tap",1,2))
                assertEquals(2,marked.view.marks[2*marked.view.width+1])
                val resumed=r.execute(p,"step",marked.state,scriptAction("resume"))
                assertEquals("playing",resumed.view.phase);assertEquals(board.view.cells,resumed.view.cells)
                val end=r.execute(p,"step",marked.state,scriptAction("confirm"));assertEquals("finished",end.view.phase)
                var empty=r.execute(p,"init");empty=r.execute(p,"step",empty.state,scriptAction("pass"));empty=r.execute(p,"step",empty.state,scriptAction("pass"))
                empty=r.execute(p,"step",empty.state,scriptAction("confirm"))
                assertEquals(2,empty.view.winner)
                assertTrue("计分应显示贴目",empty.view.status.contains(if(index==0)"7.5" else "5.5"))
            }
            compose.onNodeWithTag("script_expand").performScrollTo().performClick()
            compose.onNodeWithTag("script_run").performScrollTo().performClick()
            val action=s.frame.value.snapshot!!.actions.first {it["type"].toString()=="\"tap\""}
            compose.runOnUiThread {s.send(action)}
            compose.waitUntil(10_000){s.frame.value.error!=null || (s.frame.value.moves >= if(index==2)1 else 2)&&!s.frame.value.computing}
            assertNull(s.frame.value.error);assertTrue(s.frame.value.moves>0)
            compose.onNodeWithTag("script_run").performScrollTo().performClick()
            compose.waitForIdle()
            instrumentation.uiAutomation.takeScreenshot().let { bitmap->File(folder,"script-live-$index.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)} }
            compose.onNodeWithTag("script_expand").performScrollTo().performClick()
        }
    }
}
