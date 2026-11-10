# 0003. Reward points as Chaumian e-cash vouchers, spent through a separate redeemer

- **Status:** Accepted
- **Date:** 2026-11-09 (source spec Decision D2)
- **Specs:** [protocol §7](../specs/protocol.md#7-reward-points--chaumian-e-cash-vouchers-decision-d2), [backend §8](../specs/backend.md#8-redemption-counter-redeemer--points-spending)

## Context

Participants earn rewards for completed quests and spend them in person with leaders. A
server-side balance per person would be the simplest design, but an account accumulates every
quest that fed into it. That links a participant's quests together, and links what they earned
to where they spent it.

## Options considered

1. **Server-side balance per account.** Simple and supports leaderboards, but breaks
   unlinkability completely.
2. **Per-amount blind-signature keys** (vouchers worth 1, 5, 10, ...). Fewer vouchers, but each
   denomination splits the anonymity set.
3. **One-point bearer vouchers with blind signatures.** No account, uniform vouchers,
   double-spending checked by a counter that never learns where a voucher came from.

## Decision

- **One voucher is one point.** A quest worth *k* points yields *k* vouchers (*k* ≤ 100 by
  default). A balance is the count of unspent vouchers in the participant's own wallet.
- Vouchers are RSABSSA-SHA384-PSS-Randomized (RFC 9474) blind signatures with 3072-bit keys.
  Each leader generates one voucher key per 30-day epoch on their device. RSA keys aren't derived
  from the mnemonic, because deterministic RSA key generation isn't standardized, so they're
  backed up in the leader's encrypted vault export.
- Points are bearer tokens, spent **in person at any active leader**, including points issued by
  leaders who were later revoked.
- Double-spending is prevented by the **redeemer**, a separate service with its own database,
  credentials and OHTTP gateway. It is operated by the leaders, on infrastructure separate from
  `:server`. It doesn't trust `:server`: it verifies the admin chain from its own pinned genesis set.
- Points are accepted until 90 days after their voucher key's epoch ends.

## Consequences

- No leaderboard, running total or per-person history can exist without breaking the design.
- Losing the wallet loses the points: there is no account to recover them from.
- Points expire roughly 3–4 months after issuance. Whether that's acceptable is still an open
  question (app spec §13).
- Operating a second service is extra work, and who hosts it is still open (backend spec §13).
  Hosting it on the same provider account as `:server` would weaken the separation.
