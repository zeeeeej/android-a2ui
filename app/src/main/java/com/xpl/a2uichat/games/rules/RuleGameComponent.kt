package com.xpl.a2uichat.games.rules

import androidx.a2ui.compose.runtime.A2uiComponentProperties
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.runtime.A2uiProperty
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xpl.a2uichat.a2ui.toJsonValue

object RuleGameComponent : A2uiComponent {
    override val name = "RuleGame"
    override val description = "运行受限 JSON 网格游戏规则的原生 Compose 组件，可生成未预置的落子、连线、下落、消行游戏。spec 格式见系统提示。"
    private val gameId = A2uiProperty.string("gameId", required = true, description = "必须与组件 id 相同")
    private val title = A2uiProperty.string("title", description = "游戏标题，1～80 字")
    private val spec = A2uiProperty.any("spec", required = true, description = "RuleGameSpec version=1 规则对象，不是字符串；禁止可执行源码")
    override val properties = listOf(gameId, title, spec)
    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        RuleGame(properties[gameId]!!, properties[title] ?: "规则小游戏", properties[spec].toJsonValue().obj(), modifier) { name, context ->
            dispatchAction(mapOf("event" to mapOf("name" to name, "context" to context)))
        }
    }
}
