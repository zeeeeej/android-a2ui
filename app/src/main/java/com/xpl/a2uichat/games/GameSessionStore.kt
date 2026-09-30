package com.xpl.a2uichat.games

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

data class GameKey(val surfaceId: String, val gameId: String, val type: String)

class GameSession(val key: GameKey, difficulty: Difficulty) {
    private val engine2048 = Engine2048()
    private val snakeEngine = SnakeEngine()
    var board2048 by mutableStateOf(engine2048.newGame())
        private set
    var snake by mutableStateOf(snakeEngine.newGame(difficulty))
        private set
    val isSnake get() = key.type == "SnakeGame"
    val running get() = isSnake && snake.status == SnakeStatus.Running

    fun move(direction: Direction) {
        if (isSnake) snake = snakeEngine.turn(snake, direction)
        else board2048 = engine2048.move(board2048, direction)
    }
    fun tick() { snake = snakeEngine.tick(snake) }
    fun pause() { if (running) snake = snake.copy(status = SnakeStatus.Paused, queued = null) }
    internal fun resume() {
        if (snake.status in listOf(SnakeStatus.Ready, SnakeStatus.Paused)) snake = snake.copy(status = SnakeStatus.Running)
    }
    fun restart(difficulty: Difficulty) {
        if (isSnake) snake = snakeEngine.newGame(difficulty) else board2048 = engine2048.newGame()
    }
    fun continue2048() { board2048 = board2048.copy(continued = true) }

    fun result(): Map<String, Any> = if (isSnake) mapOf(
        "game" to "snake", "score" to snake.score, "status" to snake.status.name.lowercase(),
        "length" to snake.snake.size, "moves" to snake.moves, "difficulty" to snake.difficulty.wire,
    ) else mapOf("game" to "2048", "score" to board2048.score, "moves" to board2048.moves,
        "maxTile" to board2048.maxTile,
        "status" to if (board2048.over) "lost" else if (board2048.won) "won" else "playing")
}

/** Main-thread, ViewModel-owned state. No renderer/protocol probe creates timers. */
class GameSessionStore {
    private val sessions = mutableMapOf<GameKey, GameSession>()
    fun get(key: GameKey, difficulty: Difficulty = Difficulty.Normal): GameSession =
        sessions.getOrPut(key) { GameSession(key, difficulty) }
    fun resume(session: GameSession) {
        pauseAll()
        if (sessions[session.key] === session) session.resume()
    }
    fun pauseAll() { sessions.values.forEach { it.pause() } }
    fun retain(keys: Set<GameKey>) {
        sessions.keys.filterNot { it in keys }.forEach { sessions.remove(it)?.pause() }
    }
    fun removeSurfaces(ids: Set<String>) {
        retain(sessions.keys.filterNot { it.surfaceId in ids }.toSet())
    }
    fun clear() { pauseAll(); sessions.clear() }
}

/** Owned by the sole visible renderer. Cancellation drops elapsed time; no catch-up ticks. */
internal suspend fun runSnakeClock(session: GameSession, allowed: () -> Boolean) {
    while (session.running && allowed()) {
        delay(session.snake.difficulty.tickMillis)
        if (allowed()) session.tick() else session.pause()
    }
}
