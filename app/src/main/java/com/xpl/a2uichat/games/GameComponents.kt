package com.xpl.a2uichat.games

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Properties are static configuration, never executable code or per-frame model bindings. */
class NativeGameComponent(override val name: String, override val description: String) : A2uiComponent {
    private val gameId = A2uiProperty.string("gameId", required = true, description = "必须与此组件的 id 相同")
    private val title = A2uiProperty.string("title", description = "游戏标题，最多 80 字")
    private val difficulty = A2uiProperty.stringEnum("difficulty", listOf("easy", "normal", "hard"), defaultValue = "normal")
    override val properties = if (name == "SnakeGame") listOf(gameId, title, difficulty) else listOf(gameId, title)

    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        val id = properties[gameId] ?: return
        NativeGame(name, id, properties[title] ?: if (name == "Game2048") "2048" else "贪吃蛇",
            if (name == "SnakeGame") Difficulty.from(properties[difficulty]) else Difficulty.Normal,
            modifier) { result ->
            dispatchAction(mapOf("event" to mapOf("name" to "game_result", "context" to result)))
        }
    }
}

val gameComponents = listOf(
    NativeGameComponent("Game2048", "可直接玩的原生 4×4 2048，内置滑动、方向键、分数、重开、全屏和手动成绩点评。"),
    NativeGameComponent("SnakeGame", "可直接玩的原生 20×20 贪吃蛇，内置开始/暂停、方向键、分数、重开、全屏和手动成绩点评。"),
)
