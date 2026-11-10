package org.ferabrasil.quests.domain.net

import org.ferabrasil.quests.protocol.model.ErrorCode
import org.ferabrasil.quests.protocol.model.ErrorDto

/** What the UI should do about a failed request (app.md §7.3). Each app renders these in its own stack. */
enum class ApiFailure {
    /** Relay unreachable / timeout: offline banner, retry with backoff. */
    OFFLINE,
    /** 401: resync clock offset from `Date` and retry once. */
    RESYNC_CLOCK_AND_RETRY,
    /** 402 / 429: "no claim tokens left until <date>". */
    NO_TOKENS,
    /** 409 duplicate claim: treat as success, fetch the existing claim via status lookup. */
    ALREADY_DONE,
    /** 410: mark the quest locally and drop it from the feed. */
    QUEST_GONE,
    FORBIDDEN,
    NOT_FOUND,
    TOO_LARGE,
    /** 400 / 5xx / anything unexpected: a bug on one side; show a generic error. */
    UNEXPECTED,
    ;

    companion object {
        fun from(httpStatus: Int, body: ErrorDto?): ApiFailure {
            val code = body?.let { ErrorCode.fromWire(it.error) }
            return when {
                code == ErrorCode.ALREADY_CLAIMED || code == ErrorCode.ALREADY_SUBMITTED -> ALREADY_DONE
                httpStatus == 401 -> RESYNC_CLOCK_AND_RETRY
                httpStatus == 402 || httpStatus == 429 -> NO_TOKENS
                httpStatus == 403 -> FORBIDDEN
                httpStatus == 404 -> NOT_FOUND
                httpStatus == 410 -> QUEST_GONE
                httpStatus == 413 -> TOO_LARGE
                else -> UNEXPECTED
            }
        }
    }
}
