package org.ferabrasil.quests.domain.quests

import org.ferabrasil.quests.protocol.model.QuestDto
import org.ferabrasil.quests.protocol.model.QuestStatus
import org.ferabrasil.quests.protocol.model.VerificationMode

/** Why the Claim button is disabled (app.md §5.2 Quest detail). Ordered by precedence. */
enum class ClaimBlocker { NOT_ENROLLED, NOT_PUBLISHED, EXPIRED, NO_SLOTS, NO_TOKENS, ALREADY_CLAIMED }

sealed interface ClaimEligibility {
    data object Eligible : ClaimEligibility
    data class Blocked(val reason: ClaimBlocker) : ClaimEligibility
}

object QuestRules {
    fun claimEligibility(
        quest: QuestDto,
        nowEpochSeconds: Long,
        enrolled: Boolean,
        tokensRemaining: Int,
        alreadyClaimed: Boolean,
    ): ClaimEligibility {
        val reason = when {
            alreadyClaimed -> ClaimBlocker.ALREADY_CLAIMED
            !enrolled -> ClaimBlocker.NOT_ENROLLED
            quest.status != QuestStatus.PUBLISHED -> ClaimBlocker.NOT_PUBLISHED
            isExpired(quest, nowEpochSeconds) -> ClaimBlocker.EXPIRED
            quest.slotsRemaining == 0 -> ClaimBlocker.NO_SLOTS
            tokensRemaining <= 0 -> ClaimBlocker.NO_TOKENS
            else -> null
        }
        return if (reason == null) ClaimEligibility.Eligible else ClaimEligibility.Blocked(reason)
    }

    fun isExpired(quest: QuestDto, nowEpochSeconds: Long): Boolean =
        quest.status == QuestStatus.EXPIRED || (quest.expiresAt?.let { it <= nowEpochSeconds } ?: false)
}

/** On-device search & filter (app.md §5.2, C6): the server only ever serves the full list. */
data class QuestFilter(
    val query: String = "",
    val mode: VerificationMode? = null,
    val claimableOnly: Boolean = false,
)

object QuestSearch {
    fun apply(quests: List<QuestDto>, filter: QuestFilter, nowEpochSeconds: Long): List<QuestDto> {
        val terms = normalize(filter.query).split(' ').filter { it.isNotEmpty() }
        return quests.filter { q ->
            (filter.mode == null || q.verificationMode == filter.mode) &&
                (!filter.claimableOnly || isOpen(q, nowEpochSeconds)) &&
                (terms.isEmpty() || normalize(q.title + " " + q.description).let { text -> terms.all { it in text } })
        }
    }

    private fun isOpen(q: QuestDto, now: Long) =
        q.status == QuestStatus.PUBLISHED && !QuestRules.isExpired(q, now) && q.slotsRemaining != 0

    /** Lowercase, accent-insensitive (pt-BR users type "missao" for "missão"), whitespace-collapsed. */
    internal fun normalize(s: String): String = buildString(s.length) {
        var lastSpace = true
        for (c in s.lowercase()) {
            val mapped = FOLD[c] ?: c
            if (mapped.isWhitespace()) {
                if (!lastSpace) append(' ')
                lastSpace = true
            } else {
                append(mapped)
                lastSpace = false
            }
        }
    }.trimEnd()

    private val FOLD: Map<Char, Char> = buildMap {
        "áàâãäå".forEach { put(it, 'a') }
        "éèêë".forEach { put(it, 'e') }
        "íìîï".forEach { put(it, 'i') }
        "óòôõö".forEach { put(it, 'o') }
        "úùûü".forEach { put(it, 'u') }
        put('ç', 'c')
        put('ñ', 'n')
    }
}
