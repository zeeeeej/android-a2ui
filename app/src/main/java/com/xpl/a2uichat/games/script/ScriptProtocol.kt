package com.xpl.a2uichat.games.script

import com.xpl.a2uichat.games.rules.RuleFault
import kotlinx.serialization.json.*

internal fun checkScript(ok: Boolean, message: String) { if (!ok) throw RuleFault("ScriptGame: $message") }
private val codec = Json { isLenient = false }
internal fun JsonObject.number(key: String, default: Int = 0) = (get(key) as? JsonPrimitive)?.intOrNull ?: default
internal fun JsonObject.word(key: String, default: String = "") = (get(key) as? JsonPrimitive)?.contentOrNull ?: default

data class ScriptProgram(val json: JsonObject, val source: String, val config: JsonObject, val botPlayer: Int?, val tickMs: Int) {
    val version get() = json.number("version")
    companion object {
        fun parse(value: JsonObject): ScriptProgram {
            checkScript(value.toString().toByteArray().size <= 98304, "规则包超过 96 KiB")
            checkScript(value.number("version") in 1..2, "version 必须为 1 或 2")
            if (value.number("version") == 2) validateV2Program(value)
            checkScript((value["source"] as? JsonPrimitive)?.isString == true, "source 必须是字符串")
            val source = value.word("source")
            checkScript(source.isNotBlank() && source.toByteArray().size <= 65536, "source 必须为不超过 64 KiB 的 JS 表达式")
            checkScript("config" !in value || value["config"] is JsonObject, "config 必须是对象")
            val config = value["config"] as? JsonObject ?: JsonObject(emptyMap())
            boundedJson(config, 8192)
            val bot = if (value.number("version") == 2) value["players"]!!.jsonArray.map { it.jsonObject }
                .firstOrNull { it.word("controller") == "bot" }?.number("id") else value.number("botPlayer").takeIf { it != 0 }
            checkScript("botPlayer" !in value || (value["botPlayer"] as? JsonPrimitive)?.intOrNull in 1..2, "botPlayer 必须为 1 或 2")
            checkScript(bot == null || bot in 1..2, "botPlayer 必须为 1 或 2")
            val tick = value.number("tickMs")
            checkScript("tickMs" !in value || (value["tickMs"] as? JsonPrimitive)?.intOrNull != null, "tickMs 必须为整数")
            checkScript(tick == 0 || tick in 100..2000, "tickMs 必须为 0 或 100..2000")
            checkScript(bot == null || tick == 0, "回合制电脑不能与 tick 同时启用")
            checkScript("tests" !in value || value["tests"] is JsonArray, "tests 必须为数据数组")
            val tests = value["tests"] as? JsonArray ?: JsonArray(emptyList())
            checkScript(tests.size <= 16 && tests.all { it is JsonObject && (it["actions"] as? JsonArray)?.size in 0..64 }, "tests 最多 16 个，每个最多 64 步")
            if (value.number("version") == 2) tests.forEachIndexed { index, item ->
                val test = item.jsonObject
                checkScript("absentActions" !in test,"tests[$index].absentActions 放错层级：必须放入 tests[$index].expect.absentActions，不能修改规则来允许禁手")
                checkScript(test.keys.all { it in setOf("seed","state","actions","expect") },"tests[$index] 含未知字段，只能 seed/state/actions/expect")
                checkScript(test["expect"] is JsonObject && test["expect"]!!.jsonObject.isNotEmpty(),"tests[$index] 需要非空 expect 断言")
                checkScript("state" !in test || test["state"] is JsonObject,"tests[$index].state 必须为对象")
                checkScript("seed" !in test || (test["seed"] as? JsonPrimitive)?.intOrNull != null,"tests[$index].seed 必须为整数")
            }
            return ScriptProgram(value, source, config, bot, tick)
        }
    }
}

data class ScriptButton(val label: String, val action: JsonObject)
data class ScriptBinding(val action: JsonObject, val clicks: List<Pair<Int, Int>>)
data class ScriptView(val width: Int, val height: Int, val cells: List<Int>, val palette: List<String>,
    val labels: List<String>, val marks: List<Int>, val intersections: Boolean, val status: String,
    val phase: String, val turn: Int, val winner: Int?, val score: Double, val buttons: List<ScriptButton>, val tapType: String,
    val autoGrid: Boolean = true, val drawings: List<JsonObject> = emptyList(),
    val textColors: List<String> = emptyList(), val bindings: List<ScriptBinding> = emptyList(), val version: Int = 1)
