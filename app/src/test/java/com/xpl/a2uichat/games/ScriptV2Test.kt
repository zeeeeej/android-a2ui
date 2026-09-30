package com.xpl.a2uichat.games

import com.xpl.a2uichat.games.script.*
import com.xpl.a2uichat.network.ReplyDecoder
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptV2Test {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject
    private val spec get() = obj("""{"version":2,"source":"({})","players":[{"id":1,"label":"A","color":"#AA0000","controller":"bot"},{"id":2,"label":"B","color":"#000000","controller":"human"}],"initialPlayer":1,"turnPolicy":"alternating"}""")
    private val frame get() = obj("""{"ok":true,"state":{},"accepted":true,"view":{"width":2,"height":2,"cells":[1,0,0,2],"turn":1,"textColors":["#FFFFFF","#000000","#000000","#FFFFFF"],"bindings":[{"action":{"type":"move","from":0,"to":1},"clicks":[[0,0],[1,0]]}],"autoGrid":false,"drawings":[{"type":"text","layer":"decoration","color":"#000000","x":0.5,"y":0.5,"size":0.1,"text":"TEST"},{"type":"line","layer":"grid","color":"#000000","x":0.1,"y":0.1,"x2":0.9,"y2":0.9}]},"actions":[{"type":"move","from":0,"to":1}]}""")
    private fun parse(f: JsonObject = frame) = ScriptSnapshot.parse(f.toString().toByteArray(),2)
    private fun changeView(key: String, value: JsonElement) = JsonObject(frame + ("view" to JsonObject(frame["view"]!!.jsonObject + (key to value))))
    @Test fun explicitRolesDoNotDependOnPaletteOrder() {
        assertEquals(1,ScriptProgram.parse(spec).botPlayer)
        assertThrows(IllegalArgumentException::class.java) { ScriptProgram.parse(JsonObject(spec + ("botPlayer" to JsonPrimitive(2)))) }
        assertThrows(IllegalArgumentException::class.java) { ScriptProgram.parse(JsonObject(spec + ("initialPlayer" to JsonPrimitive(3)))) }
    }
    @Test fun selectionIsNotAnActionUntilComplete() {
        val v = parse().view
        val first = selectScriptAction(v.bindings,null,0 to 0)
        assertEquals(0 to 0,first.first); assertNull(first.second)
        assertEquals(null to null,selectScriptAction(v.bindings,first.first,0 to 0))
        assertEquals(parse().actions.single(),selectScriptAction(v.bindings,first.first,1 to 0).second)
        assertEquals(null to null,selectScriptAction(v.bindings,null,1 to 1))
    }
    @Test fun drawingLimitsAndUnknownFieldsAreRejected() {
        assertEquals(2,parse().view.drawings.size)
        val drawing = frame["view"]!!.jsonObject["drawings"]!!.jsonArray.first().jsonObject
        for (bad in listOf(JsonObject(drawing + ("x" to JsonPrimitive(1.1))),JsonObject(drawing + ("text" to JsonPrimitive("x".repeat(65)))),JsonObject(drawing + ("foo" to JsonPrimitive(true))))) {
            assertThrows(IllegalArgumentException::class.java) { parse(changeView("drawings",JsonArray(listOf(bad)))) }
        }
        assertThrows(IllegalArgumentException::class.java) { parse(changeView("drawings",JsonArray(List(257) { drawing }))) }
        assertThrows(IllegalArgumentException::class.java) { parse(changeView("river",JsonPrimitive("not a primitive"))) }
        assertThrows(IllegalArgumentException::class.java) { parse(changeView("textColors",JsonArray(emptyList()))) }
    }
    @Test fun ambiguousAndHiddenActionsCannotPass() {
        assertThrows(IllegalArgumentException::class.java) { parse(changeView("bindings",JsonArray(emptyList()))) }
        val binding = frame["view"]!!.jsonObject["bindings"]!!.jsonArray.single()
        assertThrows(IllegalArgumentException::class.java) { parse(changeView("bindings",JsonArray(listOf(binding,binding)))) }
    }
    private val plan get() = obj("""{"intent":"create","targetSurfaceId":"","targetComponentId":"root","requirements":[{"id":"R1","text":"中心文字","kind":"boardText","check":{"text":"TEST","region":[0.4,0.4,0.6,0.6]}},{"id":"R2","text":"斜线","kind":"geometry","check":{"type":"line","minCount":1,"diagonal":true,"region":[0,0,1,1]}},{"id":"R3","text":"电脑红先","kind":"role","check":{"id":1,"controller":"bot","color":"#AA0000"}}]}""")
    @Test fun requirementsCheckActualDrawingNotStatus() {
        val p = ScriptProgram.parse(spec); GameVerification.plan(plan,emptySet())
        assertTrue(GameVerification.localChecks(plan,p,parse()).isEmpty())
        val wrong = parse(changeView("drawings",JsonArray(emptyList())))
        assertEquals(2,GameVerification.localChecks(plan,p,wrong).size)
    }
    @Test fun fingerprintBindsConfigRequirementsAndVerifier() {
        val a = GameVerification.fingerprint(ScriptProgram.parse(spec),plan)
        assertEquals(64,a.length)
        assertNotEquals(a,GameVerification.fingerprint(ScriptProgram.parse(JsonObject(spec + ("config" to obj("""{"size":8}""")))),plan))
        assertEquals(a,GameVerification.fingerprint(ScriptProgram.parse(JsonObject(spec.entries.reversed().associate { it.toPair() })),plan))
    }
    @Test fun replyUnionIsMutuallyExclusive() {
        assertNotNull(ReplyDecoder().apply { append("""{"gamePlan":$plan}""") }.finish().reply.gamePlan)
        for (s in listOf("""{"text":"claim","gamePlan":$plan}""","""{"gamePlan":$plan,"review":{}}""")) {
            assertThrows(Exception::class.java) { ReplyDecoder().apply { append(s); finish() } }
        }
    }
    @Test fun misplacedNegativeTestExplainsTheExactSchemaPath() {
        val tests=Json.parseToJsonElement("""[{"actions":[],"absentActions":[],"expect":{"turn":1}}]""")
        val fault=assertThrows(IllegalArgumentException::class.java) { ScriptProgram.parse(JsonObject(spec+("tests" to tests))) }
        assertTrue(fault.message!!.contains("tests[0].expect.absentActions"))
    }
}
