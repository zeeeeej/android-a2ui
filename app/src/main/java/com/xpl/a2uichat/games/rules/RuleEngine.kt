package com.xpl.a2uichat.games.rules

import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class RuleState(
    val cells: List<Int>, val vars: Map<String, JsonElement>, val turn: Int = 1,
    val winner: Int? = null, val moves: Int = 0, val random: Long = 1L,
)
data class RuleEvent(val name: String, val x: Int = 0, val y: Int = 0)
data class RuleTransition(val state: RuleState, val accepted: Boolean)

/** Every AST node and every cell visited by a bulk primitive is charged. */
class RuleBudget(private var remaining: Int = 20_000, private val deadline: Long = Long.MAX_VALUE,
    private val parent: RuleBudget? = null, private val cancelled: () -> Boolean = { false }) {
    fun charge(n: Int = 1) {
        remaining -= n
        if (remaining < 0 || cancelled() || System.nanoTime() > deadline) throw RuleBudgetFault()
        parent?.charge(n)
    }
}

/** No IO, reflection, generated source, unbounded loop or game-name switch. */
class RuleEngine(val spec: RuleSpec) {
    fun initial(seed: Long = System.nanoTime()): RuleState {
        val state = RuleState(List(spec.width * spec.height) { 0 }, spec.initial, random = seed)
        val result = transaction(state, RuleEvent("init"), RuleBudget())
        ruleCheck(result.accepted, "init 规则拒绝初始化")
        ruleCheck(result.state.winner == null, "init 后游戏已经结束，请修正规则")
        validateState(result.state)
        return result.state.copy(moves = 0)
    }

    fun event(state: RuleState, event: RuleEvent, budget: RuleBudget = RuleBudget()): RuleTransition {
        if (state.winner != null || event.name !in spec.handlers) return RuleTransition(state, false)
        return transaction(state, event, budget)
    }

    fun evaluate(state: RuleState, expression: JsonElement, budget: RuleBudget = RuleBudget()): JsonElement =
        Machine(state, RuleEvent("evaluate"), budget).eval(expression)

    private fun transaction(state: RuleState, event: RuleEvent, budget: RuleBudget): RuleTransition {
        val machine = Machine(state, event, budget)
        return try {
            machine.run(event.name)
            val next = machine.state().copy(moves = state.moves + if (event.name == "init") 0 else 1)
            validateState(next)
            RuleTransition(next, true)
        } catch (_: IllegalMove) { RuleTransition(state, false) }
        catch (fault: RuleFault) { throw fault }
        catch (_: ArithmeticException) { throw RuleFault("整数运算溢出或除零") }
    }

    internal fun validateState(state: RuleState) {
        fun type(v: JsonElement): String = when {
            v is JsonArray -> "array"
            v is JsonPrimitive && v.isString -> "string"
            v is JsonPrimitive && v.booleanOrNull != null -> "boolean"
            else -> "integer"
        }
        var nodes = 0
        var bytes = 0
        fun data(v: JsonElement, depth: Int = 0) {
            ruleCheck(++nodes <= 4096, "状态超过 4096 个值")
            bytes += if (v is JsonPrimitive) v.toString().toByteArray(Charsets.UTF_8).size + 1 else 2
            ruleCheck(bytes <= 65_536, "状态数据过大")
            ruleCheck(depth <= 6, "状态嵌套超过 6 层")
            if (v is JsonArray) { ruleCheck(v.size <= 400, "状态数组超过 400 项"); v.forEach { data(it, depth + 1) } }
            else ruleCheck(v is JsonPrimitive && v != JsonNull &&
                (if (v.isString) v.content.length <= 120 else v.intOrNull != null || v.booleanOrNull != null), "状态值无效")
        }
        ruleCheck(state.vars.keys == spec.initial.keys, "状态字段不能增删")
        state.vars.forEach { (key, value) ->
            data(value); ruleCheck(type(value) == type(spec.initial.getValue(key)), "状态变量 $key 类型改变")
        }
        ruleCheck(state.cells.size == spec.width * spec.height && state.cells.all { it in spec.palette.indices }, "棋盘值超出 palette")
        spec.overlay?.let { overlay ->
            points(state.vars.getValue(overlay.shape)).forEach { (x, y) ->
                ruleCheck(abs(x.toLong()) <= 20 && abs(y.toLong()) <= 20, "图形坐标超过范围")
            }
            ruleCheck(abs(state.vars.getValue(overlay.x).integer().toLong()) <= 40 &&
                abs(state.vars.getValue(overlay.y).integer().toLong()) <= 40, "图形位置超出范围")
        }
    }

