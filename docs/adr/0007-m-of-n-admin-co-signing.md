# 0007. Require M-of-N admin signatures for privileged actions

- **Status:** Accepted
- **Date:** 2026-11-09
- **Specs:** [protocol §4.1](../specs/protocol.md#41-admin-set-m-of-n-co-signing--decided-2026-11-09), [backend §5.7](../specs/backend.md#57-admin-m-of-n-offline-signing)

## Context

Admins authorize leaders, and leaders issue points and see decrypted proofs. A single admin key
would be a single point of compromise: whoever holds it could appoint a fake leader and mint
points or harvest proofs. Clients and the redeemer must also be able to verify leader
authorization themselves, without trusting the server.

## Options considered

1. **One admin key.** Simple, but a single point of failure and of coercion.
2. **Threshold signatures** (FROST and similar). One compact signature, but novel cryptography
   on every platform.
3. **M-of-N ordinary Ed25519 signatures** over the same payload. Slightly larger, built only from
   primitives we already have.

## Decision

- Privileged actions need signatures from at least **M distinct members of the current admin
  set**. The defaults are M = 2, N = 3, set per admin set.
- **Needs M-of-N:** authorizing or revoking a leader, transferring a quest, certifying the config
  signing key, rotating the admin set.
- **Needs one admin** (operational, low stakes): minting invite codes, rotating issuer and OHTTP
  keys, through the admin CLI on the bastion host.
- The **genesis set** is pinned in the apps, the server config and the redeemer config. Set
  *v+1* is valid only when signed by M admins of set *v*, and verifiers walk the chain and reject
  gaps.
- `/v1/config` is signed by an online config key, whose certificate (valid at most 90 days) is
  co-signed by M admins.
- Admin private keys stay offline (hardware keys or air-gapped machines).

## Consequences

- Compromising one admin, or the server, isn't enough to authorize a leader.
- Privileged actions are slower: they need several people and offline signing.
- The genesis set must be chosen **before** it's pinned in a release (open question in the specs).
- Losing M−1 admin keys is recoverable; losing enough to fall below M is not. Key custody needs a
  documented procedure.
