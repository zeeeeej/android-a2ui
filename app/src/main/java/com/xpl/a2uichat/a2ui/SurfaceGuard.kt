package com.xpl.a2uichat.a2ui

import com.xpl.a2uichat.network.AgentException
import com.xpl.a2uichat.games.GameKey
import com.xpl.a2uichat.games.rules.RuleSpec
import com.xpl.a2uichat.games.rules.RuleFault
import kotlinx.serialization.json.*

/** Bounded static component trees keep model output inside the app's supported catalog. */
class SurfaceGuard(private val catalogId: String = CHAT_CATALOG_ID) {
    private val surfaces = linkedMapOf<String, LinkedHashMap<String, JsonObject>>()
    val ids: Set<String> get() = surfaces.keys.toSet()

    /** Only reachable game nodes own live state; detached/replaced nodes are discarded. */
    fun gameKeys(): Set<GameKey> = buildSet {
        surfaces.forEach { (surfaceId, tree) ->
            val visited = mutableSetOf<String>()
            fun visit(id: String) {
                if (!visited.add(id)) return
                val node = tree[id] ?: return
                val type = node["component"]?.jsonPrimitive?.content
                if (type in GAME_COMPONENTS) add(GameKey(surfaceId, id, type!!))
                (node["children"] as? JsonArray)?.forEach { visit(it.jsonPrimitive.content) }
                node["child"]?.jsonPrimitive?.content?.let(::visit)
            }
            visit("root")
        }
    }

    fun copy(): SurfaceGuard = SurfaceGuard(catalogId).also { guard ->
        surfaces.forEach { (id, components) -> guard.surfaces[id] = LinkedHashMap(components) }
    }

    fun scriptPrograms(surfaceIds: Set<String>): List<com.xpl.a2uichat.games.script.ScriptProgram> =
        gameKeys().filter { it.type == "ScriptGame" && it.surfaceId in surfaceIds }.map {
            com.xpl.a2uichat.games.script.ScriptProgram.parse(surfaces.getValue(it.surfaceId).getValue(it.gameId).getValue("spec").jsonObject)
        }.distinctBy { it.json }.also { if (it.size > 3) throw RuleFault("每轮最多校验 3 个 ScriptGame") }

    fun accept(message: JsonObject, newPrefix: String): String {
        requireUi(message["version"]?.jsonPrimitive?.content in setOf("v0.9", "v0.9.1"))
        val kinds = message.keys.filter { it != "version" }
        requireUi(kinds.size == 1)
        val kind = kinds.single()
        val body = message[kind] as? JsonObject ?: invalid()
        val id = (body["surfaceId"] as? JsonPrimitive)?.contentOrNull ?: invalid()
        requireUi(id.length in 1..100)
        when (kind) {
            "createSurface" -> {
                requireUi(id.startsWith(newPrefix) && id !in surfaces)
                requireUi(surfaces.keys.count { it.startsWith(newPrefix) } < 3)
                requireUi(body["catalogId"]?.jsonPrimitive?.content == catalogId)
                requireUi(body["sendDataModel"]?.jsonPrimitive?.booleanOrNull == true)
                surfaces[id] = linkedMapOf()
            }
            "updateComponents" -> {
                val current = surfaces[id] ?: invalid()
                val components = body["components"] as? JsonArray ?: invalid()
                requireUi(components.size <= 80)
                components.forEach { value ->
                    val component = value as? JsonObject ?: invalid()
                    val componentId = component["id"]?.jsonPrimitive?.contentOrNull ?: invalid()
                    requireUi(componentId.length in 1..100)
                    requireUi(component["component"]?.jsonPrimitive?.content in COMPONENTS)
                    try {
                        validateGame(component, componentId)
                        forbidUnsupported(component)
                    } catch (e: AgentException) {
                        if (component["component"]?.jsonPrimitive?.content == "ScriptGame")
                            throw RuleFault("ScriptGame 组件 $componentId 元数据错误：gameId 必须与 id 相同，title<=80字，无 difficulty/children/child/call/checks/validationRegexp")
                        throw e
                    }
                    current[componentId] = component
                }
                requireUi(current.size <= 80)
                validateTree(current, complete = false)
            }
            "updateDataModel" -> {
                requireUi(id in surfaces)
                val path = body["path"]?.jsonPrimitive?.contentOrNull ?: "/"
                requireUi(path.startsWith("/") && path.length < 256)
            }
            "deleteSurface" -> { requireUi(id in surfaces); surfaces.remove(id) }
            else -> invalid()
        }
        return id
    }

