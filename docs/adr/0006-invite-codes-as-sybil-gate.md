# 0006. Gate token issuance with invite codes and store the invite tree

- **Status:** Accepted
- **Date:** 2026-11-09
- **Specs:** [protocol §6.4](../specs/protocol.md#64-issuance-gate-attester--decided-invite-codes-2026-11-09), [backend §4](../specs/backend.md#4-database-schema-postgresql)

## Context

Blind tokens ([0002](0002-privacy-pass-rate-limit-tokens.md)) hide *who* spends a token, but
something still has to limit *how many* tokens a person can get. Otherwise a script generates a
million root keys and claims every slot. The source spec left this open. Device attestation is
ruled out by distribution outside the stores ([0005](0005-distribute-outside-app-stores.md)).

## Options considered

1. **Device attestation** (Play Integrity / App Attest). Unavailable when sideloading.
2. **Phone or email verification.** Collects exactly the identifiers we refuse to hold.
3. **Single-use invite codes, without recording who invited whom.** Abuse can't be traced back
   or revoked by branch.
4. **Single-use invite codes, storing the invite tree.** Abuse is containable; the server learns
   the social graph of root keys.

## Decision

- A root key can obtain tokens only after redeeming a single-use **invite code** minted by a
  leader or an admin.
- Each enrolled root key may obtain at most `TOKENS_PER_EPOCH` tokens per issuer epoch.
- The server stores the **full invite tree**: who minted each invite, which invite each root key
  used, and what that key's owner minted later. Admins can revoke a whole branch when abuse is
  detected.

## Consequences

- A Sybil identity costs one invite, and abuse can be cut off at its source.
- **Accepted trade-off:** the server learns *who invited whom*, as root keys rather than real
  identities. It still can't link a root key to any quest key, claim or submission. A database
  compromise reveals this graph.
- Onboarding needs an existing member, which fits a community-run deployment.
