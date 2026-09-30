package com.xpl.a2uichat.games

import com.xpl.a2uichat.games.script.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ScriptProtocolTest {
    private fun spec(source: String = "({})") = buildJsonObject { put("version",1);put("source",source) }
    @Test fun sourceAndVersionAreBounded() {
        assertEquals("({})",ScriptProgram.parse(spec()).source)
        for(v in listOf(spec("x".repeat(65537)),spec("界".repeat(22000)),JsonObject(spec()+("version" to JsonPrimitive(2))))) {
            assertThrows(IllegalArgumentException::class.java) { ScriptProgram.parse(v) }
        }
    }
    @Test fun botAndTimerCannotBeCombined() {
        assertThrows(IllegalArgumentException::class.java) { ScriptProgram.parse(JsonObject(spec()+mapOf("botPlayer" to JsonPrimitive(2),"tickMs" to JsonPrimitive(200)))) }
    }
    @Test fun nativeViewRejectsInvalidBoardAndDuplicateActions() {
        val good="""{"ok":true,"state":{},"view":{"width":2,"height":2,"cells":[0,1,0,0],"turn":1},"actions":[{"type":"tap","x":0,"y":0}],"accepted":true}"""
        assertEquals(2,ScriptSnapshot.parse(good.toByteArray()).view.width)
        for(bad in listOf(good.replace("[0,1,0,0]","[0]"),good.replace("[0,1,0,0]","[0,99,0,0]"),good.replace("\"x\":0","\"x\":30"))) {
            assertThrows(IllegalArgumentException::class.java) { ScriptSnapshot.parse(bad.toByteArray()) }
        }
    }
    @Test fun controlsCannotBeHiddenAndOptionalFieldsAreTyped() {
        val missing="""{"ok":true,"state":{},"view":{"width":2,"height":2,"cells":[0,0,0,0]},"actions":[{"type":"pass"}],"accepted":true}"""
        assertThrows(IllegalArgumentException::class.java) { ScriptSnapshot.parse(missing.toByteArray()) }
        for(pair in listOf("config" to JsonPrimitive("{}"),"botPlayer" to JsonPrimitive("computer"),"tests" to JsonObject(emptyMap()))) {
            assertThrows(IllegalArgumentException::class.java) { ScriptProgram.parse(JsonObject(spec()+pair)) }
        }
    }
}
