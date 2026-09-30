package com.xpl.a2uichat.a2ui

import com.xpl.a2uichat.network.AgentException
import kotlinx.serialization.json.*

/** Bounded static component trees keep model output inside the app's supported catalog. */
class SurfaceGuard(private val catalogId: String = CHAT_CATALOG_ID) {
    private val surfaces = linkedMapOf<String, LinkedHashMap<String, JsonObject>>()
    val ids: Set<String> get() = surfaces.keys.toSet()

    fun copy(): SurfaceGuard = SurfaceGuard(catalogId).also { guard ->
        surfaces.forEach { (id, components) -> guard.surfaces[id] = LinkedHashMap(components) }
    }

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
                    forbidUnsupported(component)
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

    private fun requireUi(condition: Boolean) { if (!condition) invalid() }
    private fun invalid(): Nothing = throw AgentException("卡片结构无效，请重试或要求生成更简单的卡片。")

    companion object {
        val COMPONENTS = setOf("Text", "Row", "Column", "Card", "List", "Button", "TextField", "CheckBox", "Divider")
    }
}
