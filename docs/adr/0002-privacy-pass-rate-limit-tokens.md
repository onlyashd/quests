# 0002. Rate-limit claims with Privacy Pass blind RSA tokens

- **Status:** Accepted
- **Date:** 2026-11-09 (source spec Decision D1)
- **Specs:** [protocol §6](../specs/protocol.md#6-privacy-pass-rate-limit-tokens-decision-d1), [backend §4](../specs/backend.md#4-database-schema-postgresql)

## Context

Claims must be rate-limited, or a single person could take every slot of every quest. The
obvious rate limiter is a per-user counter, but that needs a stable identifier on each claim,
which links all of a participant's quests together: the exact property the system exists to
prevent. Each claim is made with a fresh per-quest key (protocol §3), so the server has nothing
stable to count.

The source specification cited "RFC 9505" for Privacy Pass. That RFC is a survey of censorship
techniques. The relevant documents are RFC 9576 (architecture), RFC 9577 (HTTP authentication)
and RFC 9578 (issuance).

## Options considered

1. **Per-user counters.** Simple, but links every claim to an identity.
2. **Proof of work.** Unlinkable, but punishes phones and barely slows down servers.
3. **Privacy Pass, VOPRF (type `0x0001`).** Unlinkable and cheap, but requires a P-384 group
   implementation in Kotlin/Native: a second piece of novel crypto.
4. **Privacy Pass, blind RSA (type `0x0002`).** Unlinkable; reuses the RFC 9474 implementation we
   need anyway for reward vouchers ([0003](0003-points-as-chaumian-ecash-vouchers.md)).

## Decision

Claims are authorized by single-use Privacy Pass tokens of type `0x0002` (Blind RSA 2048,
RSABSSA-SHA384-PSS-Deterministic), in the joint Origin–Issuer model with the backend in both roles.

- Tokens use an empty `redemption_context`, so a token reveals nothing about the quest it will
  be spent on.
- The token travels in `Authorization: PrivateToken` inside the OHTTP request, and its nonce is
  bound into the claim signature.
- The server keeps a spent-set of `sha256(token)` per issuer-key epoch.
- Clients fetch a whole epoch's batch at a random early moment, so issuance timing doesn't
  announce an upcoming claim.
- Issuer keys rotate every epoch (default 7 days, 10 tokens per epoch, both configurable).

## Consequences

- One audited blind-RSA implementation serves both tokens and vouchers.
- Blinding doesn't limit how many tokens a person can *obtain*. That needs a separate issuance
  gate: [0006](0006-invite-codes-as-sybil-gate.md).
- RSA tokens are larger and slower than VOPRF tokens; acceptable at the expected claim volume.
- Spent-sets grow with usage until their key expires, and must be purged on rotation.
