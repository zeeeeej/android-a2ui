package com.xpl.a2uichat.chat

import android.util.AtomicFile
import com.xpl.a2uichat.network.ChatMessage
import com.xpl.a2uichat.network.agentJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

@Serializable
data class SavedSurface(val id: String, val owner: String, val prefix: String, val messages: List<JsonObject>)

@Serializable
data class SavedRule(val surfaceId: String, val gameId: String, val type: String, val source: JsonObject)

@Serializable
data class SavedRetry(val id: String, val content: String, val prefix: String)

@Serializable
data class SavedConversation(
    val id: String,
    val title: String = "新对话",
    val updatedAt: Long = 0,
    val favorite: Boolean = false,
    val messages: List<UiMessage> = emptyList(),
    val history: List<ChatMessage> = emptyList(),
    val surfaces: List<SavedSurface> = emptyList(),
    val activeRules: List<SavedRule> = emptyList(),
    val retry: SavedRetry? = null,
    val draft: String = "",
    val streaming: Boolean = true,
)

@Serializable
data class ConversationArchive(
    val version: Int = 1,
    val currentId: String? = null,
    val conversations: List<SavedConversation> = emptyList(),
) {
    fun validate(): ConversationArchive {
        require(version == 1) { "存档版本不兼容，请更新应用；原文件已保留。" }
        require(conversations.map { it.id }.distinct().size == conversations.size)
        require(currentId == null || conversations.any { it.id == currentId })
        require(conversations.all { it.id == currentId || it.favorite })
        require(conversations.all { c -> c.history.all { it.role in setOf("user", "assistant") } })
        conversations.forEach { c ->
            require(c.id.isNotBlank())
            require(c.messages.map { it.id }.distinct().size == c.messages.size)
            require(c.surfaces.map { it.id }.distinct().size == c.surfaces.size)
            c.retry?.let { retry ->
                require(c.messages.any { it.id == retry.id && !it.isUser && it.status != MessageStatus.Complete })
                require(retry.prefix == "s_${retry.id.take(8)}_")
            }
        }
        return this
    }

    fun select(conversation: SavedConversation) = copy(currentId = conversation.id,
        conversations = (conversations.filter { it.id != conversation.id } + conversation)
            .filter { it.favorite || it.id == conversation.id })

    fun removeFavorite(id: String) = copy(conversations = conversations.map {
        if (it.id == id) it.copy(favorite = false) else it
    }.filter { it.favorite || it.id == currentId })
}

interface ConversationRepository {
    suspend fun load(): ConversationArchive
    suspend fun save(archive: ConversationArchive)
}

/** One atomic archive makes current-selection and favorite changes a single disk transaction. */
class FileConversationRepository(file: File) : ConversationRepository {
    private val atomic = AtomicFile(file)
    private val mutex = Mutex()
    override suspend fun load(): ConversationArchive = withContext(Dispatchers.IO) {
        mutex.withLock {
            // openRead also recovers AtomicFile's backup from an interrupted write.
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) ConversationArchive()
            else agentJson.decodeFromString<ConversationArchive>(atomic.readFully().decodeToString()).validate()
        }
    }
    override suspend fun save(archive: ConversationArchive) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = agentJson.encodeToString(archive.validate()).toByteArray()
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
        }
    }
}

class MemoryConversationRepository(initial: ConversationArchive = ConversationArchive()) : ConversationRepository {
    var archive = initial
        private set
    override suspend fun load() = archive.validate()
    override suspend fun save(archive: ConversationArchive) { this.archive = archive.validate() }
}
