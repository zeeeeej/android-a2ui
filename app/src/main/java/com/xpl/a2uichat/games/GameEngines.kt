package com.xpl.a2uichat.games

import kotlin.random.Random

enum class Direction(val dx: Int, val dy: Int, val label: String, val arrow: String) {
    Up(0, -1, "向上", "↑"), Down(0, 1, "向下", "↓"),
    Left(-1, 0, "向左", "←"), Right(1, 0, "向右", "→");
    fun opposite(other: Direction) = dx + other.dx == 0 && dy + other.dy == 0
}

data class Board2048(
    val cells: List<Int> = List(16) { 0 },
    val score: Int = 0,
    val moves: Int = 0,
    val continued: Boolean = false,
) {
    val maxTile get() = cells.maxOrNull() ?: 0
    val won get() = maxTile >= 2048
    val awaitingContinue get() = won && !continued && !over
    val over get() = cells.none { it == 0 } && cells.indices.none { i ->
        (i % 4 < 3 && cells[i] == cells[i + 1]) || (i / 4 < 3 && cells[i] == cells[i + 4])
    }
}

/** Pure rules: randomness is injected; invalid moves neither spawn nor consume RNG. */
class Engine2048(private val random: Random = Random.Default) {
    fun newGame(): Board2048 = spawn(spawn(Board2048()))

    fun move(board: Board2048, direction: Direction): Board2048 {
        if (board.over || board.awaitingContinue) return board
        val cells = board.cells.toMutableList()
        var gained = 0
        repeat(4) { line ->
            val indices = List(4) { offset ->
                when (direction) {
                    Direction.Left -> line * 4 + offset
                    Direction.Right -> line * 4 + 3 - offset
                    Direction.Up -> offset * 4 + line
                    Direction.Down -> (3 - offset) * 4 + line
                }
            }
            val values = indices.map { board.cells[it] }.filter { it != 0 }
            val merged = mutableListOf<Int>()
            var i = 0
            while (i < values.size) {
                if (i + 1 < values.size && values[i] == values[i + 1]) {
                    val value = values[i] * 2
                    merged += value
                    gained += value
                    i += 2
                } else merged += values[i++]
            }
            indices.forEachIndexed { index, cell -> cells[cell] = merged.getOrElse(index) { 0 } }
        }
        if (cells == board.cells) return board
        return spawn(board.copy(cells = cells, score = board.score + gained, moves = board.moves + 1))
    }

    private fun spawn(board: Board2048): Board2048 {
        val empty = board.cells.indices.filter { board.cells[it] == 0 }
        if (empty.isEmpty()) return board
        val cells = board.cells.toMutableList()
        cells[empty[random.nextInt(empty.size)]] = if (random.nextInt(10) == 0) 4 else 2
        return board.copy(cells = cells)
    }
}

enum class Difficulty(val wire: String, val label: String, val tickMillis: Long) {
    Easy("easy", "简单", 300), Normal("normal", "普通", 200), Hard("hard", "困难", 120);
    companion object { fun from(value: String?) = entries.firstOrNull { it.wire == value } ?: Normal }
}
enum class SnakeStatus(val label: String) { Ready("准备开始"), Running("进行中"), Paused("已暂停"), Lost("游戏结束"), Won("已通关") }
data class Cell(val x: Int, val y: Int)
data class SnakeBoard(
    val snake: List<Cell> = listOf(Cell(10, 10), Cell(9, 10), Cell(8, 10)),
    val food: Cell? = null,
    val direction: Direction = Direction.Right,
    val queued: Direction? = null,
    val score: Int = 0,
    val moves: Int = 0,
    val difficulty: Difficulty = Difficulty.Normal,
    val status: SnakeStatus = SnakeStatus.Ready,
)

class SnakeEngine(private val random: Random = Random.Default) {
    fun newGame(difficulty: Difficulty = Difficulty.Normal): SnakeBoard {
        val board = SnakeBoard(difficulty = difficulty)
        return board.copy(food = food(board.snake))
    }

    fun turn(board: SnakeBoard, direction: Direction): SnakeBoard =
        if (board.status != SnakeStatus.Running || board.queued != null ||
            direction == board.direction || direction.opposite(board.direction)) board
        else board.copy(queued = direction)

    fun tick(board: SnakeBoard): SnakeBoard {
        if (board.status != SnakeStatus.Running) return board
        val direction = board.queued ?: board.direction
        val head = Cell(board.snake.first().x + direction.dx, board.snake.first().y + direction.dy)
        val grows = head == board.food
        val body = if (grows) board.snake else board.snake.dropLast(1)
        if (head.x !in 0..19 || head.y !in 0..19 || head in body) {
            return board.copy(status = SnakeStatus.Lost, queued = null)
        }
        val snake = listOf(head) + body
        return board.copy(snake = snake, direction = direction, queued = null,
            food = if (grows) food(snake) else board.food,
            score = board.score + if (grows) 10 else 0, moves = board.moves + 1,
            status = if (snake.size == 400) SnakeStatus.Won else SnakeStatus.Running)
    }

    private fun food(snake: List<Cell>): Cell? {
        val occupied = snake.toHashSet()
        val empty = (0 until 400).map { Cell(it % 20, it / 20) }.filterNot { it in occupied }
        return if (empty.isEmpty()) null else empty[random.nextInt(empty.size)]
    }
}
