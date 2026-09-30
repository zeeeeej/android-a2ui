package com.xpl.a2uichat

import androidx.test.platform.app.InstrumentationRegistry
import com.xpl.a2uichat.games.script.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun scriptFixture(name: String, size: Int = 9, bot: Boolean = false): ScriptProgram {
    val source = InstrumentationRegistry.getInstrumentation().context.assets.open("script/$name.js").bufferedReader().use { it.readText() }
    return ScriptProgram.parse(buildJsonObject { put("version",1);put("source",source);put("config",buildJsonObject { put("size",size) });if(bot)put("botPlayer",2) })
}
internal fun scriptAction(type: String, x: Int? = null,y: Int? = null) = buildJsonObject { put("type",type);x?.let { put("x",it) };y?.let { put("y",it) } }

class ScriptSandboxTest {
    private val runtime get() = ScriptSandbox.shared()
    @Test fun serviceHasDifferentUidAndNoNetworkPermission() = runBlocking {
        runtime.execute(scriptFixture("go"),"init")
        assertNotEquals(android.os.Process.myUid(),runtime.lastServiceUid)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.checkPermission(android.Manifest.permission.INTERNET,runtime.lastServicePid,runtime.lastServiceUid))
        val ready=CompletableDeferred<IGameSandbox>()
        val conn=object: android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName,binder: android.os.IBinder) {ready.complete(IGameSandbox.Stub.asInterface(binder))}
            override fun onServiceDisconnected(name: android.content.ComponentName) { }
        }
        withContext(Dispatchers.Main) { assertTrue(context.bindService(android.content.Intent(context,GameSandboxService::class.java),conn,android.content.Context.BIND_AUTO_CREATE)) }
        try {
            val oldUid=runtime.lastServiceUid
            withTimeout(5000){ready.await()}.shutdown()
            delay(300)
            assertEquals(9,runtime.execute(scriptFixture("go"),"init").view.width)
            assertNotEquals(oldUid,runtime.lastServiceUid)
        } finally { withContext(Dispatchers.Main){ context.unbindService(conn) } }
    }
    @Test fun gamesAreDynamicAndSeeded() = runBlocking {
        for ((name,size) in listOf("go" to 9,"reversi" to 8,"mines" to 6,"go" to 7,"reversi" to 6)) {
            val p = scriptFixture(name,size)
            runtime.validate(p)
            val a = runtime.execute(p,"init",seed=333);val b = runtime.execute(p,"init",seed=333)
            assertEquals(a,b);assertEquals(size,a.view.width)
            val next = runtime.execute(p,"step",a.state,a.actions.first())
            assertTrue(next.accepted);assertNotEquals(a.state,next.state)
        }
    }
    @Test fun startupProbesRejectAnUnplayableScoringPhase() = runBlocking {
        val base=scriptFixture("go")
        val source=base.source.replace("if(s.phase==='finished')return [];","if(s.phase!=='playing')return [];")
        val bad=ScriptProgram.parse(JsonObject(base.json+("source" to JsonPrimitive(source))))
        var rejected=false
        try {runtime.validate(bad)}catch(_: IllegalArgumentException){rejected=true}
        assertTrue("Pass/pass must discover missing scoring actions",rejected)
        runtime.validate(base)
        val reference=ScriptProgram.parse(buildJsonObject {
            put("version",1);put("source",ScriptExamples.go)
            put("config",buildJsonObject {put("size",7);put("komi",5.5)})
            put("tests",Json.parseToJsonElement("""[{"seed":0,"actions":[{"type":"tap","x":3,"y":3}],"expect":{"absentActions":[{"type":"tap","x":3,"y":3}]}}]"""))
        })
        runtime.validate(reference)
        var s=runtime.execute(reference,"init")
        s=runtime.execute(reference,"step",s.state,scriptAction("pass"));s=runtime.execute(reference,"step",s.state,scriptAction("pass"))
        val done=runtime.execute(reference,"step",s.state,scriptAction("confirm"))
        assertEquals(7,done.view.width);assertEquals(-5.5,done.view.score,0.001)
        val reversi=scriptFixture("reversi",8)
        val negative=ScriptProgram.parse(JsonObject(reversi.json+("tests" to Json.parseToJsonElement("""[{"seed":1,"actions":[{"type":"tap","x":0,"y":0}],"expect":{"absentActions":[{"type":"tap","x":0,"y":0}],"turn":1}}]"""))))
        runtime.validate(negative)
    }
    @Test fun goCaptureSuicideSuperkoAndScoring() = runBlocking {
        val p=scriptFixture("go");val initial=runtime.execute(p,"init")
        fun state(cells: Map<Int,Int>,turn: Int=1, history: List<String> = emptyList()): JsonObject {
            val board=List(81){cells[it] ?: 0}
            return JsonObject(initial.state + mapOf("b" to JsonArray(board.map(::JsonPrimitive)),"turn" to JsonPrimitive(turn),"history" to JsonArray((history+board.joinToString("")).map(::JsonPrimitive))))
        }
        // White at (1,1), three surrounding black stones, last liberty at (1,2).
        val capture=state(mapOf(10 to 2,9 to 1,11 to 1,1 to 1))
        val taken=runtime.execute(p,"step",capture,scriptAction("tap",1,2))
        assertTrue(taken.accepted);assertEquals(0,taken.view.cells[10]);assertEquals(1,taken.view.cells[19])
        val suicide=state(mapOf(9 to 2,11 to 2,1 to 2,19 to 2))
        val rejected=runtime.execute(p,"step",suicide,scriptAction("tap",1,1))
        assertFalse(rejected.accepted);assertEquals(suicide,rejected.state)
        val forbidden=List(81){if(it==40)1 else 0}.joinToString("")
        val repeat=state(emptyMap(),history=listOf(forbidden))
        assertFalse(runtime.execute(p,"step",repeat,scriptAction("tap",4,4)).accepted)
        var s=runtime.execute(p,"step",initial.state,scriptAction("tap",4,4))
        s=runtime.execute(p,"step",s.state,scriptAction("pass"));s=runtime.execute(p,"step",s.state,scriptAction("pass"))
        assertEquals("scoring",s.view.phase)
        val history=s.state["history"]
        s=runtime.execute(p,"step",s.state,scriptAction("tap",4,4));assertEquals(2,s.view.marks[40])
        val resumed=runtime.execute(p,"step",s.state,scriptAction("resume"))
        assertEquals("playing",resumed.view.phase);assertEquals(history,resumed.state["history"]);assertEquals(1,resumed.view.cells[40])
        val finished=runtime.execute(p,"step",s.state,scriptAction("confirm"))
        assertEquals("finished",finished.view.phase);assertEquals(2,finished.view.winner);assertEquals(-7.5,finished.view.score,0.001)
    }
    @Test fun reversiFlipsAndMinesFirstClickSafe() = runBlocking {
        val r=scriptFixture("reversi",8);val start=runtime.execute(r,"init")
        assertEquals(4,start.actions.size)
        val after=runtime.execute(r,"step",start.state,scriptAction("tap",3,2))
        assertEquals(4,after.view.cells.count { it==1 });assertEquals(1,after.view.cells.count { it==2 })
        val m=scriptFixture("mines",6)
        for(seed in listOf(1,42,65535)) {
            val s=runtime.execute(m,"init",seed=seed);val t=runtime.execute(m,"step",s.state,scriptAction("tap",0,0))
            assertTrue(t.accepted);assertEquals(JsonPrimitive(0),t.state["mines"]!!.jsonArray[0])
        }
    }
    @Test fun botUsesLegalActionsAndDoesNotActDuringScoring() = runBlocking {
        val p=scriptFixture("go",9,true);val s=runtime.execute(p,"init")
        val human=runtime.execute(p,"step",s.state,scriptAction("tap",4,4))
        val bot=runtime.execute(p,"bot",human.state)
        assertTrue(bot.accepted);assertEquals(1,bot.view.turn);assertNotEquals(human.state,bot.state)
        var scoring=runtime.execute(p,"step",bot.state,scriptAction("pass"))
        scoring=runtime.execute(p,"step",scoring.state,scriptAction("pass"))
        assertEquals(scoring.state,runtime.execute(p,"bot",scoring.state).state)
    }
    @Test fun hostileCodeCannotReachHostAndRuntimeRecovers() = runBlocking {
        val base=scriptFixture("go")
        suspend fun rejected(source: String) {
            val p=ScriptProgram.parse(JsonObject(base.json+("source" to JsonPrimitive(source))))
            var failed=false
            try { withTimeout(8000) { runtime.execute(p,"init") } } catch (_: Exception) { failed=true }
            assertTrue("Hostile source must fail",failed)
            assertEquals(9,runtime.execute(base,"init").view.width)
        }
        rejected("(()=>{while(true){} })()")
        rejected("(()=>{function f(){return f()}return f()})()")
        rejected("(()=>{let x=[];while(true)x.push(new Array(10000).fill('memory'));})()")
        rejected("({init(){return {n:NaN}},legalActions(){return []},step(){},view(){return {}}})")
        rejected("({init(){return {n:()=>1}},legalActions(){return []},step(){},view(){return {}}})")
        val checks="if(typeof fetch!=='undefined'||typeof require!=='undefined'||typeof Java!=='undefined'||typeof android!=='undefined'||typeof std!=='undefined'||typeof os!=='undefined'||typeof Date!=='undefined'||typeof Math.random!=='undefined')throw Error('host leaked');"
        val safe=ScriptProgram.parse(JsonObject(base.json+("source" to JsonPrimitive("(()=>{$checks return ${base.source};})()"))))
        assertEquals(9,runtime.execute(safe,"init").view.width)
    }
    @Test fun cancellationDoesNotPoisonTheNextRequest() = runBlocking {
        val base=scriptFixture("go")
        val infinite=ScriptProgram.parse(JsonObject(base.json+("source" to JsonPrimitive("(()=>{while(true){}})()"))))
        val job=launch { runtime.execute(infinite,"validate") }
        delay(80);job.cancelAndJoin()
        assertEquals(9,runtime.execute(base,"init").view.width)
    }
}