    private inner class Machine(input: RuleState, val event: RuleEvent, val budget: RuleBudget) {
        val cells = input.cells.toMutableList()
        val vars = input.vars.toMutableMap()
        val locals = mutableMapOf<String, JsonElement>()
        var turn = input.turn
        var winner = input.winner
        var random = input.random
        fun state() = RuleState(cells.toList(), vars.toMap(), turn, winner, random = random)
        fun cell(x: Int, y: Int): Int {
            budget.charge()
            return if (x in 0 until spec.width && y in 0 until spec.height) cells[y * spec.width + x] else -1
        }
        fun write(x: Int, y: Int, value: Int) {
            budget.charge()
            ruleCheck(x in 0 until spec.width && y in 0 until spec.height && value in spec.palette.indices, "gridSet 坐标或颜色越界")
            cells[y * spec.width + x] = value
        }
        fun chargeValue(value: JsonElement) {
            budget.charge()
            if (value is JsonArray) value.forEach(::chargeValue)
        }
        fun eval(node: JsonElement): JsonElement {
            budget.charge()
            if (node is JsonPrimitive) return node
            if (node is JsonArray) return JsonArray(node.map(::eval)).also(::chargeValue)
            val obj = node.obj()
            obj["var"]?.let {
                val name = it.string()
                return locals[name] ?: vars[name] ?: JsonPrimitive(when (name) {
                    "width" -> spec.width; "height" -> spec.height; "turn" -> turn
                    "winner" -> winner ?: -1; "me" -> spec.botPlayer ?: 1
                    else -> throw RuleFault("未知变量：$name")
                })
            }
            obj["event"]?.let { return JsonPrimitive(if (it.string() == "x") event.x else event.y) }
            val op = obj.getValue("op").string()
            val args = obj.getValue("args").array()
            fun arg(i: Int) = eval(args[i])
            fun number(i: Int) = arg(i).integer()
            fun flag(i: Int) = arg(i).bool()
            fun safe(value: Long): JsonPrimitive {
                ruleCheck(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), "整数运算溢出")
                return JsonPrimitive(value.toInt())
            }
            return when (op) {
                "if" -> if (flag(0)) arg(1) else arg(2)
                "and" -> JsonPrimitive(flag(0) && flag(1))
                "or" -> JsonPrimitive(flag(0) || flag(1))
                "not" -> JsonPrimitive(!flag(0))
                "eq", "ne" -> {
                    val a = arg(0); val b = arg(1); chargeValue(a); chargeValue(b)
                    JsonPrimitive(if (op == "eq") a == b else a != b)
                }
                "lt" -> JsonPrimitive(number(0) < number(1))
                "le" -> JsonPrimitive(number(0) <= number(1))
                "gt" -> JsonPrimitive(number(0) > number(1))
                "ge" -> JsonPrimitive(number(0) >= number(1))
                "add" -> safe(number(0).toLong() + number(1))
                "sub" -> safe(number(0).toLong() - number(1))
                "mul" -> safe(number(0).toLong() * number(1))
                "div", "mod" -> {
                    val a = number(0).toLong(); val b = number(1).toLong()
                    ruleCheck(b != 0L, "不能除以零")
                    safe(if (op == "div") a / b else a % b)
                }
                "min" -> JsonPrimitive(min(number(0), number(1)))
                "max" -> JsonPrimitive(max(number(0), number(1)))
                "abs" -> safe(abs(number(0).toLong()))
                "len" -> JsonPrimitive(arg(0).array().size)
                "get" -> {
                    val list = arg(0).array(); val index = number(1)
                    list.getOrNull(index) ?: throw RuleFault("get 索引越界")
                }
                "range" -> {
                    val count = number(0); ruleCheck(count in 0..400, "range 长度必须为 0～400")
                    budget.charge(count); JsonArray(List(count) { JsonPrimitive(it) })
                }
                "random" -> {
                    val bound = number(0); ruleCheck(bound in 1..400, "random 上界必须为 1～400")
                    random = random * 6364136223846793005L + 1442695040888963407L
                    JsonPrimitive(((random ushr 1) % bound).toInt())
                }
                "cell" -> JsonPrimitive(cell(number(0), number(1)))
                "count" -> { val value = number(0); budget.charge(cells.size); JsonPrimitive(cells.count { it == value }) }
                "fits" -> {
                    val shape = points(arg(0)); val x = number(1); val y = number(2)
                    budget.charge(shape.size)
                    JsonPrimitive(shape.all { (px, py) -> cell(Math.addExact(x, px), Math.addExact(y, py)) == 0 })
                }
                "rotate", "translate" -> {
                    val shape = points(arg(0)); budget.charge(shape.size)
                    val result = if (op == "rotate") {
                        val maxY = shape.maxOfOrNull { it.second } ?: 0
                        shape.map { (x, y) -> Math.subtractExact(maxY, y) to x }
                    } else {
                        val dx = number(1); val dy = number(2)
                        shape.map { (x, y) -> Math.addExact(x, dx) to Math.addExact(y, dy) }
                    }
                    JsonArray(result.map { (x, y) -> JsonArray(listOf(JsonPrimitive(x), JsonPrimitive(y))) })
                }
                "line" -> {
                    val x = number(0); val y = number(1); val color = number(2)
                    if (color <= 0 || cell(x, y) != color) JsonPrimitive(0) else {
                        JsonPrimitive(directions.maxOf { (dx, dy) ->
                            var length = 1
                            for (sign in listOf(-1, 1)) {
                                var distance = 1
                                while (cell(x + dx * distance * sign, y + dy * distance * sign) == color) { length++; distance++ }
                            }
                            length
                        })
                    }
                }
                "fullRows" -> JsonArray(fullRows().map(::JsonPrimitive))
                "potential" -> {
                    val player = number(0); val target = number(1)
                    ruleCheck(player in 1..spec.players && target in 2..20, "potential 玩家或连线长度无效")
                    var score = 0L
                    for (y in 0 until spec.height) for (x in 0 until spec.width) for ((dx, dy) in directions) {
                        val ex = x + (target - 1) * dx; val ey = y + (target - 1) * dy
                        budget.charge()
                        if (ex !in 0 until spec.width || ey !in 0 until spec.height) continue
                        var stones = 0; var blocked = false
                        for (i in 0 until target) {
                            val value = cell(x + i * dx, y + i * dy)
                            if (value == player) stones++ else if (value != 0) { blocked = true; break }
                        }
                        if (!blocked && stones > 0) {
                            var weight = 1L
                            repeat(stones.coerceAtMost(7)) { weight *= 8 }
                            score += weight
                        }
                    }
                    JsonPrimitive(score.coerceAtMost(100_000_000).toInt())
                }
                else -> throw RuleFault("不支持操作：$op")
            }
        }
        fun fullRows(): List<Int> = (0 until spec.height).filter { y -> (0 until spec.width).all { x -> cell(x, y) > 0 } }
        fun run(name: String) { spec.handlers[name]?.let(::block) }
        fun block(statements: JsonArray) {
            for (entry in statements) {
                budget.charge()
                if (winner != null) break
                val node = entry.obj()
                when {
                    "set" in node -> {
                        val value = eval(node.getValue("value")); chargeValue(value)
                        vars[node.getValue("set").string()] = value
                    }
                    "if" in node -> {
                        val branch = if (eval(node.getValue("if")).bool()) node["then"] else node["else"]
                        branch?.let { block(it.array()) }
                    }
                    "for" in node -> {
                        val name = node.getValue("for").string()
                        val items = eval(node.getValue("in")).array()
                        ruleCheck(items.size <= 400, "循环超过 400 项")
                        for (item in items) { budget.charge(); locals[name] = item; block(node.getValue("do").array()) }
                        locals.remove(name)
                    }
                    "assert" in node -> if (!eval(node.getValue("assert")).bool()) throw IllegalMove()
                    "gridSet" in node -> {
                        val args = node.getValue("gridSet").array().map(::eval)
                        write(args[0].integer(), args[1].integer(), args[2].integer())
                    }
                    "stamp" in node -> {
                        val args = node.getValue("stamp").array().map(::eval)
                        val x = args[1].integer(); val y = args[2].integer(); val value = args[3].integer()
                        val shape = points(args[0]); budget.charge(shape.size)
                        shape.forEach { (px, py) -> write(Math.addExact(x, px), Math.addExact(y, py), value) }
                    }
                    "compact" in node -> {
                        val full = fullRows().toSet()
                        val remaining = (0 until spec.height).filterNot { it in full }.flatMap { y -> cells.subList(y * spec.width, (y + 1) * spec.width).toList() }
                        budget.charge(cells.size)
                        val result = List(full.size * spec.width) { 0 } + remaining
                        result.forEachIndexed { i, value -> cells[i] = value }
                    }
                    "next" in node -> turn = turn % spec.players + 1
                    "finish" in node -> {
                        val value = eval(node.getValue("finish")).integer()
                        ruleCheck(value in 0..spec.players, "finish 必须为 0（平局）或玩家编号")
                        winner = value
                    }
                    "emit" in node -> run(node.getValue("emit").string())
                    else -> throw RuleFault("未知指令")
                }
            }
        }
    }

    companion object {
        val directions = listOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
        fun points(value: JsonElement): List<Pair<Int, Int>> {
            val list = value.array()
            ruleCheck(list.size <= 400, "图形最多 400 格")
            return list.map { item ->
                val pair = item.array(); ruleCheck(pair.size == 2, "图形坐标必须为 [x,y]")
                pair[0].integer() to pair[1].integer()
            }
        }
    }
}
