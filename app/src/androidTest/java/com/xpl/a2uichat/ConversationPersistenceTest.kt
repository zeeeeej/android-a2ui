package com.xpl.a2uichat

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.xpl.a2uichat.a2ui.AgentPrompt
import com.xpl.a2uichat.chat.*
import com.xpl.a2uichat.games.GameKey
import com.xpl.a2uichat.games.Direction
import com.xpl.a2uichat.games.rules.RuleExamples
import com.xpl.a2uichat.network.*
import com.xpl.a2uichat.ui.ChatTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ConversationPersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store = ViewModelStore()
    private lateinit var vm: ChatViewModel
    private fun client(reply: suspend (List<ChatMessage>) -> String) = object : AgentClient {
        override fun reply(messages: List<ChatMessage>, stream: Boolean) = flow {
            val decoder = ReplyDecoder()
            decoder.append(reply(messages)).forEach { emit(it) }
            emit(decoder.finish())
        }
    }
    private val greeting get() = client { """{"text":"已完成","a2uiMessages":[]}""" }
    private fun prefix(m: List<ChatMessage>) = Regex("本轮新 surfaceId 前缀：(s_[a-f0-9]+_)").find(m.first().content)!!.groupValues[1] + "game"
    private fun attach(repo: ConversationRepository, agent: AgentClient = greeting) {
        compose.runOnUiThread {
            store.clear()
            vm = ChatViewModel(agent, repo)
            store.put("chat", vm)
            compose.activity.setContent { ChatTheme { ChatScreen(vm) } }
        }
        compose.waitUntil(20_000) { !vm.state.value.loading }
    }
    private fun idle() { compose.waitUntil(20_000) { !vm.state.value.busy && !vm.state.value.storageBusy } }
    private fun send(text: String) { compose.runOnUiThread { vm.send(text) }; idle() }
    @After fun cleanup() { compose.runOnUiThread { store.clear() } }

    @Test fun restoredConversationContinuesWithHistoryWithoutAutomaticRequest() {
        val repo = MemoryConversationRepository()
        attach(repo); send("原问题")
        compose.runOnUiThread { vm.setDraft("草稿"); vm.setStreaming(false); vm.flush() }
        compose.waitUntil { repo.archive.conversations.single().draft == "草稿" }
        val requests = mutableListOf<List<ChatMessage>>()
        attach(repo, client { requests += it; """{"text":"续聊","a2uiMessages":[]}""" })
        assertEquals(0, requests.size)
        assertEquals(2, vm.state.value.messages.size)
        assertEquals("草稿", vm.state.value.draft); assertFalse(vm.state.value.streaming)
        send("接着聊")
        assertEquals(listOf("system", "user", "assistant", "user"), requests.single().map { it.role })
        assertEquals("原问题", requests.single()[1].content)
    }

    @Test fun favoriteTracksFurtherMessagesAndSurvivesNewChat() {
        val repo = MemoryConversationRepository()
        attach(repo); send("收藏这一段")
        compose.onNodeWithTag("favorite_chat").performClick(); idle()
        val id = vm.state.value.conversationId
        send("后续消息")
        assertEquals(4, repo.archive.conversations.single().messages.size)
        compose.runOnUiThread { vm.newChat() }; idle()
        assertTrue(vm.state.value.messages.isEmpty())
        send("将被替换")
        compose.onNodeWithTag("chat_menu").performClick()
        compose.onNodeWithTag("favorites_list").performClick()
        compose.onNodeWithTag("open_favorite_$id").performClick()
        compose.onNodeWithText("打开对话").performClick(); idle()
        assertEquals(id, vm.state.value.conversationId)
        assertEquals(4, vm.state.value.messages.size)
        assertEquals(1, repo.archive.conversations.size)
        compose.runOnUiThread { vm.openFavorite(id) }; idle()
        assertEquals(4, vm.state.value.messages.size)
        compose.runOnUiThread { vm.removeFavorite(id) }; idle()
        assertFalse(vm.state.value.favorite)
        assertEquals(1, repo.archive.conversations.size)
        compose.runOnUiThread { vm.newChat() }; idle()
        assertFalse(repo.archive.conversations.any { it.id == id })
    }

    @Test fun interruptedReplyRestoresStoppedAndRetriesWithoutDuplicatingMessages() {
        val repo = MemoryConversationRepository()
        attach(repo, object : AgentClient {
            override fun reply(messages: List<ChatMessage>, stream: Boolean) = flow {
                emit(AgentEvent.Text("中断前文字")); awaitCancellation()
            }
        })
        compose.runOnUiThread { vm.send("长回复") }
        compose.waitUntil { vm.state.value.messages.lastOrNull()?.text == "中断前文字" }
        compose.runOnUiThread { vm.flush() }
        compose.waitUntil { repo.archive.conversations.single().messages.last().text == "中断前文字" }
        val recovered = MemoryConversationRepository(repo.archive)
        var calls = 0
        attach(recovered, client { calls++; """{"text":"重新完成","a2uiMessages":[]}""" })
        assertEquals(0, calls)
        assertEquals(MessageStatus.Stopped, vm.state.value.messages.last().status)
        compose.onNodeWithTag("retry").performScrollTo().performClick(); idle()
        assertEquals(1, calls); assertEquals(2, vm.state.value.messages.size)
        assertEquals("重新完成", vm.state.value.messages.last().text)
    }

    @Test fun failedCandidatesNeverReplaceSavedFormOrEnterHistory() {
        val repo = MemoryConversationRepository()
        var calls = 0
        attach(repo, client { m -> if (++calls == 1) AgentPrompt.formExample(prefix(m)) else
            ruleReply(prefix(m), JsonObject(RuleExamples.connection() + ("width" to JsonPrimitive(999)))) })
        send("表单"); send("坏规则")
        assertEquals(5, calls)
        val saved = repo.archive.conversations.single()
        assertEquals(1, saved.surfaces.size)
        assertFalse(saved.history.any { it.content.contains("999") })
        attach(repo)
        compose.onNodeWithText("想去的城市").performScrollTo().assertExists()
        assertEquals(MessageStatus.Failed, vm.state.value.messages.last().status)
    }

    @Test fun scriptRulesRestoreFreshBoardAndKeepUnappliedUpdatePending() {
        val repo = MemoryConversationRepository()
        var calls = 0; var surface = ""
        attach(repo, client { m -> if (++calls == 1) {
            surface = prefix(m); scriptReply(surface, scriptFixture("go").json)
        } else scriptReply(surface, scriptFixture("go", 7).json, true) })
        send("围棋")
        val key = GameKey(surface, "root", "ScriptGame")
        compose.waitUntil { vm.games.findScript(key)?.frame?.value?.snapshot != null }
        compose.onNodeWithTag("script_board").performScrollTo()
        val original = vm.games.findScript(key)!!
        compose.runOnUiThread { original.resume(); original.send(original.frame.value.snapshot!!.actions.first()) }
        compose.waitUntil { original.frame.value.moves == 1 }
        send("改成7路")
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.onNodeWithTag("messages").performScrollToIndex(1)
        compose.onNodeWithTag("script_board").performScrollTo()
        compose.waitUntil { original.frame.value.pending != null }
        compose.runOnUiThread { vm.flush() }
        compose.waitUntil { repo.archive.conversations.single().activeRules.isNotEmpty() }
        attach(repo)
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.onNodeWithTag("messages").performScrollToIndex(1)
        compose.waitUntil { vm.games.findScript(key)?.frame?.value?.snapshot != null }
        compose.onNodeWithTag("script_board").performScrollTo()
        val restored = vm.games.findScript(key)!!
        assertEquals(9, restored.frame.value.snapshot!!.view.width)
        assertEquals(0, restored.frame.value.moves); assertTrue(restored.frame.value.paused)
        compose.waitUntil { restored.frame.value.pending != null }
        compose.onNodeWithTag("script_apply").performScrollTo().performClick()
        compose.waitUntil { restored.frame.value.snapshot?.view?.width == 7 }
        assertEquals(2, calls)
    }

    @Test fun multipleTurnCardsAndNativeGamesRestoreWithFreshProgress() {
        val repo = MemoryConversationRepository()
        val types = listOf("Game2048", "SnakeGame", "RuleGame", "form")
        val surfaces = mutableListOf<String>(); var calls = 0
        attach(repo, client { m ->
            val id = prefix(m); surfaces += id
            when (val type = types[calls++]) {
                "RuleGame" -> ruleReply(id, RuleExamples.connection(8, 8, 4))
                "form" -> AgentPrompt.formExample(id)
                else -> AgentPrompt.gameExample(id, type)
            }
        })
        types.forEach { send(it) }
        val nativeKey = GameKey(surfaces[0], "root", "Game2048")
        compose.runOnUiThread {
            val game = vm.games.get(nativeKey)
            listOf(Direction.Left, Direction.Down, Direction.Right).forEach(game::move)
            assertTrue(game.board2048.moves > 0)
        }
        assertEquals(4, repo.archive.conversations.single().surfaces.size)
        attach(repo)
        assertEquals(4, vm.surfaces.value.size)
        compose.runOnUiThread {
            assertEquals(0, vm.games.get(nativeKey).board2048.moves)
            assertEquals(0, vm.games.get(GameKey(surfaces[1], "root", "SnakeGame")).snake.moves)
        }
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.onNodeWithTag("messages").performScrollToIndex(5)
        val ruleKey = GameKey(surfaces[2], "root", "RuleGame")
        compose.waitUntil { vm.games.findRule(ruleKey)?.frame?.value?.state != null }
        assertEquals(8, vm.games.findRule(ruleKey)!!.frame.value.spec!!.width)
        assertTrue(vm.games.findRule(ruleKey)!!.frame.value.paused)
        assertEquals(4, calls)
    }

    @Test fun ruleGameRestorationAlsoKeepsUnappliedRulesPending() {
        val repo = MemoryConversationRepository()
        var surface = ""; var calls = 0
        attach(repo, client { m -> if (++calls == 1) {
            surface = prefix(m); ruleReply(surface, RuleExamples.connection(8, 8, 4))
        } else ruleReply(surface, RuleExamples.connection(6, 6, 3), true) })
        send("四子棋")
        val key = GameKey(surface, "root", "RuleGame")
        compose.waitUntil { vm.games.findRule(key)?.frame?.value?.spec != null }
        send("改成六路")
        attach(repo)
        compose.onNodeWithTag("messages").performTouchInput { swipeDown() }
        compose.onNodeWithTag("messages").performScrollToIndex(1)
        compose.waitUntil { vm.games.findRule(key)?.frame?.value?.pending != null }
        val frame = vm.games.findRule(key)!!.frame.value
        assertEquals(8, frame.spec!!.width)
        assertEquals(6, frame.pending!!.width)
        assertTrue(frame.paused)
        assertEquals(2, calls)
    }

    @Test fun cancellationDuringPreRequestSaveNeverStartsTheNetwork() = runBlocking {
        val memory = MemoryConversationRepository()
        val saving = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repo = object : ConversationRepository {
            override suspend fun load() = memory.load()
            override suspend fun save(archive: ConversationArchive) {
                saving.complete(Unit); release.await(); memory.save(archive)
            }
        }
        var calls = 0
        attach(repo, client { calls++; """{"text":"不应发送","a2uiMessages":[]}""" })
        compose.runOnUiThread { vm.send("停止") }
        withTimeout(5000) { saving.await() }
        compose.runOnUiThread { vm.stop() }
        release.complete(Unit)
        compose.waitUntil { memory.archive.conversations.firstOrNull()?.messages?.lastOrNull()?.status == MessageStatus.Stopped }
        assertEquals(0, calls)
    }

    @Test fun invalidSavedCardDoesNotPreventTextRestoration() {
        val repo = MemoryConversationRepository()
        attach(repo, client { AgentPrompt.formExample(prefix(it)) }); send("保留文字")
        val archive = repo.archive
        val current = archive.conversations.single()
        val damaged = current.copy(surfaces = current.surfaces.map { it.copy(prefix = "invalid") })
        attach(MemoryConversationRepository(archive.select(damaged)))
        assertFalse(vm.state.value.storageBlocked)
        assertEquals(2, vm.state.value.messages.size)
        assertTrue(vm.surfaces.value.isEmpty())
        assertTrue(vm.state.value.messages.last().error!!.contains("卡片无法恢复"))
    }

    @Test fun atomicArchiveIgnoresAnInterruptedTemporaryWrite() = runBlocking {
        val file = File(compose.activity.cacheDir, "conversation-atomic-${java.util.UUID.randomUUID()}.json")
        val partial = File(file.path + ".new")
        try {
            val archive = ConversationArchive().select(SavedConversation("last-good"))
            FileConversationRepository(file).save(archive)
            partial.writeText("partial replacement")
            assertEquals(archive, FileConversationRepository(file).load())
            assertTrue(runCatching { FileConversationRepository(file).save(archive.copy(version = 99)) }.isFailure)
            assertEquals(archive, FileConversationRepository(file).load())
        } finally { file.delete(); partial.delete() }
    }

    @Test fun saveFailureBlocksNavigationAndNetworkButKeepsMessages() {
        val memory = MemoryConversationRepository()
        var fail = false; var calls = 0
        val repo = object : ConversationRepository {
            override suspend fun load() = memory.load()
            override suspend fun save(archive: ConversationArchive) {
                if (fail) throw java.io.IOException("disk full")
                memory.save(archive)
            }
        }
        attach(repo, client { calls++; """{"text":"已完成","a2uiMessages":[]}""" }); send("保留")
        val id = vm.state.value.conversationId
        fail = true
        compose.runOnUiThread { vm.newChat() }; idle()
        assertEquals(id, vm.state.value.conversationId); assertEquals(2, vm.state.value.messages.size)
        assertNotNull(vm.state.value.storageError)
        send("不能发送")
        assertEquals(1, calls)
        fail = false
        compose.runOnUiThread { vm.flush() }
        compose.waitUntil { vm.state.value.storageError == null }
    }

    @Test fun delayedSavesCannotResurrectTheReplacedConversation() = runBlocking {
        val memory = MemoryConversationRepository()
        val repo = object : ConversationRepository {
            override suspend fun load() = memory.load()
            override suspend fun save(archive: ConversationArchive) { delay(50); memory.save(archive) }
        }
        attach(repo); send("旧对话")
        val oldId = vm.state.value.conversationId
        compose.runOnUiThread { vm.setDraft("旧草稿"); vm.flush(); vm.newChat() }
        idle()
        val newId = vm.state.value.conversationId
        assertNotEquals(oldId, newId)
        compose.runOnUiThread { vm.setDraft("新草稿") }
        compose.waitUntil { memory.archive.conversations.single().draft == "新草稿" }
        delay(600)
        assertEquals(newId, memory.archive.currentId)
        assertEquals(listOf(newId), memory.archive.conversations.map { it.id })
    }

    @Test fun corruptAndUnknownArchivesArePreservedUntilReadable() = runBlocking {
        val file = File(compose.activity.cacheDir, "conversation-test-${java.util.UUID.randomUUID()}.json")
        try {
            for (bytes in listOf("broken json", "{\"version\":99}")) {
                file.writeText(bytes)
                val repo = FileConversationRepository(file)
                attach(repo)
                assertTrue(vm.state.value.storageBlocked)
                compose.runOnUiThread { vm.send("禁止覆盖"); vm.newChat(); vm.flush() }
                assertEquals(bytes, file.readText())
            }
            val repo = FileConversationRepository(file)
            val valid = ConversationArchive().select(SavedConversation("saved", favorite = true))
            repo.save(valid)
            assertEquals(valid, FileConversationRepository(file).load())
            compose.runOnUiThread { vm.reloadSaved() }
            compose.waitUntil { !vm.state.value.loading }
            assertFalse(vm.state.value.storageBlocked)
            assertEquals("saved", vm.state.value.conversationId)
        } finally { file.delete() }
    }
}
