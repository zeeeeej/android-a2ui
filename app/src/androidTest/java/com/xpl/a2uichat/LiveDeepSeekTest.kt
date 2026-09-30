package com.xpl.a2uichat

import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.xpl.a2uichat.chat.ChatViewModel
import com.xpl.a2uichat.chat.MessageStatus
import com.xpl.a2uichat.games.GameKey
import com.xpl.a2uichat.games.SnakeStatus
import java.io.File
import org.junit.Assert.*
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in: consumes a small number of requests using the debug key. Never logs prompts/keys. */
class LiveDeepSeekTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var originalOrientation: Int? = null
    @After fun restoreOrientation() {
        originalOrientation?.let { original -> compose.runOnUiThread { compose.activity.requestedOrientation = original } }
    }
    private fun model() = ViewModelProvider(compose.activity)[ChatViewModel::class.java]

    private fun runTurn(prompt: String, stream: Boolean) {
        compose.runOnUiThread { model().setStreaming(stream); model().send(prompt) }
        compose.waitUntil(180_000) { !model().state.value.busy }
        compose.runOnIdle {
            val reply = model().state.value.messages.last()
            assertEquals(reply.error, MessageStatus.Complete, reply.status)
            assertNull(reply.error)
            assertTrue(reply.text.isNotBlank())
        }
    }

    private fun screenshot(name: String) {
        compose.runOnUiThread {
            WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Compose idleness does not include the system window enter/exit animation.
        instrumentation.uiAutomation.waitForIdle(500, 5_000)
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "verification").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun nativeGamesCompleteAndStreaming() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLive") == "true")
        runTurn("生成一个可以玩的 2048。直接把 Game2048 作为 root。", false)
        compose.onNodeWithTag("game_board").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("game_left").performScrollTo().performClick()
        compose.onNodeWithTag("game_down").performScrollTo().performClick()
        compose.onNodeWithTag("game_right").performScrollTo().performClick()
        val key = GameKey(model().surfaces.value.single().id, "root", "Game2048")
        val board = model().games.get(key).board2048
        assertTrue(board.moves > 0)
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { assertEquals(board, model().games.get(key).board2048) }
        compose.onNodeWithTag("game_expand").performScrollTo().performClick()
        screenshot("08-game-2048-fullscreen")
        originalOrientation = compose.activity.requestedOrientation
        try {
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.waitForIdle()
            compose.onNodeWithTag("game_expand").performScrollTo().performClick()
            compose.onNodeWithTag("game_board").assertIsDisplayed()
            compose.onNodeWithTag("game_result").assertIsDisplayed()
            compose.runOnIdle { assertEquals(board, model().games.get(key).board2048) }
            screenshot("08b-game-2048-landscape")
        } finally {
            // UNSPECIFIED can retain the last orientation until a physical sensor change.
            // Explicitly return to portrait for the remaining UI checks; @After restores policy.
            compose.runOnUiThread { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        compose.waitForIdle()
        compose.onNodeWithTag("game_expand").performScrollTo().performClick()
        compose.onNodeWithTag("game_result").performScrollTo().performClick()
        compose.waitUntil(180_000) { model().state.value.messages.size >= 4 && !model().state.value.busy }
        compose.runOnIdle {
            assertEquals(MessageStatus.Complete, model().state.value.messages.last().status)
            assertEquals(board, model().games.get(key).board2048)
        }
        compose.runOnUiThread { model().newChat() }
        runTurn("来一局简单难度的贪吃蛇。直接把 SnakeGame 作为 root。", true)
        compose.onNodeWithTag("game_board").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("game_expand").performScrollTo().performClick()
        compose.onNodeWithTag("game_run").performClick()
        val snakeKey = GameKey(model().surfaces.value.single().id, "root", "SnakeGame")
        compose.waitUntil(3000) { model().games.get(snakeKey).snake.moves > 0 }
        compose.onNodeWithTag("game_up").performClick()
        compose.onNodeWithTag("game_run").performClick()
        compose.runOnIdle { assertEquals(SnakeStatus.Paused, model().games.get(snakeKey).snake.status) }
        screenshot("09-game-snake-fullscreen")
        compose.onNodeWithTag("game_collapse").performClick()
        screenshot("10-game-snake-card")
    }

    @Test fun generatedRuleGamesAndUnseenVariants() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLive") == "true")
        val prompts = listOf(
            "生成15×15的五子棋让我玩，五连获胜，使用本地电脑对手。",
            "生成10×20的俄罗斯方块让我玩。",
            "生成8×8的四子棋，四连获胜，使用本地电脑对手。",
            "生成6×12的下落方块游戏，只用三格直条与三格L形，每消一行75分。",
        )
        prompts.forEachIndexed { index, prompt ->
            compose.runOnUiThread { model().newChat() }
            runTurn("$prompt 只创建一张 RuleGame 卡片，直接作为 root。", index % 2 == 1)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("rule_board").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("rule_board").performScrollTo()
            val key = GameKey(model().surfaces.value.single().id, "root", "RuleGame")
            val session = model().games.findRule(key)!!
            compose.waitUntil(5000) { session.frame.value.state != null }
            assertEquals(listOf(15, 10, 8, 6)[index], session.frame.value.spec!!.width)
            assertEquals(listOf(15, 20, 8, 12)[index], session.frame.value.spec!!.height)
            compose.onNodeWithTag("rule_expand").performScrollTo().performClick()
            compose.onNodeWithTag("rule_result").assertIsDisplayed()
            session.frame.value.spec!!.controls.lastOrNull()?.let { control ->
                compose.onNodeWithTag("rule_${control.id}").assertIsDisplayed()
            }
            compose.onNodeWithTag("rule_run").performClick()
            if (index % 2 == 0) {
                compose.onNodeWithTag("rule_board").performTouchInput { click(center) }
                compose.waitUntil(5000) { session.frame.value.state!!.cells.count { it != 0 } >= 2 && !session.frame.value.computing }
                assertEquals(1, session.frame.value.state!!.turn)
            } else {
                compose.waitUntil(50_000) { session.frame.value.state!!.cells.any { it != 0 } }
            }
            compose.onNodeWithTag("rule_run").performClick()
            val played = session.frame.value.state
            screenshot("11-rule-$index-fullscreen")
            compose.onNodeWithTag("rule_expand").performClick()
            compose.activityRule.scenario.recreate()
            compose.runOnIdle {
                assertSame(session, model().games.findRule(key))
                assertEquals(played, session.frame.value.state)
                assertTrue(session.frame.value.paused)
                assertEquals(2, model().state.value.messages.size)
            }
        }
    }

    @Test fun completeThenStreamingEndToEnd() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLive") == "true")
        runTurn("请用一句话解释 AI Agent，不生成卡片。", false)
        screenshot("01-complete-chat")
        runTurn("生成只有三个任务的学习清单，使用可勾选的 CheckBox 和提交按钮。", false)
        compose.waitUntil(10_000) { model().surfaces.value.isNotEmpty() }
        compose.waitForIdle()
        screenshot("02-complete-checklist")
        compose.runOnUiThread { model().newChat() }
        runTurn("生成一个简单出行偏好表单：一个标签为「城市」的 TextField，一个标签为「户外」的 CheckBox，一个文字为「提交偏好」的 Button。不要添加其他字段。", false)
        compose.waitUntil(10_000) { model().surfaces.value.isNotEmpty() }
        compose.onNodeWithText("城市").performScrollTo().performTextInput("上海")
        compose.onNodeWithText("户外").performScrollTo().performClick()
        screenshot("03-form-filled")
        // Activity recreation exercises the same ViewModel retention used for rotation.
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("上海").assertExists()
        compose.onNodeWithText("提交偏好").performScrollTo().performClick()
        compose.waitUntil(180_000) { model().state.value.messages.size >= 4 && !model().state.value.busy }
        compose.runOnIdle {
            assertEquals(MessageStatus.Complete, model().state.value.messages.last().status)
            assertTrue(model().state.value.messages.last().text.contains("上海"))
        }
        screenshot("04-form-follow-up")
        compose.runOnUiThread { model().newChat() }
        runTurn("请简短介绍上海的三个适合散步的地方，不生成卡片。", true)
        screenshot("05-stream-chat")
        runTurn("把这三个地点做成一张含三个复选框和提交按钮的卡片。", true)
        compose.waitUntil(10_000) { model().surfaces.value.isNotEmpty() }
        val surfaceId = model().surfaces.value.first().id
        val inSurface = hasAnyAncestor(hasTestTag("surface_$surfaceId"))
        compose.onAllNodes(isToggleable() and inSurface).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        screenshot("06-stream-card")
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) and inSurface)
            .onFirst().performScrollTo().performClick()
        compose.waitUntil(180_000) { model().state.value.messages.size >= 6 && !model().state.value.busy }
        compose.runOnIdle { assertEquals(MessageStatus.Complete, model().state.value.messages.last().status) }
        screenshot("07-stream-follow-up")
    }
}
