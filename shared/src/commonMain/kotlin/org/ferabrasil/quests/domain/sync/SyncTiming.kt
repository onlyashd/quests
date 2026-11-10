package org.ferabrasil.quests.domain.sync

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Timing hygiene (app.md §7.3, §7.4, protocol §6.5). Pure functions over an injected [Random]
 * so the platform schedulers (WorkManager / BGAppRefreshTask) only decide *when to wake*.
 */
class SyncTiming(private val random: Random = Random.Default) {
    /** Next foreground status sync: uniform in [30 min, 90 min]. */
    fun nextStatusSync(): Duration = uniform(STATUS_SYNC_MIN, STATUS_SYNC_MAX)

    /** Gap between two per-claim status requests: uniform in [2 s, 20 s]. Never batch prefixes. */
    fun gapBetweenClaimPolls(): Duration = uniform(CLAIM_GAP_MIN, CLAIM_GAP_MAX)

    /** Offset into a new token epoch at which to request the batch: uniform in [0, 24 h). */
    fun tokenRefillOffset(): Duration = uniform(Duration.ZERO, TOKEN_REFILL_WINDOW)

    /** Retry delay after [attempt] consecutive relay failures (0-based): full-jitter exponential, max 5 min. */
    fun backoff(attempt: Int): Duration {
        val ceiling = BACKOFF_BASE * (1L shl attempt.coerceIn(0, 20)).toDouble()
        return uniform(Duration.ZERO, minOf(ceiling, BACKOFF_MAX))
    }

    /** Status polling stops 14 days after a final status. */
    fun shouldStillPoll(sinceFinalStatus: Duration?): Boolean =
        sinceFinalStatus == null || sinceFinalStatus < STOP_POLLING_AFTER

    private fun uniform(min: Duration, max: Duration): Duration {
        val span = (max - min).inWholeMilliseconds
        return if (span <= 0) min else min + random.nextLong(span + 1).milliseconds
    }

    companion object {
        val STATUS_SYNC_MIN = 30.minutes
        val STATUS_SYNC_MAX = 90.minutes
        val CLAIM_GAP_MIN = 2.seconds
        val CLAIM_GAP_MAX = 20.seconds
        val TOKEN_REFILL_WINDOW = 24.hours - 1.milliseconds
        val BACKOFF_BASE = 1.seconds
        val BACKOFF_MAX = 5.minutes
        val STOP_POLLING_AFTER = (14 * 24).hours
    }
}
