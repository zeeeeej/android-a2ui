package com.xpl.a2uichat.games

import com.xpl.a2uichat.games.rules.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RuleEngineTest {
    private fun parse(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun tiny(body: String, initial: String = "{}") = parse("""{"version":1,"width":4,"height":4,"initial":$initial,"handlers":{"tap":$body}}""")

    @Test fun connectionProgramChecksOccupancyTurnWinAndDraw() {
        val engine = RuleEngine(RuleSpec.parse(RuleExamples.connection()))
        var state = engine.initial(5)
        repeat(4) { x ->
            state = engine.event(state, RuleEvent("tap", x, 0)).state
            assertEquals(2, state.turn)
            val invalid = engine.event(state, RuleEvent("tap", x, 0))
            assertFalse(invalid.accepted); assertEquals(state, invalid.state)
            state = engine.event(state, RuleEvent("tap", x, 2)).state
        }
        state = engine.event(state, RuleEvent("tap", 4, 0)).state
        assertEquals(1, state.winner)
        assertFalse(engine.event(state, RuleEvent("tap", 5, 0)).accepted)
        val drawEngine = RuleEngine(RuleSpec.parse(RuleExamples.connection(2, 2, 5)))
        var draw = drawEngine.initial()
        for (i in 0..3) draw = drawEngine.event(draw, RuleEvent("tap", i % 2, i / 2)).state
        assertEquals(0, draw.winner)
    }

    @Test fun unseenFourInRowVariantUsesOnlyDataChanges() {
        val engine = RuleEngine(RuleSpec.parse(RuleExamples.connection(8, 8, 4)))
        var state = engine.initial()
        repeat(3) { x -> state = engine.event(state, RuleEvent("tap", x, 0)).state; state = engine.event(state, RuleEvent("tap", x, 3)).state }
        state = engine.event(state, RuleEvent("tap", 3, 0)).state
        assertEquals(64, state.cells.size); assertEquals(1, state.winner)
    }

    @Test fun fallingProgramMovesRotatesLocksAndIsDeterministic() {
        val engine = RuleEngine(RuleSpec.parse(RuleExamples.falling()))
        val initial = engine.initial(123)
        assertEquals(initial, engine.initial(123))
        val down = engine.event(initial, RuleEvent("tick")).state
        assertEquals(1, down.vars.getValue("y").jsonPrimitive.int)
        val left = engine.event(down, RuleEvent("left")).state
        assertEquals(down.vars.getValue("x").jsonPrimitive.int - 1, left.vars.getValue("x").jsonPrimitive.int)
        val rotated = engine.event(left, RuleEvent("rotate")).state
        assertEquals(4, rotated.vars.getValue("piece").jsonArray.size)
        val dropped = engine.event(rotated, RuleEvent("drop")).state
        assertEquals(4, dropped.cells.count { it != 0 })
        assertEquals(0, dropped.vars.getValue("y").jsonPrimitive.int)
    }

    @Test fun fallingClearsRowsScoresAndChangesShapesWithoutNewCode() {
        val source = RuleExamples.falling(6, 8, 75)
        val initial = source.getValue("initial").jsonObject.toMutableMap().apply {
            this["shapes"] = Json.parseToJsonElement("[[[0,0],[1,0],[2,0]]]")
            this["piece"] = Json.parseToJsonElement("[[0,0],[1,0],[2,0]]")
        }
        val engine = RuleEngine(RuleSpec.parse(JsonObject(source + ("initial" to JsonObject(initial)))))
        val base = engine.initial(1)
        val state = base.copy(cells = List(48) { if (it >= 42 && it % 6 >= 3) 3 else 0 },
            vars = base.vars + ("x" to JsonPrimitive(0)))
        val next = engine.event(state, RuleEvent("drop")).state
        assertEquals(75, next.vars.getValue("score").jsonPrimitive.int)
        assertTrue(next.cells.all { it == 0 })
        val lost = engine.event(next.copy(cells = List(48) { 3 }), RuleEvent("spawn")).state
        assertEquals(0, lost.winner)
    }

    @Test fun staticTypesUnknownReferencesAndRecursiveEmitsAreRejected() {
        for (body in listOf("""[{"set":"missing","value":0}]""", """[{"emit":"tap"}]""",
            """[{"assert":{"op":"http","args":[]}}]""", """[{"assert":5}]""",
            """[{"gridSet":[0,"wrong",1]}]""")) {
            assertThrows(RuleFault::class.java) { RuleSpec.parse(tiny(body)) }
        }
    }

    @Test fun failureAndIllegalMoveDoNotCommitPartialWritesOrRandomState() {
        val engine = RuleEngine(RuleSpec.parse(tiny("""[{"gridSet":[0,0,1]},{"set":"n","value":{"op":"random","args":[10]}},{"assert":false}]""", """{"n":0}""")))
        val initial = engine.initial(1)
        val next = engine.event(initial, RuleEvent("tap"))
        assertFalse(next.accepted); assertSame(initial, next.state); assertEquals(0, initial.cells[0])
        val broken = RuleEngine(RuleSpec.parse(tiny("""[{"gridSet":[0,0,1]},{"gridSet":[99,0,1]}]"""), probe = false))
        assertThrows(RuleFault::class.java) { broken.event(broken.initial(1), RuleEvent("tap")) }
    }

    @Test fun finiteLoopsStillHaveOperationBudget() {
        val source = tiny("""[{"for":"i","in":{"op":"range","args":[400]},"do":[{"for":"j","in":{"op":"range","args":[400]},"do":[{"gridSet":[0,0,1]}]}]}]""")
        val engine = RuleEngine(RuleSpec.parse(source, probe = false))
        val state = engine.initial()
        assertThrows(RuleFault::class.java) { engine.event(state, RuleEvent("tap")) }
        assertTrue(state.cells.all { it == 0 })
    }

    @Test fun botUsesRulesAndChoosesWinningMoveWithoutNetwork() {
        val engine = RuleEngine(RuleSpec.parse(RuleExamples.connection(5, 5, 4)))
        val base = engine.initial(1)
        val state = base.copy(turn = 2, cells = List(25) { if (it in 0..2) 2 else 0 })
        val move = RuleBot.choose(engine, state)
        assertEquals(2, engine.event(state, move).state.winner)
    }

    @Test fun botReturnsLegalMoveAndHonorsCancellation() {
        val engine = RuleEngine(RuleSpec.parse(RuleExamples.connection(8, 8, 4)))
        val initial = engine.event(engine.initial(), RuleEvent("tap", 4, 4)).state
        val event = RuleBot.choose(engine, initial)
        assertTrue(engine.event(initial, event).accepted)
        assertThrows(RuleFault::class.java) { RuleBot.choose(engine, initial) { true } }
    }

    @Test fun botBlocksImmediateThreat() {
        val engine = RuleEngine(RuleSpec.parse(RuleExamples.connection(8, 8, 4)))
        val initial = engine.initial().copy(turn = 2, cells = List(64) { if (it in 0..2) 1 else if (it == 36) 2 else 0 })
        val event = RuleBot.choose(engine, initial)
        assertEquals(RuleEvent("tap", 3, 0), event)
    }

    @Test fun sharedArrayExpansionCannotBypassMemoryBudget() {
        val engine = RuleEngine(RuleSpec.parse(tiny("""[{"set":"a","value":[{"var":"a"},{"var":"a"},{"var":"a"},{"var":"a"}]}]""", """{"a":[1,2,3,4]}""")))
        var state = engine.initial()
        assertThrows(RuleFault::class.java) { repeat(20) { state = engine.event(state, RuleEvent("tap")).state } }
        assertTrue(state.moves < 20)
    }

    @Test fun stateAndGridAndPaletteBoundsAreValidated() {
        assertThrows(RuleFault::class.java) { RuleSpec.parse(JsonObject(RuleExamples.connection() + ("width" to JsonPrimitive(999)))) }
        assertThrows(RuleFault::class.java) { RuleSpec.parse(tiny("""[{"gridSet":[0,0,99]}]""")) }
        assertThrows(RuleFault::class.java) { RuleSpec.parse(tiny("""[{"set":"n","value":{"op":"div","args":[1,0]}}]""", """{"n":0}""")) }
    }

    @Test fun startupProbeRejectsRulesThatForbidComputerTurns() {
        val source = RuleExamples.connection(8, 8, 4)
        val handlers = source.getValue("handlers").jsonObject
        val tap = JsonArray(listOf(Json.parseToJsonElement("""{"assert":{"op":"eq","args":[{"var":"turn"},1]}}""")) + handlers.getValue("tap").jsonArray)
        assertThrows(RuleFault::class.java) { RuleSpec.parse(JsonObject(source + ("handlers" to JsonObject(handlers + ("tap" to tap))))) }
    }
}
