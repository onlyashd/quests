# Architecture Decision Records

Each record explains one significant decision: the context, the options, what we chose and what
it costs. The specs in [`../specs/`](../specs) describe the system as it is; these records explain
*why*. See [0001](0001-record-architecture-decisions.md) for the rules.

| # | Decision | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-privacy-pass-rate-limit-tokens.md) | Rate-limit claims with Privacy Pass blind RSA tokens (D1) | Accepted |
| [0003](0003-points-as-chaumian-ecash-vouchers.md) | Reward points as Chaumian e-cash vouchers, spent through a separate redeemer (D2) | Accepted |
| [0004](0004-ohttp-through-cloudflare-relay.md) | Reach the backend only through Oblivious HTTP, with Cloudflare as relay (D3) | Accepted |
| [0005](0005-distribute-outside-app-stores.md) | Distribute the apps outside the app stores (D4) | Accepted |
| [0006](0006-invite-codes-as-sybil-gate.md) | Gate token issuance with invite codes and store the invite tree | Accepted |
| [0007](0007-m-of-n-admin-co-signing.md) | Require M-of-N admin signatures for privileged actions | Accepted |
| [0008](0008-leader-revocation-transfers-quests.md) | On leader revocation, transfer open quests and keep issued points valid | Accepted |
| [0009](0009-versioned-quest-key-label.md) | Use a versioned label for quest key derivation | Accepted |
| [0010](0010-shared-business-logic-native-ui.md) | Share business logic in Kotlin Multiplatform; keep UI native per platform | Accepted |
| [0011](0011-android-min-sdk-30.md) | Require Android 11 (API 30) or later | Accepted |
| [0012](0012-independent-versions-per-target.md) | Version and release each target independently, as reviewed drafts | Accepted |
| [0013](0013-conventional-commits-and-branches.md) | Enforce Conventional Commits and Conventional Branch names | Accepted |

D1–D4 are the decisions numbered in the source specification (*Anonymous Quest Engine –
Technical & Architectural Specification*).

## Writing a new ADR

1. Copy [`template.md`](template.md) to `NNNN-short-title.md`, using the next free number.
2. Fill it in with status **Proposed**, and open a pull request: `docs(adr): propose NNNN <title>`.
   Discussion happens on the pull request.
3. Once agreed, set the status to **Accepted**, add the row to the table above, and update the
   affected specs (including their "Decisions log") in the same pull request.

Accepted ADRs are not edited, apart from typos and links. To change a decision, write a new ADR
and set the old one's status to `Superseded by [NNNN](NNNN-title.md)`.

**Write an ADR when** a change affects what the server or any party can learn, adds or replaces a
cryptographic primitive, adds a component, service or third party, changes how releases are made,
or is hard to reverse. When in doubt, write one: they're short.