data class ScriptSnapshot(val state: JsonObject, val view: ScriptView, val actions: List<JsonObject>, val accepted: Boolean) {
    companion object {
        fun parse(bytes: ByteArray, version: Int = 1): ScriptSnapshot {
            checkScript(bytes.size <= 262144, "回复超过 256 KiB")
            val obj = codec.parseToJsonElement(bytes.decodeToString()) as? JsonObject ?: throw RuleFault("ScriptGame 回复不是对象")
            checkScript(obj["ok"] == JsonPrimitive(true), obj.word("error", "隔离进程执行失败").take(300))
            boundedJson(obj, 262144)
            val state = obj["state"] as? JsonObject ?: throw RuleFault("ScriptGame 缺少 state")
            boundedJson(state, 131072)
            val v = obj["view"] as? JsonObject ?: throw RuleFault("ScriptGame 缺少 view")
            val w = v.number("width"); val h = v.number("height")
            checkScript(w in 2..20 && h in 2..20, "棋盘尺寸必须为 2..20")
            val palette = (v["palette"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: listOf("#DDBB77", "#222222", "#FFFFFF", "#66AA88")
            checkScript(palette.size in 3..16 && palette.all { it.matches(Regex("#[0-9a-fA-F]{6}")) }, "palette 需要 3..16 个 #RRGGBB")
            val cells = (v["cells"] as? JsonArray)?.map { it.jsonPrimitive.int } ?: emptyList()
            checkScript(cells.size == w*h && cells.all { it in palette.indices }, "cells 长度或颜色索引错误")
            val labels = (v["labels"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: List(w*h) { "" }
            val marks = (v["marks"] as? JsonArray)?.map { it.jsonPrimitive.int } ?: List(w*h) { 0 }
            checkScript(labels.size == w*h && labels.all { it.length <= 8 } && marks.size == w*h && marks.all { it in 0..2 }, "labels/marks 长度或值错误")
            val phase = v.word("phase", "playing")
            val turn = v.number("turn", 1)
            val winner = (v["winner"] as? JsonPrimitive)?.intOrNull
            checkScript(phase in setOf("playing", "scoring", "finished") && turn in 1..2 && (winner == null || winner in 0..2), "phase/turn/winner 错误")
            val buttons = (v["buttons"] as? JsonArray)?.map {
                val b = it.jsonObject; val label = b.word("label")
                checkScript(label.length in 1..20, "按钮标签过长")
                ScriptButton(label, action(b["action"]!!))
            }.orEmpty()
            checkScript(buttons.size <= 8, "最多 8 个按钮")
            val actions = (obj["actions"] as? JsonArray)?.map(::action) ?: throw RuleFault("ScriptGame 缺少合法动作")
            checkScript(actions.size <= 512 && actions.distinct().size == actions.size, "合法动作最多 512 个且不能重复")
            checkScript(if (phase == "finished") actions.isEmpty() && winner != null else actions.isNotEmpty(), "未结束状态必须有合法动作；结束状态必须有 winner 且没有动作")
            checkScript(phase != "scoring" || buttons.isNotEmpty() && buttons.all { it.action in actions }, "计分按钮必须有对应合法动作")
            val tap = v.word("tapType", "tap")
            val bindings = if (version == 2) validateV2View(v, actions, buttons, w, h) else emptyList()
            checkScript(tap.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}")), "tapType 错误")
            checkScript(actions.all { a -> a.word("type") == "tick" ||
                (version == 1 && a.word("type") == tap && a.keys == setOf("type","x","y") && a.number("x",-1) in 0 until w && a.number("y",-1) in 0 until h) ||
                bindings.any { it.action == a } ||
                buttons.any { it.action == a } }, if (version == 2) "双方完整合法动作都必须有 bindings 或按钮，电脑回合也不能省略；宿主会禁用人工操作" else "每个合法动作都必须可操作：格子点击需 x/y，其他动作必须在 view.buttons 中提供按钮")
            return ScriptSnapshot(state, ScriptView(w,h,cells,palette,labels,marks,v.word("style") == "intersections",
                v.word("status").take(160), phase,turn,winner,(v["score"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,buttons,tap,
                (v["autoGrid"] as? JsonPrimitive)?.booleanOrNull ?: true,
                if (version == 2) (v["drawings"] as? JsonArray)?.map { it.jsonObject }.orEmpty() else emptyList(),
                if (version == 2) v["textColors"]!!.jsonArray.map { it.jsonPrimitive.content } else emptyList(), bindings, version),
                actions,obj["accepted"] == JsonPrimitive(true))
        }
        private fun action(value: JsonElement): JsonObject {
            val a = value as? JsonObject ?: throw RuleFault("ScriptGame action 需要对象")
            boundedJson(a, 512)
            checkScript(a.word("type").matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}")), "action.type 错误")
            for (key in listOf("x", "y")) if (key in a) checkScript((a[key] as? JsonPrimitive)?.isString == false && a.number(key, -1) in 0..19, "action 坐标必须是 0..19 的整数")
            return a
        }
    }
}

internal fun boundedJson(value: JsonElement, bytes: Int) {
    var count = 0
    fun visit(v: JsonElement, depth: Int) {
        checkScript(depth <= 16 && ++count <= 20000, "JSON 嵌套或节点超限")
        when (v) {
            is JsonObject -> v.forEach { (key, item) -> checkScript(key.length <= 128, "JSON key 过长"); visit(item, depth+1) }
            is JsonArray -> v.forEach { visit(it, depth+1) }
            is JsonPrimitive -> if (!v.isString && v != JsonNull && v.booleanOrNull == null) {
                val n = v.doubleOrNull; checkScript(n != null && n.isFinite() && kotlin.math.abs(n) <= 1e12, "JSON 数值无效")
            }
        }
    }
    visit(value,0)
    checkScript(value.toString().toByteArray().size <= bytes, "JSON 大小超限")
}
