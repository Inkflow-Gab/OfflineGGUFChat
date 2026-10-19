package com.offlinegguf.chat.inference

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Bounds the text handed to the tokenizer on every turn.
 *
 * The native KV cache already drops old tokens to fit, but tokenizing a years-long
 * transcript before that happens is needless first-token latency. This keeps the
 * newest user/assistant groups and leaves room for the answer. It is an estimate,
 * not the model's tokenizer; the native context remains the final safety boundary.
 */
object PromptWindow {
    data class Result(
        val turns: List<ChatTurn>,
        val droppedTurns: Int,
        val clippedLatestTurn: Boolean,
        val estimatedPromptTokens: Int,
    )

    fun fit(
        system: String,
        turns: List<ChatTurn>,
        contextTokens: Int,
        outputTokens: Int,
    ): Result {
        if (turns.isEmpty() || contextTokens <= 0) {
            return Result(turns, 0, false, estimate(system) + turns.sumOf { estimate(it.content) })
        }

        val outputReserve = min(outputTokens.coerceAtLeast(0), max(128, contextTokens / 2))
        val safety = max(64, contextTokens / 12)
        val promptBudget = max(128, contextTokens - outputReserve - safety)
        val systemCost = estimate(system) + 24 // role/template markers
        val turnsBudget = max(64, promptBudget - systemCost)
        val groups = groupByUserTurn(turns)

        val keptGroupsReversed = ArrayList<List<ChatTurn>>()
        var used = 0
        var clipped = false
        for (group in groups.indices.reversed()) {
            val messages = groups[group]
            val cost = messages.sumOf { estimate(it.content) + 6 }
            if (keptGroupsReversed.isEmpty() && cost > turnsBudget) {
                // A newest user turn may contain an attached file larger than the
                // remaining context. Preserve its beginning and ending so the
                // request and the file tail both survive; make the omission visible.
                val newest = messages.last()
                val maxChars = max(128, turnsBudget * 3)
                val clippedText = clipMiddle(newest.content, maxChars)
                val clippedGroup = messages.dropLast(1) + newest.copy(content = clippedText)
                keptGroupsReversed += clippedGroup
                used += clippedGroup.sumOf { estimate(it.content) + 6 }
                clipped = clippedText != newest.content
                break
            }
            if (used + cost > turnsBudget) break
            keptGroupsReversed += messages
            used += cost
        }

        val kept = keptGroupsReversed.asReversed().flatten()
        return Result(
            turns = kept,
            droppedTurns = (turns.size - kept.size).coerceAtLeast(0),
            clippedLatestTurn = clipped,
            estimatedPromptTokens = systemCost + used,
        )
    }

    /** Each user message and its following assistant response form an indivisible history group. */
    private fun groupByUserTurn(turns: List<ChatTurn>): List<List<ChatTurn>> {
        val groups = ArrayList<List<ChatTurn>>()
        var current = ArrayList<ChatTurn>()
        turns.forEach { turn ->
            if (turn.isUser && current.isNotEmpty()) {
                groups += current
                current = ArrayList()
            }
            current += turn
        }
        if (current.isNotEmpty()) groups += current
        return groups
    }

    /** Conservative rough estimate: code and non-Latin text use more tokens per character. */
    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        val nonAscii = text.count { it.code > 0x7f }
        return ceil((text.length + nonAscii * 1.5) / 3.2).toInt()
    }

    private fun clipMiddle(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val marker = "\n[… middle of this turn omitted to fit the model context …]\n"
        val available = (maxChars - marker.length).coerceAtLeast(2)
        val head = available * 2 / 3
        val tail = available - head
        return text.take(head) + marker + text.takeLast(tail)
    }
}
