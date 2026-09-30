package com.xpl.a2uichat.games

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class GameEnginesTest {
    private fun board(vararg row: Int) = Board2048(cells = row.toList() + List(16 - row.size) { 0 })

    @Test fun mergesOncePerTileAndAddsScore() {
        val next = Engine2048(Random(1)).move(board(2, 2, 2, 2), Direction.Left)
        assertEquals(listOf(4, 4), next.cells.take(2))
        assertEquals(8, next.score)
        assertEquals(1, next.moves)
        assertEquals(3, next.cells.count { it != 0 })
    }

    @Test fun compressesBeforeMergingWithoutChainMerge() {
        val next = Engine2048(Random(2)).move(board(2, 0, 2, 4), Direction.Left)
        assertEquals(listOf(4, 4), next.cells.take(2))
        assertEquals(4, next.score)
    }

    @Test fun allDirectionsUseCorrectLeadingEdge() {
        val engine = Engine2048(Random(3))
        assertEquals(4, engine.move(board(2, 2), Direction.Right).cells[3])
        val vertical = Board2048(cells = List(16) { if (it == 0 || it == 4) 2 else 0 })
        assertEquals(4, engine.move(vertical, Direction.Up).cells[0])
        assertEquals(4, engine.move(vertical, Direction.Down).cells[12])
    }

    @Test fun invalidMoveDoesNotSpawnOrConsumeRandomness() {
        val first = Engine2048(Random(99))
        val other = Engine2048(Random(99))
        val initial = board(2)
        assertSame(initial, first.move(initial, Direction.Left))
        assertEquals(other.move(initial, Direction.Right), first.move(initial, Direction.Right))
    }

    @Test fun startsWithTwoTilesAndOnlyGeneratesTwoOrFour() {
        repeat(100) {
            val initial = Engine2048(Random(it)).newGame()
            assertEquals(2, initial.cells.count { n -> n != 0 })
            assertTrue(initial.cells.all { n -> n in listOf(0, 2, 4) })
        }
    }

    @Test fun victoryPausesUntilContinueAndDetectsNoMoves() {
        val engine = Engine2048(Random(1))
        val win = engine.move(board(1024, 1024), Direction.Left)
        assertTrue(win.awaitingContinue)
        assertSame(win, engine.move(win, Direction.Right))
        assertEquals(2, engine.move(win.copy(continued = true), Direction.Right).moves)
        val lost = Board2048(listOf(2,4,2,4, 4,2,4,2, 2,4,2,4, 4,2,4,2))
        assertTrue(lost.over)
        assertFalse(lost.copy(cells = lost.cells.toMutableList().apply { this[0] = 4 }).over)
    }

    @Test fun snakeRejectsReverseAndQueuesOnlyOneTurnPerTick() {
        val engine = SnakeEngine(Random(1))
        val initial = engine.newGame().copy(status = SnakeStatus.Running)
        assertSame(initial, engine.turn(initial, Direction.Left))
        val up = engine.turn(initial, Direction.Up)
        assertSame(up, engine.turn(up, Direction.Left))
        val moved = engine.tick(up)
        assertEquals(Cell(10, 9), moved.snake.first())
        assertNull(moved.queued)
        assertEquals(Direction.Left, engine.turn(moved, Direction.Left).queued)
    }

    @Test fun snakeGrowsAndFoodNeverOccupiesBody() {
        val engine = SnakeEngine(Random(2))
        val initial = engine.newGame().copy(food = Cell(11, 10), status = SnakeStatus.Running)
        val next = engine.tick(initial)
        assertEquals(4, next.snake.size)
        assertEquals(10, next.score)
        assertFalse(next.food in next.snake)
    }

    @Test fun snakeHitsWallsOrBodyButCanEnterDepartingTail() {
        val engine = SnakeEngine(Random(2))
        val initial = SnakeBoard(snake = listOf(Cell(19, 10), Cell(18, 10), Cell(17, 10)), status = SnakeStatus.Running)
        assertEquals(SnakeStatus.Lost, engine.tick(initial).status)
        val loop = SnakeBoard(snake = listOf(Cell(1,1), Cell(1,2), Cell(2,2), Cell(2,1)), status = SnakeStatus.Running)
        assertEquals(SnakeStatus.Running, engine.tick(loop).status)
        assertEquals(SnakeStatus.Lost, engine.tick(loop.copy(snake = loop.snake + Cell(3,1))).status)
    }

    @Test fun pausedSnakeDoesNotMoveAndFullBoardWins() {
        val engine = SnakeEngine(Random(4))
        val paused = engine.newGame().copy(status = SnakeStatus.Paused)
        assertSame(paused, engine.tick(paused))
        val filled = (0 until 400).map { Cell(it % 20, it / 20) }.filterNot { it == Cell(0,0) || it == Cell(1,0) }
        val almost = SnakeBoard(snake = listOf(Cell(1,0)) + filled, food = Cell(0,0), direction = Direction.Left, status = SnakeStatus.Running)
        val won = engine.tick(almost)
        assertEquals(SnakeStatus.Won, won.status)
        assertEquals(400, won.snake.size)
        assertNull(won.food)
    }
}
