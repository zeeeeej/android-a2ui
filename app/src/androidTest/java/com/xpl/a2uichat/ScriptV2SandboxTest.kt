package com.xpl.a2uichat

import androidx.test.platform.app.InstrumentationRegistry
import com.xpl.a2uichat.games.script.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun abstractV2(bot: Boolean = false): ScriptProgram {
    val source = InstrumentationRegistry.getInstrumentation().context.assets.open("script/abstract-v2.js").bufferedReader().use { it.readText() }
    return ScriptProgram.parse(buildJsonObject {
        put("version",2);put("source",source);put("initialPlayer",1);put("turnPolicy","alternating")
        put("players",Json.parseToJsonElement("""[{"id":1,"label":"A","color":"#AA0000","controller":"human"},{"id":2,"label":"B","color":"#111111","controller":"${if(bot) "bot" else "human"}"}]"""))
    })
}

class ScriptV2SandboxTest {
    private val runtime get() = ScriptSandbox.shared()
    @Test fun completeActionsAdvanceBothSidesAndBotUsesOneTurn() = runBlocking {
        val p=abstractV2(true);runtime.validate(p)
        val initial=runtime.execute(p,"init")
        assertEquals(1,initial.view.turn);assertEquals(0,initial.state["moves"]!!.jsonPrimitive.int)
        val next=runtime.execute(p,"step",initial.state,initial.actions.first())
        assertEquals(2,next.view.turn);assertTrue(next.actions.isNotEmpty())
        val start=android.os.SystemClock.elapsedRealtime()
        val bot=runtime.execute(p,"bot",next.state)
        assertEquals(1,bot.view.turn);assertEquals(2,bot.state["moves"]!!.jsonPrimitive.int)
        assertTrue(android.os.SystemClock.elapsedRealtime()-start<3000)
        assertEquals(6,bot.view.drawings.size)
    }
    @Test fun wrongInitialActorAndPartialTurnsAreRejected() = runBlocking {
        val base=abstractV2()
        for(source in listOf(base.source.replace("turn:1,moves:0","turn:2,moves:0"),base.source.replace("s.turn=3-s.turn;",""))) {
            var failed=false
            try {runtime.validate(ScriptProgram.parse(JsonObject(base.json+("source" to JsonPrimitive(source)))))}catch(_: IllegalArgumentException){failed=true}
            assertTrue(failed)
        }
    }
    @Test fun independentTestsMaySupplyStateAndRequiredActions() = runBlocking {
        val base=abstractV2()
        val tests=Json.parseToJsonElement("""[{"state":{"b":[1,0,0,0,0,2],"turn":2,"moves":1},"actions":[{"type":"move","from":5,"to":3}],"expect":{"turn":1,"cells":[[1,1,2]],"legalActions":[{"type":"move","from":0,"to":1}]}}]""")
        runtime.validate(ScriptProgram.parse(JsonObject(base.json+("tests" to tests))))
    }
    @Test fun botDeadlineKeepsOnlyACompleteLegalCheckpoint() = runBlocking {
        val base=abstractV2(true)
        val p=ScriptProgram.parse(JsonObject(base.json+("source" to JsonPrimitive(base.source.replace("return s.b.indexOf(p);","while(true){}")))))
        val start=runtime.execute(p,"init")
        val human=runtime.execute(p,"step",start.state,start.actions.first())
        val before=android.os.SystemClock.elapsedRealtime()
        val result=runtime.execute(p,"bot",human.state)
        val elapsed=android.os.SystemClock.elapsedRealtime()-before
        assertTrue("Expected native 1 second deadline: $elapsed",elapsed in 800..3000)
        assertEquals(1,result.view.turn);assertEquals(2,result.state["moves"]!!.jsonPrimitive.int)
        assertTrue(result.accepted)
    }
}
