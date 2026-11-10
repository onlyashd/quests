package org.ferabrasil.quests.protocol.model

// protocol.md §11. Names are part of the wire format (JSON and signed payloads): never rename.

enum class VerificationMode { MANUAL_REVIEW, SECRET_PREIMAGE }

enum class ProofFormat { IMAGE, TEXT, IMAGE_TEXT, PREIMAGE }

enum class QuestStatus { PUBLISHED, PAUSED, TRANSFER_PENDING, CLOSED, EXPIRED }

enum class ClaimStatus { CLAIMED, SUBMITTED, APPROVED, REJECTED, EXPIRED, ABANDONED }

enum class SubmissionStatus { PENDING, APPROVED_PENDING_SIGNATURE, APPROVED, REJECTED, SUPERSEDED }

enum class LeaderStatus { ACTIVE, REVOKED }