    fun validateComplete() {
        surfaces.values.forEach { tree ->
            requireUi("root" in tree)
            validateTree(tree, complete = true)
            // A shared ancestor would render the same game twice (and could start two clocks).
            val incoming = mutableMapOf("root" to 1)
            val hasGame = mutableMapOf<String, Boolean>()
            fun children(node: JsonObject): List<String> =
                (node["children"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty() +
                    listOfNotNull(node["child"]?.jsonPrimitive?.content)
            fun containsGame(id: String): Boolean = hasGame.getOrPut(id) {
                val node = tree.getValue(id)
                node["component"]?.jsonPrimitive?.content in GAME_COMPONENTS || children(node).any(::containsGame)
            }
            tree.values.forEach { node -> children(node).forEach { incoming[it] = (incoming[it] ?: 0) + 1 } }
            tree.keys.forEach { id -> if (containsGame(id)) requireUi((incoming[id] ?: 0) <= 1) }
        }
    }

    fun snapshotMessages(data: Map<String, JsonElement>): List<JsonObject> = surfaces.flatMap { (id, tree) ->
        listOf(
            buildJsonObject { put("version", "v0.9"); put("createSurface", buildJsonObject {
                put("surfaceId", id); put("catalogId", catalogId); put("sendDataModel", true)
            }) },
            buildJsonObject { put("version", "v0.9"); put("updateDataModel", buildJsonObject {
                put("surfaceId", id); put("path", "/"); put("value", data[id] ?: JsonObject(emptyMap()))
            }) },
            buildJsonObject { put("version", "v0.9"); put("updateComponents", buildJsonObject {
                put("surfaceId", id); put("components", JsonArray(tree.values.toList()))
            }) },
        )
    }

    private fun validateTree(tree: Map<String, JsonObject>, complete: Boolean) {
        val depths = mutableMapOf<String, Int>()
        val visiting = mutableSetOf<String>()
        fun depth(id: String): Int {
            depths[id]?.let { return it }
            requireUi(visiting.add(id))
            val component = tree[id]
            if (component == null) { requireUi(!complete); visiting.remove(id); return 0 }
            val children = component["children"]
            requireUi(children == null || children is JsonArray)
            val refs = (children as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: invalid() }.orEmpty() +
                listOfNotNull(component["child"]?.jsonPrimitive?.contentOrNull)
            val depth = 1 + (refs.maxOfOrNull { depth(it) } ?: 0)
            requireUi(depth <= 12)
            visiting.remove(id)
            depths[id] = depth
            return depth
        }
        tree.keys.forEach { depth(it) }
    }

    private fun forbidUnsupported(value: JsonElement) {
        when (value) {
            is JsonObject -> {
                requireUi(value.keys.none { it in setOf("call", "checks", "validationRegexp") })
                value.values.forEach(::forbidUnsupported)
            }
            is JsonArray -> value.forEach(::forbidUnsupported)
            else -> Unit
        }
    }

    private fun validateGame(component: JsonObject, id: String) {
        val type = component["component"]?.jsonPrimitive?.content
        if (type !in GAME_COMPONENTS) return
        val gameId = component["gameId"] as? JsonPrimitive
        requireUi(gameId?.isString == true && gameId.content == id)
        component["title"]?.let { value ->
            val title = value as? JsonPrimitive
            requireUi(title?.isString == true && title.content.length in 1..80)
        }
        component["difficulty"]?.let { value ->
            val difficulty = value as? JsonPrimitive
            requireUi(type == "SnakeGame" && difficulty?.isString == true && difficulty.content in setOf("easy", "normal", "hard"))
        }
        requireUi("children" !in component && "child" !in component)
        if (type == "RuleGame") {
            val spec = component["spec"] as? JsonObject ?: throw RuleFault("RuleGame 缺少 spec 对象")
            RuleSpec.parse(spec)
        }
        if (type == "ScriptGame") {
            val spec = component["spec"] as? JsonObject ?: throw RuleFault("ScriptGame 缺少 spec 对象")
            com.xpl.a2uichat.games.script.ScriptProgram.parse(spec)
        }
    }

    private fun requireUi(condition: Boolean) { if (!condition) invalid() }
    private fun invalid(): Nothing = throw AgentException("卡片结构无效，请重试或要求生成更简单的卡片。")

    companion object {
        val GAME_COMPONENTS = setOf("Game2048", "SnakeGame", "RuleGame", "ScriptGame")
        val COMPONENTS = setOf("Text", "Row", "Column", "Card", "List", "Button", "TextField", "CheckBox", "Divider") + GAME_COMPONENTS
    }
}
