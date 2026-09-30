package com.xpl.a2uichat.games.script

import androidx.a2ui.compose.runtime.*
import androidx.a2ui.compose.ui.A2uiComponent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xpl.a2uichat.a2ui.toJsonValue
import kotlinx.serialization.json.jsonObject

object ScriptGameComponent : A2uiComponent {
    override val name = "ScriptGame"
    override val description = "原生 Compose 棋盘；动态 JavaScript 规则仅在隔离进程执行。支持围棋、黑白棋、扫雷等，spec 接口见系统提示。"
    private val gameId = A2uiProperty.string("gameId", required = true, description = "与组件 id 相同")
    private val title = A2uiProperty.string("title", description = "游戏名称")
    private val spec = A2uiProperty.any("spec", required = true, description = "version=1、source JS 表达式、config、可选 botPlayer/tickMs/tests")
    override val properties = listOf(gameId,title,spec)
    @Composable
    override fun A2uiComponentScope.Content(properties: A2uiComponentProperties, modifier: Modifier) {
        ScriptGame(properties[gameId]!!,properties[title] ?: "动态小游戏",properties[spec].toJsonValue().jsonObject,modifier) { name,context ->
            dispatchAction(mapOf("event" to mapOf("name" to name,"context" to context)))
        }
    }
}
