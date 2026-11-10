# 0008. On leader revocation, transfer open quests and keep issued points valid

- **Status:** Accepted
- **Date:** 2026-11-09
- **Specs:** [backend §6.6](../specs/backend.md#66-leader-revocation--quest-transfer-decided-2026-11-09), [protocol §11.1](../specs/protocol.md#111-quest-transfer-verification)

## Context

Leaders can leave, lose their device, or be removed for abuse. Their open quests carry
participants' work in progress. Pending proofs are sealed to the revoked leader's key, and
pending vouchers target that leader's voucher key. Points they already issued are in
participants' wallets.

## Options considered

1. **Close the quests and invalidate the leader's points.** Simple, but punishes participants
   for the leader's departure.
2. **Transfer open quests to another leader and keep issued points valid.**

## Decision

On an M-of-N revocation, in a single transaction:

- The leader is marked revoked.
- Each open quest gets a `quest_transfers` row and becomes `TRANSFER_PENDING`.
- Pending submissions become `SUPERSEDED`, because only the revoked key could decrypt them. Their
  claims reopen with a fresh TTL, and their artifacts are deleted.

The target leader re-signs each quest. Only the leader keys and the signature may change, and
clients verify the transfer chain (protocol §11.1). Participants see `SUPERSEDED` through blind
status and resubmit. Points already issued stay spendable until their key's `accept_until`.

## Consequences

- Participants keep their points, and can finish quests after a leader leaves.
- Participants with pending submissions must submit again.
- A target leader who never takes over leaves quests in `TRANSFER_PENDING`. After 14 days the
  admins are alerted, and must transfer or close the quests.
- A leader removed for abuse can't be "fined" by voiding their past issuance; that is accepted.
