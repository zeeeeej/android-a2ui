package com.xpl.a2uichat

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.xpl.a2uichat.chat.*
import com.xpl.a2uichat.games.GameKey
import com.xpl.a2uichat.network.*
import com.xpl.a2uichat.ui.ChatTheme
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

/** Only opt-in acceptance requests. No game-specific implementation or fallback exists here. */
class GameV2LiveTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val models=ViewModelStore()
    @After fun cleanup(){compose.runOnUiThread { models.clear() }}
    @Test fun requestedBoard() = live("生成象棋，电脑为红方，我为黑方。中间写上楚界汉河，士的路径要补上。电脑聪明点。",9,10)
    @Test fun nonTemplateVariant() = live("生成7列9行的五子棋变体让我和电脑玩，我执白、电脑执黑并先手，任意方向连续五子获胜，不用禁手规则。使用 ScriptGame。",7,9)
    private fun live(prompt: String,width: Int,height: Int) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runGameV2Live")=="true")
        val delegate=DeepSeekClient(BuildConfig.DEEPSEEK_API_KEY,BuildConfig.DEEPSEEK_MODEL,BuildConfig.DEEPSEEK_URL)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=java.io.File(context.getExternalFilesDir(null),"verification").apply { mkdirs() }
        var calls=0
        val vm=ChatViewModel(object: AgentClient {
            override fun reply(messages: List<ChatMessage>,stream: Boolean) = reply(messages,stream,ReplyBudget.CHAT)
            override fun reply(messages: List<ChatMessage>,stream: Boolean,maxTokens: Int): kotlinx.coroutines.flow.Flow<AgentEvent> {
                calls++;android.util.Log.i("GameV2Live","request $calls: ${if(messages.first().content.contains("独立游戏审查员")) "review" else if(messages.first().content.contains("当前为需求分流阶段")) "plan" else "generate"}")
                java.io.File(folder,"game-v2-$width-request-$calls.txt").writeText(messages.last().content)
                return delegate.reply(messages,stream,maxTokens).onEach { if(it is AgentEvent.Complete) {
                    android.util.Log.i("GameV2Live","request $calls completed")
                    java.io.File(folder,"game-v2-$width-response-$calls.json").writeText(it.raw)
                } }.catch { e ->
                    if(e is ReplyFormatException) java.io.File(folder,"game-v2-$width-malformed-$calls.txt").writeText(e.partialReply)
                    throw e
                }
            }
        })
        compose.runOnUiThread { models.put("live-v2",vm);compose.activity.setContent { ChatTheme { ChatScreen(vm) } };vm.send(prompt) }
        compose.waitUntil(1200000){!vm.state.value.busy}
        val last=vm.state.value.messages.last()
        assertEquals(last.error,MessageStatus.Complete,last.status);assertTrue(calls in 3..9)
        assertEquals(JsonPrimitive(true),last.verification?.get("passed"))
        val key=GameKey(vm.surfaces.value.single().id,"root","ScriptGame")
        compose.waitUntil(20000){vm.games.findScript(key)?.frame?.value?.snapshot!=null}
        val game=vm.games.findScript(key)!!
        assertEquals(2,game.frame.value.program!!.version)
        assertEquals(width,game.frame.value.snapshot!!.view.width);assertEquals(height,game.frame.value.snapshot!!.view.height)
        compose.onNodeWithTag("script_expand").performScrollTo().performClick()
        compose.onNodeWithTag("script_run").performScrollTo().performClick()
        compose.waitUntil(10000){game.frame.value.error!=null || game.frame.value.moves>=1&&!game.frame.value.computing}
        assertNull(game.frame.value.error)
        val before=game.frame.value.moves
        val binding=game.frame.value.snapshot!!.view.bindings.first()
        binding.clicks.forEach { point ->
            compose.onNodeWithTag("script_board").performTouchInput { click(androidx.compose.ui.geometry.Offset((point.first+.5f)/width*this.width,(point.second+.5f)/height*this.height)) }
        }
        compose.waitUntil(10000){game.frame.value.error!=null || game.frame.value.moves>=before+2&&!game.frame.value.computing}
        assertNull(game.frame.value.error)
        compose.runOnUiThread { game.pause() }
        java.io.File(folder,"game-v2-$width-report.json").writeText(last.verification.toString())
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder,"game-v2-$width.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
    }
}
