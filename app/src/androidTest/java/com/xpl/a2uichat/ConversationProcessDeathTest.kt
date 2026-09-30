package com.xpl.a2uichat

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.xpl.a2uichat.a2ui.AgentPrompt
import com.xpl.a2uichat.chat.*
import com.xpl.a2uichat.games.script.GameApplication
import com.xpl.a2uichat.network.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in, three-stage test. Run prepare, force-stop/relaunch externally, verify, then cleanup. */
class ConversationProcessDeathTest {
    @Test fun savedConversationsSurviveRealProcessDeath() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("conversationProcessDeath")
        assumeTrue(stage in listOf("prepare", "verify", "cleanup"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as GameApplication
        val repo = app.conversations
        val backupFile = File(app.filesDir, "verification-conversations-backup.json")
        val backup = FileConversationRepository(backupFile)
        if (stage == "cleanup") {
            require(backupFile.exists())
            repo.save(backup.load())
            return@runBlocking
        }
        if (stage == "verify") {
            val archive = repo.load()
            assertEquals("最近验证：2048", archive.conversations.single { it.id == archive.currentId }.title)
            assertTrue(archive.conversations.any { it.favorite && it.title == "收藏验证：围棋" })
            assertTrue(archive.conversations.filter { it.title in listOf("收藏验证：围棋", "最近验证：2048") }
                .all { it.surfaces.size == 1 && it.history.size == 2 })
            return@runBlocking
        }
        backup.save(repo.load())
        var calls = 0
        val client = object : AgentClient {
            override fun reply(messages: List<ChatMessage>, stream: Boolean) = flow {
                val id = Regex("本轮新 surfaceId 前缀：(s_[a-f0-9]+_)").find(messages.first().content)!!.groupValues[1] + "game"
                val raw = if (++calls == 1) scriptReply(id, scriptFixture("go").json) else AgentPrompt.gameExample(id, "Game2048")
                val decoder = ReplyDecoder(); decoder.append(raw).forEach { emit(it) }; emit(decoder.finish())
            }
        }
        val store = ViewModelStore()
        val vm = withContext(Dispatchers.Main) { ChatViewModel(client, repo).also { store.put("process", it) } }
        suspend fun ready() = withTimeout(20_000) {
            while (withContext(Dispatchers.Main) { vm.state.value.loading || vm.state.value.busy || vm.state.value.storageBusy }) delay(20)
        }
        try {
            ready()
            withContext(Dispatchers.Main) { vm.newChat() }; ready()
            withContext(Dispatchers.Main) { vm.send("收藏验证：围棋") }; ready()
            withContext(Dispatchers.Main) { vm.toggleFavorite() }; ready()
            withContext(Dispatchers.Main) { vm.newChat() }; ready()
            withContext(Dispatchers.Main) { vm.send("最近验证：2048") }; ready()
            assertEquals(2, calls)
            assertTrue(repo.load().conversations.size >= 2)
        } finally { withContext(Dispatchers.Main) { store.clear() } }
    }
}
