package com.xpl.a2uichat.games.rules

import kotlinx.serialization.json.*

open class RuleFault(message: String) : IllegalArgumentException(message)
internal class RuleBudgetFault : RuleFault("规则计算超出运行预算，已暂停")
internal class IllegalMove : RuntimeException()
internal fun ruleCheck(ok: Boolean, message: String) { if (!ok) throw RuleFault(message) }
internal fun JsonElement.obj() = this as? JsonObject ?: throw RuleFault("需要 JSON 对象")
internal fun JsonElement.array() = this as? JsonArray ?: throw RuleFault("需要 JSON 数组")
internal fun JsonElement.integer(): Int {
    val p = this as? JsonPrimitive
    return p?.takeUnless { it.isString }?.intOrNull ?: throw RuleFault("需要整数")
}
internal fun JsonElement.string(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw RuleFault("需要字符串")
internal fun JsonElement.bool(): Boolean = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: throw RuleFault("需要布尔值")
internal fun JsonObject.int(key: String, default: Int) = get(key)?.integer() ?: default
internal fun JsonObject.str(key: String, default: String) = get(key)?.string() ?: default

data class RuleControl(val id: String, val label: String)
data class RuleOverlay(val shape: String, val x: String, val y: String, val color: Int)
data class RuleSpec(
    val source: JsonObject, val width: Int, val height: Int, val players: Int,
    val initial: Map<String, JsonElement>, val handlers: Map<String, JsonArray>,
    val controls: List<RuleControl>, val palette: List<String>, val tickMs: Long,
    val intersections: Boolean, val overlay: RuleOverlay?, val botPlayer: Int?, val evaluation: JsonElement?,
) {
    companion object {
        const val MAX_BYTES = 65_536
        private val namePattern = Regex("[a-zA-Z_][a-zA-Z0-9_]{0,31}")
        private val reserved = setOf("width", "height", "turn", "winner", "me")
        internal val arities = mapOf(
            "add" to 2..2, "sub" to 2..2, "mul" to 2..2, "div" to 2..2, "mod" to 2..2,
            "eq" to 2..2, "ne" to 2..2, "lt" to 2..2, "le" to 2..2, "gt" to 2..2, "ge" to 2..2,
            "and" to 2..2, "or" to 2..2, "not" to 1..1, "if" to 3..3,
            "min" to 2..2, "max" to 2..2, "abs" to 1..1, "len" to 1..1, "get" to 2..2,
            "range" to 1..1, "random" to 1..1, "cell" to 2..2, "count" to 1..1,
            "fits" to 3..3, "rotate" to 1..1, "translate" to 3..3,
            "line" to 3..3, "fullRows" to 0..0, "potential" to 2..2,
        )

        fun parse(source: JsonObject, probe: Boolean = true): RuleSpec {
            ruleCheck(source.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES, "规则超过 64 KiB")
            fun keys(node: JsonObject, allowed: Set<String>) = ruleCheck(node.keys.all { it in allowed }, "规则含未知字段：${(node.keys - allowed).firstOrNull()}")
            keys(source, setOf("version", "width", "height", "players", "initial", "handlers", "controls", "palette", "tickMs", "style", "overlay", "bot"))
            ruleCheck(source.int("version", 0) == 1, "只支持规则 version=1")
            val width = source.int("width", 0); val height = source.int("height", 0)
            ruleCheck(width in 2..20 && height in 2..20, "棋盘宽高必须为 2～20")
            val players = source.int("players", 1)
            ruleCheck(players in 1..2, "players 必须为 1 或 2")
            val initial = source["initial"]?.obj() ?: JsonObject(emptyMap())
            ruleCheck(initial.size <= 32 && initial.keys.all { namePattern.matches(it) && it !in reserved }, "状态变量名称无效或过多")
            fun data(value: JsonElement, depth: Int = 0) {
                ruleCheck(depth <= 6, "初始状态嵌套过深")
                when (value) {
                    is JsonArray -> { ruleCheck(value.size <= 400, "列表超过 400 项"); value.forEach { data(it, depth + 1) } }
                    is JsonPrimitive -> ruleCheck(value != JsonNull && (!value.isString || value.content.length <= 120) &&
                        (value.isString || value.booleanOrNull != null || value.intOrNull != null), "状态只允许整数、布尔、短字符串和数组")
                    else -> throw RuleFault("状态不能包含对象")
                }
            }
            initial.values.forEach { data(it) }
            val rawHandlers = source["handlers"]?.obj() ?: throw RuleFault("缺少 handlers")
            ruleCheck(rawHandlers.isNotEmpty() && rawHandlers.size <= 32 && rawHandlers.keys.all { namePattern.matches(it) && it != "__bot" }, "事件数量或名称无效")
            val handlers = rawHandlers.mapValues { it.value.array() }
            var nodes = 0
            fun expression(value: JsonElement, locals: Set<String>, depth: Int = 0, pure: Boolean = false) {
                ruleCheck(++nodes <= 4096 && depth <= 12, "规则节点超过 4096 或表达式深度超过 12")
                when (value) {
                    is JsonArray -> { ruleCheck(value.size <= 400, "列表超过 400 项"); value.forEach { expression(it, locals, depth + 1, pure) } }
                    is JsonPrimitive -> data(value)
                    is JsonObject -> when {
                        value.keys == setOf("var") -> ruleCheck(value.getValue("var").string() in initial.keys + reserved + locals, "未知状态变量")
                        value.keys == setOf("event") -> ruleCheck(!pure && value.getValue("event").string() in setOf("x", "y"), "事件仅支持 x/y，评分不能依赖事件")
                        else -> {
                            keys(value, setOf("op", "args"))
                            val op = value["op"]?.string() ?: throw RuleFault("表达式缺少 op")
                            val args = value["args"]?.array() ?: throw RuleFault("表达式缺少 args")
                            ruleCheck(args.size in (arities[op] ?: throw RuleFault("不支持操作：$op")), "$op 参数数量错误")
                            ruleCheck(!pure || op != "random", "评分不允许随机操作")
                            args.forEach { expression(it, locals, depth + 1, pure) }
                        }
                    }
                }
            }
            val emits = mutableMapOf<String, MutableSet<String>>()
            fun statements(block: JsonArray, locals: Set<String>, handler: String, depth: Int = 0) {
                ruleCheck(depth <= 12 && block.size <= 256, "指令嵌套或数量超限")
                block.forEach { entry ->
                    ruleCheck(++nodes <= 4096, "规则节点超过 4096")
                    val node = entry.obj()
                    when {
                        "set" in node -> {
                            keys(node, setOf("set", "value"))
                            ruleCheck(node.getValue("set").string() in initial, "set 只能写已声明状态")
                            expression(node["value"] ?: throw RuleFault("set 缺少 value"), locals)
                        }
                        "if" in node -> {
                            keys(node, setOf("if", "then", "else")); expression(node.getValue("if"), locals)
                            statements(node["then"]?.array() ?: throw RuleFault("if 缺少 then"), locals, handler, depth + 1)
                            node["else"]?.let { statements(it.array(), locals, handler, depth + 1) }
                        }
                        "for" in node -> {
                            keys(node, setOf("for", "in", "do"))
                            val name = node.getValue("for").string()
                            ruleCheck(namePattern.matches(name) && name !in initial.keys + reserved + locals, "循环变量名称冲突")
                            expression(node["in"] ?: throw RuleFault("for 缺少 in"), locals)
                            statements(node["do"]?.array() ?: throw RuleFault("for 缺少 do"), locals + name, handler, depth + 1)
                        }
                        else -> {
                            ruleCheck(node.size == 1, "指令必须只有一个动作")
                            val (op, value) = node.entries.single()
                            when (op) {
                                "assert", "finish" -> expression(value, locals)
                                "gridSet", "stamp" -> {
                                    val args = value.array()
                                    ruleCheck(args.size == if (op == "gridSet") 3 else 4, "$op 参数数量错误")
                                    args.forEach { expression(it, locals) }
                                }
                                "next", "compact" -> ruleCheck(value == JsonPrimitive(true), "$op 必须为 true")
                                "emit" -> {
                                    val target = value.string()
                                    ruleCheck(target in handlers, "未知事件：$target")
                                    emits.getOrPut(handler) { mutableSetOf() }.add(target)
                                }
                                else -> throw RuleFault("未知指令：$op")
                            }
                        }
                    }
                }
            }
            handlers.forEach { (name, block) -> statements(block, emptySet(), name) }
            val depths = mutableMapOf<String, Int>()
            fun visit(name: String, stack: Set<String>): Int {
                depths[name]?.let { return it }
                ruleCheck(name !in stack, "emit 禁止递归")
                val depth = 1 + (emits[name].orEmpty().maxOfOrNull { visit(it, stack + name) } ?: 0)
                ruleCheck(depth <= 8, "emit 调用深度最多 8")
                depths[name] = depth
                return depth
            }
            handlers.keys.forEach { visit(it, emptySet()) }
            val controls = source["controls"]?.array()?.map { entry ->
                val control = entry.obj(); keys(control, setOf("id", "label"))
                RuleControl(control["id"]?.string() ?: "", control["label"]?.string() ?: "")
            }.orEmpty()
            ruleCheck(controls.size <= 8 && controls.map { it.id }.distinct().size == controls.size &&
                controls.all { it.id in handlers && it.id !in setOf("init", "tick", "tap") && it.label.length in 1..12 }, "按钮配置无效")
            ruleCheck("tap" in handlers || controls.isNotEmpty(), "至少需要落子或按钮操作")
            val palette = source["palette"]?.array()?.map { it.string() }
                ?: listOf("#EEE8DD", "#222222", "#FFFFFF", "#008577", "#FFB547", "#8866BB", "#EE6666", "#4488CC")
            ruleCheck(palette.size in 3..16 && palette.all { Regex("#[0-9a-fA-F]{6}").matches(it) }, "palette 必须为 3～16 个 #RRGGBB 颜色")
            val tick = source.int("tickMs", 0)
            ruleCheck(tick == 0 || (tick in 100..2000 && "tick" in handlers), "tickMs 为 0 或 100～2000，非零时必须有 tick 事件")
            val style = source.str("style", "cells")
            ruleCheck(style in setOf("cells", "intersections"), "style 仅支持 cells/intersections")
            val overlay = source["overlay"]?.obj()?.let { node ->
                keys(node, setOf("shape", "x", "y", "color"))
                val result = RuleOverlay(node.str("shape", ""), node.str("x", ""), node.str("y", ""), node.int("color", 3))
                ruleCheck(listOf(result.shape, result.x, result.y).all { it in initial } && result.color in 1 until palette.size, "overlay 配置无效")
                result
            }
            val bot = source["bot"]?.obj()
            bot?.let {
                keys(it, setOf("player", "evaluate"))
                ruleCheck(players == 2 && it.int("player", 2) in 1..2 && "tap" in handlers && tick == 0, "电脑对手仅支持双人回合制 tap 游戏")
                expression(it["evaluate"] ?: throw RuleFault("bot 缺少 evaluate"), emptySet(), pure = true)
            }
            val spec = RuleSpec(source, width, height, players, initial, handlers, controls, palette, tick.toLong(),
                style == "intersections", overlay, bot?.int("player", 2), bot?.get("evaluate"))
            RuleTypes.validate(spec)
            if (probe) RuleEngine(spec).initial(1L).also { state ->
                spec.evaluation?.let { RuleEngine(spec).evaluate(state, it).integer() }
                val engine = RuleEngine(spec)
                val events = controls.map { RuleEvent(it.id) } + listOf(RuleEvent("tick"), RuleEvent("tap"),
                    RuleEvent("tap", width / 2, height / 2), RuleEvent("tap", width - 1, height - 1))
                events.forEach { engine.event(state, it) }
                if (spec.botPlayer != null) {
                    // Both players use tap. A human-only assertion otherwise makes every bot move illegal.
                    val botState = if (state.turn == spec.botPlayer) state else {
                        val human = events.filter { it.name == "tap" }.asSequence().map { engine.event(state, it) }
                            .firstOrNull { it.accepted && it.state.winner == null }
                            ?: throw RuleFault("启动探测未找到可继续的玩家落子")
                        ruleCheck(human.state.turn == spec.botPlayer, "tap 落子后未将回合交给电脑")
                        human.state
                    }
                    val move = RuleBot.choose(engine, botState)
                    ruleCheck(engine.event(botState, move).accepted, "电脑无法使用同一 tap 规则合法落子")
                }
            }
            return spec
        }
    }
}
