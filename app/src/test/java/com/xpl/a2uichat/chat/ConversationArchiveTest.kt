package com.xpl.a2uichat.chat

import com.xpl.a2uichat.network.ChatMessage
import com.xpl.a2uichat.network.agentJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ConversationArchiveTest {
    @Test fun replacesOnlyUnfavoritedConversation() {
        val a = SavedConversation("a", favorite = true)
        val b = SavedConversation("b")
        val c = SavedConversation("c")
        val archive = ConversationArchive().select(a).select(b).select(c).validate()
        assertEquals(listOf("a", "c"), archive.conversations.map { it.id })
        assertEquals("c", archive.currentId)
    }
    @Test fun favoriteUpdatesDoNotDuplicateAndRemovalPreservesCurrent() {
        val a = SavedConversation("a", favorite = true)
        val archive = ConversationArchive().select(a).select(a.copy(title = "changed")).removeFavorite("a").validate()
        assertEquals(1, archive.conversations.size)
        assertEquals("changed", archive.conversations.single().title)
        assertFalse(archive.conversations.single().favorite)
        assertEquals(listOf("b"), archive.select(SavedConversation("b")).conversations.map { it.id })
    }
    @Test fun removeNonCurrentFavoriteDeletesOnlyThatRecord() {
        val archive = ConversationArchive().select(SavedConversation("a", favorite = true)).select(SavedConversation("b"))
        assertEquals(listOf("b"), archive.removeFavorite("a").validate().conversations.map { it.id })
    }
    @Test fun archiveRoundTripsMessagesAndRetry() {
        val saved = SavedConversation("a", messages = listOf(UiMessage("q", true, "你好"),
            UiMessage("a", false, "部分回复", status = MessageStatus.Generating)),
            history = listOf(ChatMessage("user", "之前的问题"), ChatMessage("assistant", "之前的回复")),
            retry = SavedRetry("a", "你好", "s_a_"), draft = "草稿", streaming = false)
        val archive = ConversationArchive().select(saved)
        assertEquals(archive, agentJson.decodeFromString<ConversationArchive>(agentJson.encodeToString(archive)).validate())
    }
    @Test fun rejectsUnknownVersionAndBrokenSelection() {
        for (archive in listOf(ConversationArchive(version = 2), ConversationArchive(currentId = "missing"),
            ConversationArchive(conversations = listOf(SavedConversation("orphan"))))) {
            assertTrue(runCatching { archive.validate() }.isFailure)
        }
    }
    @Test fun systemPromptsAreNotPersistableHistory() {
        val saved = SavedConversation("a", history = listOf(ChatMessage("system", "not persisted")))
        assertTrue(runCatching { ConversationArchive().select(saved).validate() }.isFailure)
    }
}
