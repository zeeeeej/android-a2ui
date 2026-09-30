package com.xpl.a2uichat.games

import com.xpl.a2uichat.BuildConfig
import com.xpl.a2uichat.a2ui.AgentPrompt
import com.xpl.a2uichat.a2ui.SurfaceGuard
import com.xpl.a2uichat.games.rules.*
import com.xpl.a2uichat.network.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with RUN_LIVE_RULES=true. Uses the real prompt/schema, never logs keys or request bodies. */
class RuleLiveGenerationTest {
    @Test fun realModelGeneratesFourExecutablePrograms() = runBlocking {
        assumeTrue(System.getenv("RUN_LIVE_RULES") == "true")
        val client = DeepSeekClient(BuildConfig.DEEPSEEK_API_KEY, BuildConfig.DEEPSEEK_MODEL, BuildConfig.DEEPSEEK_URL)
        val prompts = listOf(
            "生成一个15×15的五子棋让我玩，五连获胜，使用本地电脑对手。只生成一张 RuleGame 卡片。",
            "生成俄罗斯方块让我玩，10×20棋盘。只生成一张 RuleGame 卡片。",
            "生成8×8棋盘的四子棋，四连获胜，使用本地电脑对手。只生成一张 RuleGame 卡片。",
            "生成6×12棋盘的下落方块游戏，形状仅包含三格直条与三格L形，每消一行75分。只生成一张 RuleGame 卡片。",
        )
        prompts.forEachIndexed { index, prompt ->
            val prefix = "s_live_${index}_"
            val base = listOf(ChatMessage("system", AgentPrompt.system() + "\n本轮新 surfaceId 前缀：$prefix；可更新的已有 surfaceId：。"), ChatMessage("user", prompt))
            var messages = base
            var accepted: RuleSpec? = null
            for (attempt in 0..1) {
                var completed: AgentEvent.Complete? = null
                try {
                    client.reply(messages, stream = index % 2 == 1).collect { if (it is AgentEvent.Complete) completed = it }
                    val reply = completed ?: error("No complete reply")
                    val guard = SurfaceGuard()
                    reply.reply.a2uiMessages.forEach { guard.accept(it, prefix) }
                    guard.validateComplete()
                    val components = reply.reply.a2uiMessages.flatMap { it["updateComponents"]?.jsonObject?.get("components")?.jsonArray.orEmpty() }
                    val component = components.map { it.jsonObject }.single { it["component"]?.jsonPrimitive?.content == "RuleGame" }
                    accepted = RuleSpec.parse(component.getValue("spec").jsonObject)
                    break
                } catch (e: Exception) {
                    if (e !is RuleFault && (e !is ReplyFormatException || !e.containsRuleGame)) throw e
                    if (attempt == 1) throw e
                    val raw = (e as? ReplyFormatException)?.partialReply ?: completed!!.raw
                    messages = base + ChatMessage("assistant", raw) + ChatMessage("user", "规则校验失败：${e.message}。请修复后重新输出完整回复 JSON，最多修复一次。")
                }
            }
            val spec = accepted ?: error("No valid rule spec")
            val folder = File("build/outputs/verification/rules-live").apply { mkdirs() }
            File(folder, "program-$index.json").writeText(spec.source.toString())
            val engine = RuleEngine(spec)
            var state = engine.initial(1)
            assertEquals(listOf(15,10,8,6)[index], spec.width)
            assertEquals(listOf(15,20,8,12)[index], spec.height)
            if (index % 2 == 0) {
                assertEquals(2, spec.botPlayer)
                val played = engine.event(state, RuleEvent("tap", spec.width / 2, spec.height / 2))
                assertTrue(played.accepted)
                state = played.state
                val reply = engine.event(state, RuleBot.choose(engine, state))
                assertTrue(reply.accepted)
                assertEquals(1, reply.state.turn)
                // Verify the requested win length, not just the board dimensions.
                val target = if (index == 0) 5 else 4
                var game = engine.initial(1)
                repeat(target - 1) { x ->
                    game = engine.event(game, RuleEvent("tap", x, 0)).state
                    assertNull(game.winner)
                    game = engine.event(game, RuleEvent("tap", x, 2)).state
                    assertNull(game.winner)
                }
                game = engine.event(game, RuleEvent("tap", target - 1, 0)).state
                assertEquals(1, game.winner)
            } else {
                assertTrue(spec.tickMs > 0)
                assertNotNull(spec.overlay)
                repeat(spec.height + 2) { state = engine.event(state, RuleEvent("tick")).state }
                assertTrue(state.cells.any { it != 0 })
                assertNull(state.winner)
                if (index == 3) {
                    val overlay = spec.overlay!!
                    val shapes = (1L..32L).map { seed -> RuleEngine.points(engine.initial(seed).vars.getValue(overlay.shape)) }
                    assertTrue(shapes.all { it.size == 3 })
                    fun straight(shape: List<Pair<Int, Int>>) = shape.map { it.first }.distinct().size == 1 || shape.map { it.second }.distinct().size == 1
                    assertTrue(shapes.any(::straight)); assertTrue(shapes.any { !straight(it) })
                    val baseState = engine.initial(1)
                    val shape = RuleEngine.points(baseState.vars.getValue(overlay.shape))
                    val x = baseState.vars.getValue(overlay.x).jsonPrimitive.int
                    val bottom = shape.maxOf { it.second }
                    val holes = shape.filter { it.second == bottom }.map { x + it.first }.toSet()
                    val beforeClear = baseState.copy(
                        cells = List(spec.width * spec.height) { i -> if (i / spec.width == spec.height - 1 && i % spec.width !in holes) overlay.color else 0 },
                        vars = baseState.vars + (overlay.y to JsonPrimitive(spec.height - 1 - bottom)))
                    val cleared = engine.event(beforeClear, RuleEvent("tick")).state
                    assertEquals(75, cleared.vars.getValue("score").jsonPrimitive.int - baseState.vars.getValue("score").jsonPrimitive.int)
                }
            }
            println("PASS: live rule program $index (${spec.width}x${spec.height}), ${if (index % 2 == 0) "local bot" else "timed falling"}")
        }
    }
}
