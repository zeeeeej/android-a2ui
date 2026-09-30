package com.xpl.a2uichat

import androidx.activity.compose.setContent
import androidx.a2ui.compose.ui.toJsonSchemaString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import com.xpl.a2uichat.a2ui.AgentPrompt
import com.xpl.a2uichat.a2ui.SurfaceGuard
import com.xpl.a2uichat.a2ui.chatCatalog
import com.xpl.a2uichat.chat.*
import com.xpl.a2uichat.games.*
import com.xpl.a2uichat.network.*
import com.xpl.a2uichat.ui.ChatTheme
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GameInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: ChatViewModel
    private lateinit var game: GameSession
    private val requests = mutableListOf<List<ChatMessage>>()

    private fun launch(type: String, pendingSecond: Boolean = false) {
        compose.runOnUiThread {
            vm = ChatViewModel(object : AgentClient {
                override fun reply(messages: List<ChatMessage>, stream: Boolean) = flow {
                    requests += messages
                    if (requests.size == 2 && pendingSecond) awaitCancellation()
                    val prefix = Regex("本轮新 surfaceId 前缀：(s_[a-f0-9]+_)").find(messages.first().content)!!.groupValues[1]
                    val raw = if (requests.size == 1) AgentPrompt.gameExample("${prefix}game", type)
                        else """{"text":"已收到成绩，保持节奏！","a2uiMessages":[]}"""
                    val decoder = ReplyDecoder()
                    decoder.append(raw).forEach { emit(it) }
                    emit(decoder.finish())
                }
            })
            store.put("game", vm)
            compose.activity.setContent { ChatTheme { ChatScreen(vm) } }
            vm.send("玩游戏")
        }
        compose.waitUntil(15_000) { !vm.state.value.busy && vm.surfaces.value.isNotEmpty() }
        compose.runOnIdle {
            assertEquals(MessageStatus.Complete, vm.state.value.messages.last().status)
            game = vm.games.get(GameKey(vm.surfaces.value.single().id, "root", type))
        }
        compose.onNodeWithTag("game_board").performScrollTo().assertIsDisplayed()
    }

    @After fun cleanup() { compose.runOnUiThread { store.clear() } }

    @Test fun registeredCatalogMatchesGuardAndDeclaresGameSchemas() {
        assertEquals(SurfaceGuard.COMPONENTS, chatCatalog.components.map { it.name }.toSet())
        val schema = chatCatalog.toJsonSchemaString()
        assertTrue(schema.contains("Game2048"))
        assertTrue(schema.contains("SnakeGame"))
        assertTrue(schema.contains("gameId"))
    }

    @Test fun local2048ControlsAndFullscreenKeepProgressAndOnlyManualResultCallsModel() {
        launch("Game2048")
        compose.onNodeWithTag("game_left").performScrollTo().performClick()
        compose.onNodeWithTag("game_down").performScrollTo().performClick()
        compose.onNodeWithTag("game_right").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(game.board2048.moves > 0); assertEquals(1, requests.size) }
        val before = game.board2048
        compose.onNodeWithTag("game_expand").performScrollTo().performClick()
        compose.onNodeWithTag("game_collapse").assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, game.board2048) }
        compose.onNodeWithTag("game_board").performTouchInput { swipeRight() }
        compose.onNodeWithTag("game_collapse").performClick()
        val result = game.result()
        compose.onNodeWithTag("game_result").performScrollTo().performClick()
        compose.waitUntil(15_000) { requests.size == 2 && !vm.state.value.busy }
        compose.runOnIdle {
            val event = agentJson.parseToJsonElement(requests.last().last().content).jsonObject
            assertEquals("game_result", event["name"]!!.jsonPrimitive.content)
            assertEquals(result["score"].toString(), event["context"]!!.jsonObject["score"]!!.jsonPrimitive.content)
            assertEquals("2048", event["context"]!!.jsonObject["game"]!!.jsonPrimitive.content)
            assertSame(game, vm.games.get(game.key))
            assertEquals(MessageStatus.Complete, vm.state.value.messages.last().status)
            vm.newChat()
            assertNotSame(game, vm.games.get(game.key))
        }
    }

    @Test fun snakePausesAcrossFullscreenAndBackgroundAndRequiresManualResume() {
        launch("SnakeGame")
        compose.onNodeWithTag("game_run").performScrollTo().performClick()
        compose.waitUntil(3000) { game.snake.moves > 0 }
        compose.onNodeWithTag("game_expand").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(SnakeStatus.Paused, game.snake.status) }
        val position = game.snake.snake
        compose.onNodeWithTag("game_run").performClick()
        compose.waitUntil(3000) { game.snake.snake != position }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(SnakeStatus.Paused, game.snake.status)
        val paused = game.snake
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(paused, game.snake); assertEquals(1, requests.size) }
        compose.onNodeWithTag("game_collapse").performClick()
        compose.onNodeWithTag("game_restart").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(SnakeStatus.Ready, game.snake.status); assertEquals(0, game.snake.moves) }
    }

    @Test fun generatingReplyPausesSnakeAndDisablesItsControls() {
        launch("SnakeGame", pendingSecond = true)
        compose.onNodeWithTag("game_run").performScrollTo().performClick()
        compose.waitUntil(3000) { game.snake.moves > 0 }
        compose.runOnUiThread { vm.send("给我建议") }
        compose.waitUntil { requests.size == 2 }
        compose.runOnIdle { assertEquals(SnakeStatus.Paused, game.snake.status) }
        compose.onNodeWithTag("messages").performScrollToIndex(1)
        compose.onNodeWithTag("game_run", useUnmergedTree = true).assertIsNotEnabled()
        compose.runOnUiThread { vm.stop() }
        compose.runOnIdle { assertFalse(game.running) }
    }
}
