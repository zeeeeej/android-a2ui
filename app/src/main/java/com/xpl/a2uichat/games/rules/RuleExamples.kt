package com.xpl.a2uichat.games.rules

import kotlinx.serialization.json.*

/** Teaching/test data only. The runtime does not inspect game titles or select these programs. */
object RuleExamples {
    private fun value(v: Any?): JsonElement = when (v) {
        is JsonElement -> v
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map(::value))
        else -> error("Invalid example")
    }
    private fun obj(vararg fields: Pair<String, Any?>) = JsonObject(fields.associate { it.first to value(it.second) })
    private fun op(name: String, vararg args: Any?) = obj("op" to name, "args" to args.toList())
    private fun v(name: String) = obj("var" to name)
    private fun e(name: String) = obj("event" to name)
    private fun set(name: String, value: Any) = obj("set" to name, "value" to value)
    private fun iff(condition: Any, yes: List<JsonObject>, no: List<JsonObject> = emptyList()) = obj("if" to condition, "then" to yes, "else" to no)

    fun connection(width: Int = 15, height: Int = 15, target: Int = 5): JsonObject = obj(
        "version" to 1, "width" to width, "height" to height, "players" to 2, "style" to "intersections",
        "initial" to obj("target" to target, "score" to 0),
        "handlers" to obj("tap" to listOf(
            obj("assert" to op("eq", op("cell", e("x"), e("y")), 0)),
            obj("gridSet" to listOf(e("x"), e("y"), v("turn"))),
            set("score", op("add", v("score"), 1)),
            iff(op("ge", op("line", e("x"), e("y"), v("turn")), v("target")), listOf(obj("finish" to v("turn")))),
            iff(op("eq", op("count", 0), 0), listOf(obj("finish" to 0))),
            obj("next" to true),
        )),
        "bot" to obj("player" to 2, "evaluate" to op("sub", op("potential", v("me"), v("target")),
            op("mul", 2, op("potential", op("sub", 3, v("me")), v("target")))))
    )

    fun falling(width: Int = 10, height: Int = 20, pointsPerRow: Int = 100): JsonObject {
        val shapes = listOf(
            listOf(listOf(0,0), listOf(1,0), listOf(2,0), listOf(3,0)),
            listOf(listOf(0,0), listOf(1,0), listOf(0,1), listOf(1,1)),
            listOf(listOf(1,0), listOf(0,1), listOf(1,1), listOf(2,1)),
            listOf(listOf(1,0), listOf(2,0), listOf(0,1), listOf(1,1)),
            listOf(listOf(0,0), listOf(1,0), listOf(1,1), listOf(2,1)),
            listOf(listOf(0,0), listOf(0,1), listOf(1,1), listOf(2,1)),
            listOf(listOf(2,0), listOf(0,1), listOf(1,1), listOf(2,1)),
        )
        fun fits(x: Any, y: Any) = op("fits", v("piece"), x, y)
        fun horizontal(dx: Int) = listOf(iff(fits(op("add", v("x"), dx), v("y")), listOf(set("x", op("add", v("x"), dx)))))
        val dropSteps = listOf(iff(fits(v("x"), op("add", v("y"), 1)), listOf(set("y", op("add", v("y"), 1)))))
        return obj("version" to 1, "width" to width, "height" to height, "players" to 1, "tickMs" to 600,
            "initial" to obj("shapes" to shapes, "piece" to shapes[0], "x" to 0, "y" to 0, "score" to 0, "points" to pointsPerRow),
            "overlay" to obj("shape" to "piece", "x" to "x", "y" to "y", "color" to 3),
            "controls" to listOf(obj("id" to "left", "label" to "←"), obj("id" to "rotate", "label" to "旋转"),
                obj("id" to "right", "label" to "→"), obj("id" to "down", "label" to "↓"), obj("id" to "drop", "label" to "落到底")),
            "handlers" to obj(
                "init" to listOf(obj("emit" to "spawn")),
                "spawn" to listOf(set("piece", op("get", v("shapes"), op("random", op("len", v("shapes"))))),
                    set("x", op("sub", op("div", v("width"), 2), 2)), set("y", 0),
                    iff(op("not", fits(v("x"), v("y"))), listOf(obj("finish" to 0)))),
                "tick" to listOf(iff(fits(v("x"), op("add", v("y"), 1)), listOf(set("y", op("add", v("y"), 1))), listOf(
                    obj("stamp" to listOf(v("piece"), v("x"), v("y"), 3)),
                    set("score", op("add", v("score"), op("mul", op("len", op("fullRows")), v("points")))),
                    obj("compact" to true), obj("emit" to "spawn"),
                ))),
                "left" to horizontal(-1), "right" to horizontal(1),
                "rotate" to listOf(iff(op("fits", op("rotate", v("piece")), v("x"), v("y")), listOf(set("piece", op("rotate", v("piece")))))),
                "down" to listOf(obj("emit" to "tick")),
                "drop" to listOf(obj("for" to "step", "in" to op("range", v("height")), "do" to dropSteps), obj("emit" to "tick")),
            ))
    }
}
