package com.xpl.a2uichat.a2ui

import com.xpl.a2uichat.network.AgentException
import com.xpl.a2uichat.network.AgentReply
import com.xpl.a2uichat.network.agentJson
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SurfaceGuardTest {
    @Test fun gamesValidateAndProduceIsolatedKeys() {
        val guard = SurfaceGuard()
        for (type in listOf("Game2048", "SnakeGame")) {
            val reply = agentJson.decodeFromString<AgentReply>(AgentPrompt.gameExample("s_test_$type", type))
            reply.a2uiMessages.forEach { guard.accept(it, "s_test_") }
        }
        guard.validateComplete()
        assertEquals(2, guard.gameKeys().size)
        assertEquals(setOf("Game2048", "SnakeGame"), guard.gameKeys().map { it.type }.toSet())
    }

    @Test fun invalidGameConfigurationRejectedBeforeRender() {
        for (replacement in listOf("\"gameId\":\"wrong\"", "\"gameId\":123", "\"gameId\":\"root\",\"difficulty\":\"extreme\"",
            "\"gameId\":\"root\",\"title\":42", "\"gameId\":\"root\",\"child\":\"root\"")) {
            val reply = agentJson.decodeFromString<AgentReply>(AgentPrompt.gameExample("s_test_game")
                .replace("\"gameId\":\"root\"", replacement).replace("\"title\":\"2048\"", "\"unused\":true"))
            assertThrows(AgentException::class.java) {
                val guard = SurfaceGuard()
                reply.a2uiMessages.forEach { guard.accept(it, "s_test_") }
                guard.validateComplete()
            }
        }
    }

    @Test fun detachedGamesDoNotKeepStateAndRepeatedGamesAreRejected() {
        val guard = SurfaceGuard()
        agentJson.decodeFromString<AgentReply>(AgentPrompt.gameExample("s_test_game")).a2uiMessages.forEach { guard.accept(it, "s_test_") }
        fun update(components: String) = agentJson.parseToJsonElement("""{"version":"v0.9","updateComponents":{"surfaceId":"s_test_game","components":$components}}""").jsonObject
        guard.accept(update("""[{"id":"root","component":"Column","children":["game","game"]},{"id":"game","component":"SnakeGame","gameId":"game"}]"""), "s_test_")
        assertThrows(AgentException::class.java) { guard.validateComplete() }
        guard.accept(update("""[{"id":"root","component":"Text","text":"已移除"}]"""), "s_test_")
        guard.validateComplete()
        assertTrue(guard.gameKeys().isEmpty())
    }

    private fun example() = agentJson.decodeFromString<AgentReply>(AgentPrompt.formExample("s_test_form"))
    @Test fun formExampleIsAValidBoundedTree() {
        val guard = SurfaceGuard()
        example().a2uiMessages.forEach { guard.accept(it, "s_test_") }
        guard.validateComplete()
        assertEquals(setOf("s_test_form"), guard.ids)
    }
    @Test fun foreignSurfacesAreRejected() {
        assertThrows(AgentException::class.java) { SurfaceGuard().accept(example().a2uiMessages.first(), "foreign_") }
    }
    @Test fun cyclesAndMissingChildrenFail() {
        for (child in listOf("root", "missing")) {
            val guard = SurfaceGuard()
            guard.accept(example().a2uiMessages.first(), "s_test_")
            assertThrows(AgentException::class.java) {
                guard.accept(agentJson.parseToJsonElement("""{"version":"v0.9","updateComponents":{"surfaceId":"s_test_form","components":[{"id":"root","component":"Card","child":"$child"}]}}""").jsonObject, "s_test_")
                guard.validateComplete()
            }
        }
    }
    @Test fun rejectedTransactionDoesNotChangeCommittedTree() {
        val original = SurfaceGuard()
        example().a2uiMessages.forEach { original.accept(it, "s_test_") }
        val copy = original.copy()
        copy.accept(agentJson.parseToJsonElement("""{"version":"v0.9","deleteSurface":{"surfaceId":"s_test_form"}}""").jsonObject, "s_new_")
        assertTrue(copy.ids.isEmpty())
        assertEquals(setOf("s_test_form"), original.ids)
    }
}
