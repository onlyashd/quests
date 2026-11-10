# Architecture overview

A map for maintainers. The details live in the [specs](specs), and the reasons in the
[ADRs](adr).

## The promise

Participants claim quests, submit end-to-end-encrypted proof and earn points, **without an
account**. The backend is a *blind coordinator*: a full compromise of the server and its database
must reveal

- no IP addresses, user agents or device identifiers;
- no proof content;
- no link between a participant's quests;
- no link between a completed quest and the points spent later.

Every component below exists to keep one of those properties. When reviewing a change, ask which of
them it touches.

## Parties

| Party                         | Holds                                                                           | Can see                                                                                                       |
|-------------------------------|---------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| **Participant**               | 24-word mnemonic → root seed → one key per quest; wallet of point vouchers      | Their own quests and points                                                                                   |
| **Leader**                    | Leader signing key, sealing key, voucher keys (backed up in an encrypted vault) | Decrypted proofs of *their* quests, on their device only                                                      |
| **Admin** (M of N)            | Offline admin keys                                                              | Leader authorizations, quest transfers, config certificates ([ADR 0007](adr/0007-m-of-n-admin-co-signing.md)) |
| **Relay** (Cloudflare)        | Nothing persistent                                                              | Client IPs and timing, never content ([ADR 0004](adr/0004-ohttp-through-cloudflare-relay.md))                 |
| **Server**                    | Signed quests, claims, sealed artifacts, spent-sets, invite tree                | Content of requests, never IPs                                                                                |
| **Redeemer** (run by leaders) | Spent voucher set                                                               | Spent vouchers, never where they came from ([ADR 0003](adr/0003-points-as-chaumian-ecash-vouchers.md))        |

## Components

```
 Android app (Compose) ─┐                               ┌─► :server ──► PostgreSQL
                        ├─ :shared ─ :protocol ─ OHTTP ─┤      (blind      MinIO (internal only)
 iOS app (SwiftUI) ─────┘   (KMP)     (KMP)    via relay│    coordinator)
                                                        └─► :redeemer ─► PostgreSQL (separate)
```

| Module                   | Role                                                                                                                                                            | Spec                                                                          |
|--------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| `:protocol`              | Every byte both sides must agree on: key derivation, signatures, sealed box, Privacy Pass, blind vouchers, OHTTP, DTOs. Shared by the apps **and** the backends | [protocol](specs/protocol.md)                                                 |
| `:shared`                | App business logic (domain, repositories, sync, DI). No UI ([ADR 0010](adr/0010-shared-business-logic-native-ui.md))                                            | [app §3](specs/app.md#3-module--package-structure)                            |
| `:androidApp`, `iosApp/` | Native UIs and platform services (`SecretStore`, `ImageSanitizer`, camera, ...)                                                                                 | [app §4–5](specs/app.md#4-platform-abstractions)                              |
| `:server`                | OHTTP gateway + inner API, quests, claims, uploads, submissions, token issuance, background jobs                                                                | [backend](specs/backend.md)                                                   |
| `:redeemer`              | Point spending (double-spend check). Own database, gateway and operators                                                                                        | [backend §8](specs/backend.md#8-redemption-counter-redeemer--points-spending) |

## A quest, end to end

1. **Enroll:** a participant redeems an invite code with their root key and gets a batch of blind
   Privacy Pass tokens ([ADR 0006](adr/0006-invite-codes-as-sybil-gate.md),
   [0002](adr/0002-privacy-pass-rate-limit-tokens.md)).
2. **Browse:** the app downloads signed quests and searches **locally**; the server never learns
   what was browsed. Each quest's leader is verified against the M-of-N admin chain.
3. **Claim:** the participant derives a fresh quest key, and spends one token to claim a slot.
4. **Submit:** the proof (photo, text) is stripped of metadata, sealed to the leader's key, and
   uploaded in OHTTP-sized chunks. The participant also sends blinded voucher messages.
5. **Review:** the leader decrypts on their device, approves, and blind-signs the vouchers.
6. **Collect:** the participant polls blind status, unblinds the signatures, and stores the points.
7. **Spend:** in person, at any active leader. The redeemer checks for double-spending.

Every request in steps 1–7 goes through the relay as OHTTP; the backends never see a client IP.

## Where to change things

| You want to...                 | Look in                                                                                                                 | And remember                                                  |
|--------------------------------|-------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| Change a wire format or crypto | `:protocol` + [protocol spec](specs/protocol.md)                                                                        | Update test vectors; both apps and both backends are affected |
| Change a business rule         | `:shared` `domain/` + `commonTest`                                                                                      | Tested once, used by both apps                                |
| Change a screen                | `androidApp/.../ui/` **and** `iosApp/iosApp/*View.swift`                                                                | Strings in en + pt-BR on both platforms                       |
| Add an API endpoint            | `:server` routes + [backend §5](specs/backend.md#5-api-inner-endpoints-reached-only-through-ohttp) + DTO in `:protocol` | Constant-shape responses, no identity-bearing logs            |
| Change the schema              | New Flyway migration `V<n>__*.sql`                                                                                      | Never edit merged migrations; justify every column            |
| Change a fundamental choice    | A new [ADR](adr) first                                                                                                  | Specs and ADR updated in the same pull request                |
