package org.ferabrasil.quests.protocol.model

/** backend.md §5.9. [wire] is the `error` field; [httpStatus] is the response status. */
enum class ErrorCode(val wire: String, val httpStatus: Int) {
    BAD_REQUEST("bad_request", 400),
    BAD_AUTH("bad_auth", 401),
    TOKEN_REQUIRED("token_required", 402),
    TOKEN_INVALID("token_invalid", 402),
    TOKEN_SPENT("token_spent", 402),
    FORBIDDEN("forbidden", 403),
    NOT_FOUND("not_found", 404),
    ALREADY_CLAIMED("already_claimed", 409),
    ALREADY_SUBMITTED("already_submitted", 409),
    ID_CONFLICT("id_conflict", 409),
    CHUNK_OUT_OF_ORDER("chunk_out_of_order", 409),
    QUEST_UNAVAILABLE("quest_unavailable", 410),
    TOO_LARGE("too_large", 413),
    QUOTA_EXCEEDED("quota_exceeded", 429),
    INTERNAL("internal", 500),
    ;

    companion object {
        fun fromWire(wire: String): ErrorCode? = entries.firstOrNull { it.wire == wire }
    }
}
