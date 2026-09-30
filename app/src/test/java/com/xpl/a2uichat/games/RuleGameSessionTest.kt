package com.xpl.a2uichat.games

import com.xpl.a2uichat.games.rules.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleGameSessionTest {
    private suspend fun RuleGameSession.await(predicate: (RuleFrame) -> Boolean) = withTimeout(5000) { frame.first(predicate) }

    @Test fun nonNumericScoreCannotCrashResultSubmission() = runBlocking {
        val source = Json.parseToJsonElement("""{"version":1,"width":4,"height":4,"initial":{"score":[]},"handlers":{"tap":[]}}""").jsonObject
        val session = RuleGameSession(GameKey("s", "root", "RuleGame"), source)
        try {
            session.await { it.state != null }
            assertEquals(0, session.result()["score"])
        } finally { session.dispose() }
    }

    @Test fun localTurnsPauseResumeAndDisposeDoNotLoseState() = runBlocking {
        val store = GameSessionStore()
        val key = GameKey("surface", "root", "RuleGame")
        val session = store.rule(key, RuleExamples.connection(8, 8, 4))
        try {
            session.await { it.state != null }
            store.resume(session)
            session.send(RuleEvent("tap", 3, 3))
            val played = session.await { (it.state?.moves ?: 0) >= 2 && it.state?.turn == 1 }
            assertNull(played.error)
            assertEquals(2, played.state!!.cells.count { it != 0 })
            session.pause()
            val paused = session.frame.value.state
            session.send(RuleEvent("tap", 0, 0))
            delay(30)
            assertEquals(paused, session.frame.value.state)
            assertSame(session, store.rule(key, RuleExamples.connection()))
            store.removeSurfaces(setOf("surface"))
            session.resume()
            assertTrue(session.frame.value.paused)
        } finally { store.clear() }
    }

    @Test fun pendingRuleChangesRequireExplicitApplyAndRestartKeepsPending() = runBlocking {
        val session = RuleGameSession(GameKey("surface", "root", "RuleGame"), RuleExamples.falling())
        try {
            session.await { it.state != null }
            session.resume(); session.send(RuleEvent("drop"))
            val played = session.await { (it.state?.moves ?: 0) > 0 }.state
            session.pause()
            session.offer(RuleExamples.falling(6, 8, 75))
            session.await { it.pending != null }
            assertEquals(played, session.frame.value.state)
            assertEquals(10, session.frame.value.spec!!.width)
            session.restart(true)
            val applied = session.await { it.spec?.width == 6 && !it.computing }
            assertNull(applied.pending); assertTrue(applied.paused)
            assertEquals(0, applied.state!!.moves)
            assertEquals(48, applied.state.cells.size)
        } finally { session.dispose() }
    }

    @Test fun runtimeFaultPreservesPreviousStateAndRequiresRepairOrRestart() = runBlocking {
        val source = Json.parseToJsonElement("""{"version":1,"width":4,"height":4,"initial":{"score":0},"handlers":{"tap":[{"set":"score","value":{"op":"add","args":[{"var":"score"},1]}},{"if":{"op":"ge","args":[{"var":"score"},3]},"then":[{"gridSet":[100,0,1]}]}]}}""").jsonObject
        val session = RuleGameSession(GameKey("s", "root", "RuleGame"), source)
        try {
            session.await { it.state != null }; session.resume()
            repeat(2) { i -> session.send(RuleEvent("tap")); session.await { it.state?.moves == i + 1 } }
            val before = session.frame.value.state
            session.send(RuleEvent("tap"))
            val error = session.await { it.error != null }
            assertEquals(before, error.state); assertTrue(error.paused)
            session.resume(); assertTrue(session.frame.value.paused)
            session.restart()
            val restarted = session.await { it.state?.moves == 0 && !it.computing }
            assertNull(restarted.error)
        } finally { session.dispose() }
    }
}
