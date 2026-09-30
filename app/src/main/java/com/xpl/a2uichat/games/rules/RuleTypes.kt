package com.xpl.a2uichat.games.rules

import kotlinx.serialization.json.*

/** Conservative static checking; heterogeneous/empty array elements are checked at runtime. */
internal object RuleTypes {
    private data class Type(val kind: String, val element: Type? = null)
    private val unknown = Type("unknown")
    private val number = Type("integer")
    private val boolean = Type("boolean")
    private val array = Type("array")
    fun validate(spec: RuleSpec) {
        fun literal(v: JsonElement): Type = when (v) {
            is JsonArray -> Type("array", v.map { literal(it) }.distinct().singleOrNull() ?: unknown)
            is JsonPrimitive -> if (v.isString) Type("string") else if (v.booleanOrNull != null) boolean else number
            else -> unknown
        }
        val vars = spec.initial.mapValues { literal(it.value) } +
            listOf("width", "height", "turn", "winner", "me").associateWith { number }
        fun expect(actual: Type, expected: Type) {
            ruleCheck(actual.kind == "unknown" || actual.kind == expected.kind, "类型错误：需要 ${expected.kind}，得到 ${actual.kind}")
        }
        fun expression(node: JsonElement, locals: Map<String, Type>): Type {
            if (node is JsonPrimitive) return literal(node)
            if (node is JsonArray) return Type("array", node.map { expression(it, locals) }.distinct().singleOrNull() ?: unknown)
            val obj = node.obj()
            obj["var"]?.let { return locals[it.string()] ?: vars[it.string()] ?: unknown }
            if ("event" in obj) return number
            val op = obj.getValue("op").string()
            val args = obj.getValue("args").array().map { expression(it, locals) }
            fun all(type: Type) { args.forEach { expect(it, type) } }
            return when (op) {
                "and", "or", "not" -> { all(boolean); boolean }
                "if" -> { expect(args[0], boolean); if (args[1].kind != "unknown" && args[2].kind != "unknown") expect(args[1], args[2]); args[1] }
                "eq", "ne" -> boolean
                "lt", "le", "gt", "ge" -> { all(number); boolean }
                "get" -> { expect(args[0], array); expect(args[1], number); args[0].element ?: unknown }
                "len" -> { expect(args[0], array); number }
                "rotate" -> { expect(args[0], array); array }
                "fits", "translate" -> {
                    expect(args[0], array); expect(args[1], number); expect(args[2], number)
                    if (op == "fits") boolean else array
                }
                "range", "fullRows" -> { all(number); Type("array", number) }
                else -> { all(number); number }
            }
        }
        fun block(nodes: JsonArray, locals: Map<String, Type>) {
            nodes.forEach { entry ->
                val node = entry.obj()
                when {
                    "set" in node -> expect(expression(node.getValue("value"), locals), vars.getValue(node.getValue("set").string()))
                    "if" in node -> {
                        expect(expression(node.getValue("if"), locals), boolean)
                        block(node.getValue("then").array(), locals); node["else"]?.let { block(it.array(), locals) }
                    }
                    "for" in node -> {
                        val type = expression(node.getValue("in"), locals); expect(type, array)
                        block(node.getValue("do").array(), locals + (node.getValue("for").string() to (type.element ?: unknown)))
                    }
                    "assert" in node -> expect(expression(node.getValue("assert"), locals), boolean)
                    "finish" in node -> expect(expression(node.getValue("finish"), locals), number)
                    "gridSet" in node -> node.getValue("gridSet").array().forEach { expect(expression(it, locals), number) }
                    "stamp" in node -> node.getValue("stamp").array().forEachIndexed { i, value -> expect(expression(value, locals), if (i == 0) array else number) }
                }
            }
        }
        spec.handlers.values.forEach { block(it, emptyMap()) }
        spec.evaluation?.let { expect(expression(it, emptyMap()), number) }
    }
}
