package com.xpl.a2uichat.games.script

import com.xpl.a2uichat.games.GameKey
import com.xpl.a2uichat.games.PlayableSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

data class ScriptFrame(val program: ScriptProgram? = null, val snapshot: ScriptSnapshot? = null,
    val paused: Boolean = true, val computing: Boolean = false, val error: String? = null, val pending: JsonObject? = null, val moves: Int = 0)

/** Main-thread owned snapshots. In-flight results are never authoritative until epoch checked. */
class ScriptGameSession(override val key: GameKey, source: JsonObject) : PlayableSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableFrame = MutableStateFlow(ScriptFrame())
    val frame = mutableFrame.asStateFlow()
    private var activeSource = source
    fun activeRules(): JsonObject = activeSource
    private var job: Job? = null
    private var epoch = 0
    private var seed = 1
    private var botChain = 0
    init { restart() }
    fun offer(source: JsonObject) { mutableFrame.value = frame.value.copy(pending = source.takeIf { it != activeSource }) }
    override fun pause() {
        epoch++; job?.cancel(); job = null
        mutableFrame.value = frame.value.copy(paused = true,computing = false)
    }
    override fun resume() {
        if (frame.value.snapshot == null) { restart(); return }
        botChain = 0
        mutableFrame.value = frame.value.copy(paused = false,error = null)
        computer()
    }
    fun restart(apply: Boolean = false) {
        pause()
        val candidate = if (apply) frame.value.pending ?: activeSource else activeSource
        val generation = epoch
        seed++
        botChain = 0
        mutableFrame.value = frame.value.copy(computing = true,error = null)
        job = scope.launch {
            try {
                val program = withContext(Dispatchers.Default) { ScriptProgram.parse(candidate) }
                ScriptSandbox.shared().validate(program)
                val snapshot = ScriptSandbox.shared().execute(program,"init",seed = seed)
                if (epoch == generation) {
                    activeSource = candidate
                    mutableFrame.value = ScriptFrame(program,snapshot,pending = frame.value.pending?.takeIf { it != candidate })
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == generation) fail(e) }
        }
    }
    fun send(action: JsonObject) {
        val f = frame.value; val s = f.snapshot ?: return; val p = f.program ?: return
        if (f.paused || f.computing || action !in s.actions || s.view.phase == "finished" ||
            (s.view.phase == "playing" && s.view.turn == p.botPlayer)) return
        botChain = 0
        execute("step",action)
    }
    private fun computer() {
        val f = frame.value; val s = f.snapshot ?: return
        if (!f.paused && !f.computing && s.view.phase == "playing" && s.view.turn == f.program?.botPlayer) {
            if (botChain >= 8) fail(IllegalStateException("电脑连续行动达到 8 次上限，已暂停；请检查回合规则"))
            else { botChain++; execute("bot",null) }
        }
    }
    private fun execute(op: String, action: JsonObject?) {
        val f = frame.value; val snapshot = f.snapshot ?: return; val program = f.program ?: return
        val generation = epoch
        mutableFrame.value = f.copy(computing = true)
        job = scope.launch {
            try {
                val result = ScriptSandbox.shared().execute(program,op,snapshot.state,action,seed)
                if (generation == epoch) {
                    checkScript(op != "bot" || result.state != snapshot.state, "电脑未推进棋局")
                    mutableFrame.value = frame.value.copy(snapshot = result,computing = false,moves = f.moves + if (result.accepted) 1 else 0)
                    // Schedule subsequent turns without recursive coroutine starts.
                    yield(); if (generation == epoch) computer()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == epoch) fail(e) }
        }
    }
    private fun fail(e: Exception) { mutableFrame.value = frame.value.copy(paused = true,computing = false,error = e.message?.take(300) ?: "脚本执行失败") }
    override fun result(): Map<String, Any> = mapOf("game" to "scriptgame", "moves" to frame.value.moves,
        "score" to (frame.value.snapshot?.view?.score ?: 0), "status" to (frame.value.snapshot?.view?.status ?: "未开始"))
    override fun dispose() { pause(); scope.cancel() }
}
