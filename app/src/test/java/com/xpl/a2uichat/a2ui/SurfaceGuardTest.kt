package com.xpl.a2uichat.a2ui

import com.xpl.a2uichat.network.AgentException
import com.xpl.a2uichat.network.AgentReply
import com.xpl.a2uichat.network.agentJson
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SurfaceGuardTest {
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
