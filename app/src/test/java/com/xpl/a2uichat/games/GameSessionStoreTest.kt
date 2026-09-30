package com.xpl.a2uichat.games

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameSessionStoreTest {
    @Test fun sessionsSurviveLookupAndOnlyOneSnakeRuns() {
        val store = GameSessionStore()
        val a = store.get(GameKey("one", "root", "SnakeGame"))
        val b = store.get(GameKey("two", "root", "SnakeGame"))
        store.resume(a)
        a.tick()
        assertSame(a, store.get(a.key))
        assertEquals(1, a.snake.moves)
        store.resume(b)
        assertEquals(SnakeStatus.Paused, a.snake.status)
        assertTrue(b.running)
        store.retain(setOf(a.key))
        assertFalse(b.running)
        assertNotSame(b, store.get(b.key))
        store.clear()
        assertNotSame(a, store.get(a.key))
    }

    @Test fun injectedClockTicksOnlyWhileAllowedAndDoesNotCatchUp() = runTest {
        val store = GameSessionStore()
        val game = store.get(GameKey("one", "root", "SnakeGame"), Difficulty.Easy)
        var allowed = true
        store.resume(game)
        val job = launch { runSnakeClock(game) { allowed } }
        runCurrent()
        advanceTimeBy(301)
        assertEquals(1, game.snake.moves)
        allowed = false
        advanceTimeBy(300)
        assertEquals(1, game.snake.moves)
        assertEquals(SnakeStatus.Paused, game.snake.status)
        allowed = true
        advanceTimeBy(5000)
        assertEquals(1, game.snake.moves)
        job.join()
    }

    @Test fun restartAppliesNewDifficultyAndClearsScoreAndPendingTurn() {
        val store = GameSessionStore()
        val game = store.get(GameKey("one", "root", "SnakeGame"), Difficulty.Easy)
        store.resume(game)
        game.move(Direction.Up)
        game.pause()
        assertNull(game.snake.queued)
        game.restart(Difficulty.Hard)
        assertEquals(Difficulty.Hard, game.snake.difficulty)
        assertEquals(SnakeStatus.Ready, game.snake.status)
        assertEquals(0, game.snake.moves)
        assertEquals(0, game.result()["score"])
    }
}
