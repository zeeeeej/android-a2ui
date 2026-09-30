package com.xpl.a2uichat.games.rules

import com.xpl.a2uichat.games.GameKey
import com.xpl.a2uichat.games.PlayableSession
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

data class RuleFrame(val spec: RuleSpec? = null, val state: RuleState? = null,
    val paused: Boolean = true, val computing: Boolean = false, val error: String? = null,
    val pending: RuleSpec? = null)

/** Serial, off-main execution. Epoch fencing makes pause/reset/disposal invalidate in-flight work. */
class RuleGameSession(override val key: GameKey, source: JsonObject) : PlayableSession {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableFrame = MutableStateFlow(RuleFrame())
    val frame = mutableFrame.asStateFlow()
    private var epoch = 0L
    private var closed = false
    private var offered = source
    private data class Command(val epoch: Long, val event: RuleEvent)
    private val queue = Channel<Command>(32)

    init {
        scope.launch { initialize(source) }
        scope.launch {
            for (command in queue) {
                val before = synchronized(lock) {
                    frame.value.takeIf { !closed && command.epoch == epoch && !it.paused && it.error == null }
                } ?: continue
                val spec = before.spec ?: continue
                val state = before.state ?: continue
                fun cancelled() = synchronized(lock) { closed || command.epoch != epoch }
                try {
                    val engine = RuleEngine(spec)
                    val event = if (command.event.name == "__bot") {
                        if (state.turn != spec.botPlayer || state.winner != null) continue
                        synchronized(lock) { if (!cancelled()) mutableFrame.value = frame.value.copy(computing = true) }
                        RuleBot.choose(engine, state, ::cancelled)
                    } else {
                        if (state.turn == spec.botPlayer) continue
                        command.event
                    }
                    val next = engine.event(state, event, RuleBudget(cancelled = ::cancelled))
                    synchronized(lock) {
                        if (!cancelled()) {
                            mutableFrame.value = frame.value.copy(state = next.state, computing = false)
                            if (next.accepted && next.state.winner == null && next.state.turn == spec.botPlayer) {
                                queue.trySend(Command(epoch, RuleEvent("__bot")))
                            }
                        }
                    }
                } catch (_: CancellationException) { throw CancellationException() }
                catch (e: Exception) {
                    synchronized(lock) {
                        if (!cancelled()) {
                            epoch++
                            mutableFrame.value = frame.value.copy(paused = true, computing = false,
                                error = if (e is RuleFault) e.message else "规则执行失败，已保留上一份有效状态")
                        }
                    }
                }
            }
        }
    }

    private fun initialize(source: JsonObject) {
        try {
            val spec = RuleSpec.parse(source)
            val state = RuleEngine(spec).initial()
            synchronized(lock) { if (!closed) mutableFrame.value = RuleFrame(spec = spec, state = state, pending = frame.value.pending) }
        } catch (e: Exception) {
            synchronized(lock) { if (!closed) mutableFrame.value = frame.value.copy(error = e.message ?: "初始化失败") }
        }
    }

    fun offer(source: JsonObject) {
        synchronized(lock) { if (source == offered || closed) return; offered = source }
        scope.launch {
            try {
                val spec = RuleSpec.parse(source)
                synchronized(lock) {
                    if (!closed && offered == source) mutableFrame.value = frame.value.copy(
                        pending = spec.takeUnless { it.source == frame.value.spec?.source })
                }
            } catch (e: RuleFault) {
                synchronized(lock) {
                    if (!closed && offered == source) {
                        epoch++
                        mutableFrame.value = frame.value.copy(error = e.message, paused = true, computing = false)
                    }
                }
            }
        }
    }

    fun restart(applyPending: Boolean = false) {
        val pair = synchronized(lock) {
            val spec = (if (applyPending) frame.value.pending else frame.value.spec) ?: return
            epoch++
            mutableFrame.value = frame.value.copy(paused = true, computing = true)
            spec to epoch
        }
        scope.launch {
            try {
                val next = RuleEngine(pair.first).initial()
                synchronized(lock) {
                    if (!closed && epoch == pair.second) mutableFrame.value = RuleFrame(pair.first, next,
                        pending = if (applyPending) null else frame.value.pending)
                }
            } catch (e: Exception) {
                synchronized(lock) { if (!closed && epoch == pair.second) mutableFrame.value = frame.value.copy(computing = false, error = e.message) }
            }
        }
    }

    fun send(event: RuleEvent) = synchronized(lock) {
        if (!closed && !frame.value.paused && !frame.value.computing && frame.value.error == null) queue.trySend(Command(epoch, event))
        Unit
    }
    override fun pause() = synchronized(lock) {
        epoch++
        mutableFrame.value = frame.value.copy(paused = true, computing = false)
    }
    override fun resume() = synchronized(lock) {
        val current = frame.value
        if (!closed && current.state != null && current.state.winner == null && current.error == null) {
            mutableFrame.value = current.copy(paused = false)
            if (current.state.turn == current.spec?.botPlayer) queue.trySend(Command(epoch, RuleEvent("__bot")))
        }
    }
    override fun result(): Map<String, Any> {
        val current = frame.value
        return mapOf("game" to "rulegame", "score" to ((current.state?.vars?.get("score") as? JsonPrimitive)?.intOrNull ?: 0),
            "moves" to (current.state?.moves ?: 0), "winner" to (current.state?.winner ?: -1),
            "status" to if (current.error != null) "error" else if (current.state?.winner != null) "finished" else "paused")
    }
    override fun dispose() = synchronized(lock) { closed = true; pause(); queue.close(); scope.cancel() }
}
