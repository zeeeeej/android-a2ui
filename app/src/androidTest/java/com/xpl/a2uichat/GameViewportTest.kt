package com.xpl.a2uichat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.xpl.a2uichat.games.*
import com.xpl.a2uichat.ui.ChatTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GameViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clippedBoardPausesEvenWhenItsLazyItemIsStillComposedAndScrollBackKeepsState() {
        val store = GameSessionStore()
        val key = GameKey("test", "snake", "SnakeGame")
        val session = store.get(key, Difficulty.Easy)
        compose.setContent {
            ChatTheme {
                var viewport by remember { mutableStateOf(Rect.Zero) }
                LazyColumn(Modifier.fillMaxSize().testTag("list").onGloballyPositioned { viewport = it.boundsInWindow() }) {
                    item {
                        CompositionLocalProvider(LocalGameHost provides GameHost(store, "test", true, viewport)) {
                            Column {
                                NativeGame("SnakeGame", "snake", "贪吃蛇", Difficulty.Easy) { error("Gameplay must stay local") }
                                Spacer(Modifier.height(1500.dp))
                                Text("同一条消息底部", Modifier.testTag("footer"))
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("game_run").performScrollTo().performClick()
        compose.waitUntil(3000) { session.snake.moves > 0 }
        compose.onNodeWithTag("footer").performScrollTo()
        compose.waitUntil { session.snake.status == SnakeStatus.Paused }
        val paused = session.snake
        compose.onNodeWithTag("game_board").performScrollTo()
        compose.runOnIdle { assertEquals(paused, session.snake); assertSame(session, store.get(key)) }
        compose.onNodeWithTag("game_run").performScrollTo().performClick()
        compose.waitUntil(3000) { session.snake.moves > paused.moves }
        compose.runOnUiThread { store.clear() }
    }
}
