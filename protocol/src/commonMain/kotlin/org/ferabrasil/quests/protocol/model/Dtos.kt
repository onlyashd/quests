package org.ferabrasil.quests.protocol.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// protocol.md §4.1, §11. JSON uses snake_case; Kotlin uses camelCase.

@Serializable
data class AdminSignature(
    @SerialName("admin_pubkey") val adminPubkey: String,
    @SerialName("sig") val sig: String,
)

@Serializable
data class AdminSet(
    @SerialName("version") val version: Int,
    @SerialName("threshold") val threshold: Int,
    @SerialName("admins") val admins: List<String>,
    /** By ≥ M admins of set `version - 1`; empty for genesis. */
    @SerialName("signatures") val signatures: List<AdminSignature>,
)

@Serializable
data class QuestTransfer(
    @SerialName("seq") val seq: Int,
    @SerialName("from_leader_pubkey") val fromLeaderPubkey: String,
    @SerialName("to_leader_pubkey") val toLeaderPubkey: String,
    @SerialName("signatures") val signatures: List<AdminSignature>,
)

@Serializable
data class QuestDto(
    @SerialName("id") val id: String,
    @SerialName("leader_pubkey") val leaderPubkey: String,
    @SerialName("leader_enc_pubkey") val leaderEncPubkey: String,
    @SerialName("voucher_key_id") val voucherKeyId: String,
    @SerialName("title") val title: String,
    @SerialName("description") val description: String,
    @SerialName("verification_mode") val verificationMode: VerificationMode,
    @SerialName("secret_preimage_hash") val secretPreimageHash: String?,
    @SerialName("max_participants") val maxParticipants: Int?,
    @SerialName("reward_units") val rewardUnits: Int,
    @SerialName("status") val status: QuestStatus,
    @SerialName("slots_remaining") val slotsRemaining: Int?,
    @SerialName("expires_at") val expiresAt: Long?,
    @SerialName("signature") val signature: String,
    /** Ordered by seq; see protocol.md §11.1. */
    @SerialName("transfers") val transfers: List<QuestTransfer> = emptyList(),
)

/** Error body for every non-2xx response (backend.md §5). */
@Serializable
data class ErrorDto(
    @SerialName("error") val error: String,
    @SerialName("message") val message: String? = null,
)

/** Generic cursor-paginated list (backend.md §5). */
@Serializable
data class Page<T>(
    @SerialName("items") val items: List<T>,
    @SerialName("next_cursor") val nextCursor: String? = null,
)
