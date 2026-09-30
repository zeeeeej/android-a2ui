package com.xpl.a2uichat.games.script

import kotlinx.serialization.json.*

private val color = Regex("#[0-9a-fA-F]{6}")
internal fun validateV2Program(v: JsonObject) {
    checkScript(v.keys.all { it in setOf("version","source","config","players","initialPlayer","turnPolicy","tickMs","tests") }, "v2 spec 含未知字段；botPlayer 由 players 推导")
    val players = (v["players"] as? JsonArray)?.map { it as? JsonObject }
    checkScript(players != null && players.size in 1..2 && players.all { it != null }, "players 需要 1..2 个角色")
    val ps = players!!.filterNotNull()
    checkScript(ps.map { it.number("id") }.toSet().size == ps.size && ps.all {
        it.keys == setOf("id","label","color","controller") && it.number("id") in 1..2 &&
            it.word("label").length in 1..32 && color.matches(it.word("color")) && it.word("controller") in setOf("human","bot")
    }, "角色需要唯一 id、label、#RRGGBB color、human/bot controller")
    checkScript(ps.count { it.word("controller") == "bot" } <= 1 && ps.any { it.word("controller") == "human" }, "最多一个电脑，至少一个人类")
    checkScript(v.number("initialPlayer") in ps.map { it.number("id") }, "initialPlayer 必须引用角色")
    checkScript(v.word("turnPolicy") in setOf("alternating","extraTurn"), "turnPolicy 必须为 alternating/extraTurn")
    checkScript(v.word("turnPolicy") != "alternating" || ps.size == 2, "alternating 需要两个角色")
}

internal fun validateV2View(v: JsonObject, actions: List<JsonObject>, buttons: List<ScriptButton>, w: Int, h: Int): List<ScriptBinding> {
    checkScript(v.keys.all { it in setOf("width","height","cells","palette","labels","marks","style","status","phase","turn","winner","score","buttons","tapType","autoGrid","drawings","textColors","bindings") }, "v2 view 含未知字段")
    checkScript("autoGrid" !in v || (v["autoGrid"] as? JsonPrimitive)?.booleanOrNull != null, "autoGrid 必须是布尔值")
    val colors = v["textColors"] as? JsonArray
    checkScript(colors?.size == w*h && colors.all { color.matches((it as? JsonPrimitive)?.contentOrNull ?: "") }, "textColors 必须为每格明确指定文字颜色")
    val drawings = v["drawings"] as? JsonArray ?: JsonArray(emptyList())
    checkScript("drawings" !in v || v["drawings"] is JsonArray, "drawings 必须为数组")
    checkScript(drawings.size <= 256, "最多 256 个绘图元素")
    drawings.forEach { item ->
        val d = item as? JsonObject ?: error("drawings 元素必须是对象")
        val geometry = when (d.word("type")) {
            "line" -> setOf("x","y","x2","y2")
            "polyline" -> setOf("points")
            "rect" -> setOf("x","y","width","height","fill")
            "circle" -> setOf("x","y","radius","fill")
            "text" -> setOf("x","y","text","size")
            else -> { checkScript(false,"未知绘图类型"); emptySet() }
        }
        checkScript(d.keys.all { it in geometry + setOf("type","layer","color","stroke") }, "绘图含未知字段")
        checkScript(d.word("layer") in setOf("background","grid","decoration"), "绘图层只能为 background/grid/decoration")
        checkScript(color.matches(d.word("color")), "绘图 color 无效")
        fun number(key: String, min: Double = 0.0, max: Double = 1.0): Double {
            val n = (d[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            checkScript(n != null && n.isFinite() && n in min..max, "绘图 $key 必须为 $min..$max")
            return n!!
        }
        if ("stroke" in d) number("stroke",0.001,0.05)
        if (d.word("type") == "polyline") {
            val points = d["points"] as? JsonArray
            checkScript(points != null && points.size in 2..64 && points.all { p ->
                p is JsonArray && p.size == 2 && p.all { (it as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.let { n -> n.isFinite() && n in 0.0..1.0 } == true }
            }, "polyline.points 需要 2..64 个归一化坐标")
        } else {
            val x = number("x"); val y = number("y")
            when (d.word("type")) {
                "line" -> { number("x2"); number("y2") }
                "rect" -> checkScript(x+number("width",0.001) <= 1.000001 && y+number("height",0.001) <= 1.000001,"矩形超出棋盘")
                "circle" -> number("radius",0.001,0.5)
                "text" -> { number("size",0.005,0.2); checkScript(d.word("text").length in 1..64,"绘图文本需要 1..64 字") }
            }
        }
        if ("fill" in d) checkScript((d["fill"] as? JsonPrimitive)?.booleanOrNull != null,"fill 必须是布尔值")
    }
    checkScript("bindings" !in v || v["bindings"] is JsonArray,"bindings 必须为数组")
    val bindings = (v["bindings"] as? JsonArray).orEmpty().map { item ->
        val b = item.jsonObject
        checkScript(b.keys == setOf("action","clicks") && b["action"] is JsonObject,"binding 需要 action/clicks")
        val a = b["action"]!!.jsonObject
        val clicks = b["clicks"] as? JsonArray
        checkScript(a in actions && clicks != null && clicks.size in 1..2,"binding 需要合法完整动作及 1..2 个点击")
        ScriptBinding(a, clicks!!.map { p ->
            checkScript(p is JsonArray && p.size == 2,"点击坐标为 [x,y]")
            val x = (p.jsonArray[0] as? JsonPrimitive)?.intOrNull ?: -1
            val y = (p.jsonArray[1] as? JsonPrimitive)?.intOrNull ?: -1
            checkScript(x in 0 until w && y in 0 until h,"点击超出棋盘")
            x to y
        })
    }
    checkScript(bindings.size <= 512 && bindings.map { it.action }.distinct().size == bindings.size,"每个动作只能绑定一次")
    checkScript(bindings.map { it.clicks }.distinct().size == bindings.size && bindings.none { a -> bindings.any { b -> a != b && a.clicks.size < b.clicks.size && b.clicks.take(a.clicks.size) == a.clicks } },"点击路径有歧义")
    checkScript(buttons.all { it.action in actions },"按钮必须引用合法动作")
    return bindings
}

/** Pure UI selection; never calls JS until a complete action has been chosen. */
fun selectScriptAction(bindings: List<ScriptBinding>, selected: Pair<Int,Int>?, click: Pair<Int,Int>): Pair<Pair<Int,Int>?, JsonObject?> {
    if (selected != null) {
        bindings.firstOrNull { it.clicks == listOf(selected, click) }?.let { return null to it.action }
        if (selected == click) return null to null
    }
    bindings.firstOrNull { it.clicks == listOf(click) }?.let { return null to it.action }
    return (click.takeIf { p -> bindings.any { it.clicks.first() == p } }) to null
}
