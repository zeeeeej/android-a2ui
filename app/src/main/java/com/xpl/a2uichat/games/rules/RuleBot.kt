package com.xpl.a2uichat.games.rules

import kotlin.math.abs

/** Bounded, local two-ply search using exactly the same tap rules as the player. */
object RuleBot {
    fun choose(engine: RuleEngine, state: RuleState, cancelled: () -> Boolean = { false }): RuleEvent {
        val spec = engine.spec
        val me = spec.botPlayer ?: throw RuleFault("未配置电脑对手")
        ruleCheck(state.turn == me && state.winner == null, "当前不是电脑回合")
        val budget = RuleBudget(2_000_000, System.nanoTime() + 500_000_000, cancelled = cancelled)
        data class Candidate(val event: RuleEvent, val state: RuleState, val score: Int)
        val candidates = mutableListOf<Candidate>()
        var fallback: RuleEvent? = null
        fun transition(s: RuleState, e: RuleEvent) = engine.event(s, e, RuleBudget(parent = budget))
        fun score(s: RuleState): Int = when (s.winner) {
            me -> 1_000_000_000
            0 -> 0
            null -> engine.evaluate(s, spec.evaluation!!, RuleBudget(parent = budget)).integer().coerceIn(-100_000_000, 100_000_000)
            else -> -1_000_000_000
        }
        // Center-first ordering also gives a useful, already-validated fallback on small budgets.
        fun events(s: RuleState): List<RuleEvent> = s.cells.indices.filter { s.cells[it] == 0 }.sortedBy { i ->
            abs(i % spec.width * 2 - spec.width + 1) + abs(i / spec.width * 2 - spec.height + 1)
        }.map { RuleEvent("tap", it % spec.width, it / spec.width) }
        try {
            for (event in events(state)) {
                val next = transition(state, event)
                if (!next.accepted) continue
                if (fallback == null) fallback = event
                if (next.state.winner == me) return event
                candidates += Candidate(event, next.state, score(next.state))
            }
            val best = candidates.sortedByDescending { it.score }.take(12)
            var bestScore = Int.MIN_VALUE
            for (candidate in best) {
                var worst = candidate.score
                if (candidate.state.winner == null && candidate.state.turn != me) {
                    val replies = mutableListOf<Int>()
                    for (reply in events(candidate.state)) {
                        val next = transition(candidate.state, reply)
                        if (next.accepted) replies += score(next.state)
                    }
                    // At most 12 evaluated reply positions participate in the second ply.
                    worst = replies.sorted().take(12).minOrNull() ?: candidate.score
                }
                if (worst > bestScore) { bestScore = worst; fallback = candidate.event }
            }
        } catch (fault: RuleFault) {
            if (cancelled()) throw fault
            // A budget stop is recoverable only after finding a valid move. Other rule errors are not.
            if (fault !is RuleBudgetFault) throw fault
            if (candidates.isNotEmpty()) return candidates.maxBy { it.score }.event
        }
        return fallback ?: throw RuleFault("电脑未能在预算内找到合法落子，已暂停")
    }
}
