# TODO: road to production

What's missing between the current skeleton (commit `386aa6e`) and a production release. The specs
in `docs/specs/` are the target; this file only tracks the gap. Items cite spec sections
(`backend.md §6.2`) and milestone IDs (`app.md §12`, `backend.md §12`). Tick items off as they
merge, and delete this file once A7 and B7 are signed off.

Legend: 🔒 privacy/security-critical · ⛔ blocks production · (both) = Android **and** iOS work.

---

## 0. Where things stand

**Done (B0 and parts of A0/A1):**
- Gradle build: `:protocol` (android, iosArm64, iosSimulatorArm64, jvm), `:shared`, `:androidApp`,
  `:server`, `:redeemer`; `iosApp/` Xcode project wired to the `Shared` framework.
- `:protocol`: hex/base64url encoding, `SigInput`, DTOs (`AdminSet`, `QuestDto`, `QuestTransfer`,
  `ErrorDto`, `Page`), enums, `ErrorCode`. Tests for encoding only.
- `:server`: config loading + validation, Hikari/Flyway, full `V1__init.sql` schema (incl.
  append-only `admin_audit`), advisory-lock `JobRunner` (no jobs registered), MinIO bucket check,
  error mapping, internal `/healthz`, admin CLI with `migrate` only.
- `:redeemer`: config, own DB + `V1__init.sql`, `/healthz`.
- `:shared`: Metro graph, `IdentityRepository` (create/load/wipe raw entropy), `PreferencesRepository`,
  `QuestRules`/`ClaimBlocker`, `ApiFailure`, `SyncTiming`, `Watchable`.
- Apps: welcome screen, tab shell with placeholders, settings (theme, leader mode, version),
  `KeystoreSecretStore` / `KeychainSecretStore`, `ScreenPrivacy` / `SecureContent`, en + pt-BR strings.
- Repo: Conventional Commits/Branch hooks and PR checks, CodeQL, release workflows, compose stack,
  Dockerfiles, 13 ADRs.

**Not started:** every cryptographic primitive, the OHTTP transport, every `/v1` endpoint, the vault,
networking in the apps, and every participant/leader feature.

---

## 1. Decisions that block production ⛔

These are open in the specs and need humans, not code.

- [ ] Confirm M = 2 / N = 3 and name the genesis admins; produce the genesis admin-set JSON to pin in
      `BuildConfig` and `GENESIS_ADMIN_SET` (`backend.md §13` #1, `app.md §13` #1). **Blocks B1/A2.**
- [ ] Sign the Cloudflare managed OHTTP relay agreement; obtain relay egress ranges or mTLS identity
      for `RELAY_ALLOWLIST`, one relay endpoint for `:server` and one for `:redeemer` (ADR 0004).
- [ ] Decide who hosts the redeemer (leaders operate it; must not share the `:server` provider
      account, `backend.md §13` #3).
- [ ] Confirm point expiry (voucher accepted until `valid_until + 90 days`, `protocol.md §7`).
- [ ] Confirm the points semantics (bearer, in person, no balance/leaderboard, `app.md §13` #2).
- [ ] Book the external crypto audit of `:protocol` and a threat-model review (`backend.md §9` S10).
- [ ] Set up offline release signing (Android key, iOS distribution) and the hardware/offline admin
      keys (`backend.md §9` S2, `releasing.md`).

### Spec gaps to close before implementing (update the spec in the same PR)

- [ ] **Sealed reject reason** format: `app.md §5.3` seals it to the participant's quest pubkey
      "converted to X25519", but `protocol.md` defines neither the Ed25519→X25519 conversion nor the
      sealing construction/AAD. Add a `protocol.md` section.
- [ ] **Invite code** format (length, alphabet, checksum, QR payload) isn't defined (`protocol.md §6.4`).
- [ ] **Spend QR** payload encoding (`spend_code`, `n`) isn't defined (`protocol.md §7.4`).
- [ ] **Release manifest** format and its signature (`backend.md §5.1`, `app.md §10`).
- [ ] **Private-key envelope encryption** with `MASTER_KEY` (algorithm, key wrapping, rotation) for
      issuer/OHTTP/config keys (`backend.md §4` `issuer_keys.private_key_enc`, §9 S2).
- [ ] **Vault export** format and its encryption key/passphrase (`app.md §5.4`, §6).
- [ ] How the redeemer obtains the admin-set chain and leader list from `:server` without sharing a
      network (`backend.md §8` "Trust"), and how `accepted_by_alias` is produced (the redeemer has no aliases).
- [ ] Name mismatch: `app.md §10` says `latest_version`, `backend.md §5.1` says `latest_app_version`.
- [ ] Broken Markdown tables in `backend.md §5.6` (`PATCH …/quests/{id}`, `GET /v1/leader/submissions`)
      and `§8` (`GET /v1/spends/{hash}`): the `|` inside enum lists splits the cells.
- [ ] `backend.md §10` still lists the `minio/minio` image; the repo uses `MINIO_IMAGE` (see AGENTS.md gotchas).

---

## 2. `:protocol`: crypto and wire formats (A0 remainder) 🔒

Critical path: almost everything else depends on it. Every hand-written primitive ships with its RFC
vectors in `commonTest`, passing on `jvmTest`, `testAndroidHostTest` **and** `iosSimulatorArm64Test`
(`protocol.md §2`, §12).

- [ ] Add and pin deps: `multiplatform-crypto-libsodium-bindings`, `dev.whyoleg.cryptography`
      (core + JDK/Apple providers), `com.ionspin.kotlin:bignum` (`protocol.md §2`). State the reason
      in the PR (AGENTS.md "Before you finish" #4).
- [ ] libsodium init on every target; CSPRNG wrapper (`randombytes_buf`).
- [ ] `crypto/Hkdf`: HKDF-SHA256 Extract/Expand. Vectors: RFC 5869 App. A.
- [ ] `crypto/Bip39`: entropy ↔ 24 words (English canonical), checksum validation, NFKD, PBKDF2-SHA512
      seed with optional passphrase; pt-BR wordlist display-only. Vectors: trezor `vectors.json`.
- [ ] `crypto/KeyDerivation`: root, quest *i* (`"AQE-v1 quest:" ‖ uuid`, frozen, ADR 0009), leader
      Ed25519, leader X25519 (clamped), vault key (`protocol.md §3.2`).
- [ ] Ed25519 sign/verify wrappers. Vectors: RFC 8032 §7.1.
- [ ] ChaCha20-Poly1305 IETF wrappers. Vectors: RFC 8439 §2.8.2.
- [ ] `crypto/SealedBox`: X25519 + HKDF key, chunked STREAM (64 KiB chunks, nonce XOR counter, AAD
      with `quest_id`/`claim_id`/index/`is_last`), streaming encrypt and decrypt in bounded memory
      (`protocol.md §5.1–5.2`). Tests: round trip, truncation, reordering, chunk swap across claims,
      all-zero shared secret.
- [ ] Envelope encode/decode with bucket padding (`max(4 KiB, 2^k)`, cap 16 MiB, `protocol.md §5.3`).
- [ ] `crypto/Rsabssa`: RFC 9474 Blind/BlindSign/Finalize/Verify, both PSS-Deterministic (2048,
      Privacy Pass) and PSS-Randomized (3072, vouchers); RSA keygen for leader voucher keys; SPKI DER
      encode/parse. Constant-time where the bignum allows. Vectors: RFC 9474 App. A.
- [ ] `privacypass/`: `TokenChallenge`, `TokenRequest`, `TokenResponse`, `Token` for type `0x0002`
      (RFC 9578 §6), `Authorization: PrivateToken` header (RFC 9577). Vectors: RFC 9578 App. A.2.
- [ ] `crypto/Hpke`: RFC 9180 base mode, suite (0x0020, 0x0001, 0x0003). Vectors: RFC 9180.
- [ ] `ohttp/`: BHTTP known-length encode/decode with padding (RFC 9292); OHTTP key config parsing
      and client/server encapsulation (RFC 9458). Vectors: RFC 9292 / 9458 App. A.
- [ ] `sign/RequestSigner` + verifier for `AQE-Auth` (`protocol.md §9`).
- [ ] Typed builders for every `SigInput` context in the `protocol.md §4` table (quest, claim,
      submission, review, request, admin_set, leader_auth, quest_transfer, config_key, config, voucher_key).
- [ ] Verifiers: admin-set chain walk from genesis, M-of-N distinct-signer check, leader
      authorization, quest transfer chain (`protocol.md §4.1`, §11.1), status tag, preimage hash.
- [ ] Remaining DTOs for every endpoint in `backend.md §5` and `§8` (config, leaders, enroll, tokens,
      claims, uploads, submissions, status, reviews, invites, transfers, spends, voucher keys).
- [ ] Golden vectors for SealedBox and every SigInput context in
      `protocol/src/commonTest/resources/vectors/*.json`, generated once and frozen (`protocol.md §12`).
- [ ] Fuzz/property tests for all decoders (BHTTP, OHTTP, envelope, SPKI, base64url/hex).

---

## 3. Backend (`:server`, `:redeemer`)

### B1: gateway, directory, admin workflow
- [ ] 🔒 OHTTP gateway: `GET /ohttp/keys`, `POST /ohttp`, HPKE decap → BHTTP → in-process inner
      dispatch → padded (4 KiB) BHTTP response → encap; `413` above 256 KiB (`backend.md §3.1`).
- [ ] 🔒 Relay allow-list (IP ranges or mTLS) on the outer endpoints.
- [ ] `auth/AqeAuth` plugin: ts window ±60 s, Postgres replay cache (`request_replay`), kid policy per
      route (Q/R/L) (`protocol.md §9`, `backend.md §5`).
- [ ] `GET /v1/config` signed by the config key + certificate; `GET /v1/admin-sets`; `GET /v1/leaders`.
- [ ] Load and verify the genesis admin set from `GENESIS_ADMIN_SET` at startup.
- [ ] Envelope encryption of private keys with `MASTER_KEY` (after the spec gap above is closed).
- [ ] Admin CLI: `propose` / `sign` / `apply` for `leader-add`, `config-key`, `admin-set`;
      `config sign`, `ohttp rotate`; every `apply` writes `admin_audit` (`backend.md §5.7`).
- [ ] Dev OHTTP relay in `server` test sources (`backend.md §10`) and compose wiring.

### B2: enrollment and tokens
- [ ] `POST /v1/enroll` (constant-time invite hash check, single use, expiry) with a global rate limit.
- [ ] `POST /v1/tokens`: quota `issued + n ≤ TOKENS_PER_EPOCH` in one transaction, RFC 9578 issuance.
- [ ] `IssuerService` key management; `admin issuer rotate`, `admin invite mint --count N`,
      `admin enrollment revoke --invite-tree <id>`.
- [ ] `KeyRotationJob`: next issuer key 24 h before the boundary, drop expired keys (cascades), OHTTP
      expiry warning metric.

### B3: quests and claims
- [ ] `POST /v1/leader/voucher-keys` (also forwards to the redeemer), `POST /v1/leader/quests`
      (signature, `kid` match, voucher key ownership + validity through `expires_at`, `409 id_conflict`,
      S3 field limits), `PATCH /v1/leader/quests/{id}`.
- [ ] `GET /v1/quests` (no filter params, cursor pagination, transfers included), `GET /v1/quests/{id}`.
- [ ] `POST /v1/quests/{id}/claims`: token verified before the transaction, spent-set insert, conditional
      `UPDATE` slot accounting, `409 already_claimed` with existing id (`backend.md §6.2`).
- [ ] `DELETE /v1/claims/{id}` (abandon, frees slot).
- [ ] `POST /v1/leader/invites` (≤ 50 hashes).

### B4: uploads, submissions, status
- [ ] `ArtifactStore`: S3 multipart with 5 MiB coalescing buffer, cap on active sessions, incremental
      SHA-256, ranged reads (`backend.md §6.3`). Nothing buffered to disk.
- [ ] `POST /v1/claims/{id}/uploads`, `PUT /v1/uploads/{uid}/chunks/{n}` (in-order, idempotent),
      `GET /v1/uploads/{uid}`.
- [ ] `POST /v1/claims/{id}/submissions`, both modes; constant-time preimage check with 5-attempt limit
      → `REJECTED` (`backend.md §6.4`); refuse while `TRANSFER_PENDING`.
- [ ] `GET /v1/status/{prefix_hex}`: range scan, always an array (`backend.md §5.5`, §5.8).
- [ ] Confirm MinIO access logging is disabled in every deployment.

### B5: review and retention
- [ ] `GET /v1/leader/submissions`, `GET …/artifact?offset=&length=` (≤ 192 KiB), `POST …/review` with
      server-side blind-signature verification (`backend.md §6.5`).
- [ ] `ExpiryJob` (quests, claims + slot release, transfer-timeout alert) and `RetentionJob` (artifact
      deletion schedule, stale uploads, replay purge, 30-day terminal purge) (`backend.md §7`).
- [ ] Register the jobs in `Application.kt` (`JobRunner(pool, jobs = emptyList())` today).

### B6: redeemer
- [ ] Redeemer OHTTP gateway with its own keys and relay allow-list.
- [ ] Independent verification of the admin-set chain and leader list against its own pinned genesis.
- [ ] `GET/POST /v1/voucher-keys`, `POST /v1/spends` (all-or-nothing lock, `409` lists serials,
      ≤ 500 vouchers), `GET /v1/spends/{hash}`, `POST /v1/spends/accept` (accepting leader ACTIVE;
      revoked issuers still accepted), `GET /v1/leader/accepted` (`backend.md §8`).
- [ ] Lock-expiry job (every minute) and purge after `accept_until`.

### B6b: revocation and transfer
- [ ] `admin propose leader-revoke --transfer-to | --transfer-map`, `propose quest-transfer`; `apply`
      refuses open quests without a target and fewer than M signatures.
- [ ] Revocation transaction: leader REVOKED, transfers inserted, quests `TRANSFER_PENDING`, live
      submissions `SUPERSEDED`, claims re-opened with extended TTL, artifacts queued for deletion
      (`backend.md §6.6`).
- [ ] `GET /v1/leader/transfers`, `POST /v1/leader/quests/{id}/takeover` (byte-identical check on all
      other signed fields, restore `previous_status`).

### B7: hardening (`backend.md §9`)
- [ ] 🔒 S1: verify no Netty access log, no `CallLogging`; Postgres logging settings documented in deploy.
- [ ] 🔒 S9: detekt with custom rules banning `remoteHost`, `userAgent()`, and logging DTO bodies.
- [ ] S5: Prometheus metrics on the internal port (aggregate counters only, no ID labels).
- [ ] S8: global rate limits on `/v1/enroll` and `/v1/tokens`.
- [ ] S6: encrypted Postgres backups, 14-day retention (MinIO not backed up).
- [ ] Graceful shutdown, readiness vs liveness on `/healthz`, DB/S3 connection timeouts.

### Backend tests (`backend.md §11`)
- [ ] Unit tests for every §6 rule and the auth plugin (stale ts, replay, wrong kid).
- [ ] Integration test per endpoint (happy + error), through a real HPKE/BHTTP round trip.
- [ ] Concurrency: 500 parallel claims on `max_participants = 50` → exactly 50, no token burned on failure.
- [ ] Upload: 16 MiB in 192 KiB chunks with kill + resume.
- [ ] 🔒 Log-privacy test: no IPs, 64-hex keys or base64 blobs > 32 chars in any log line over the E2E suite.
- [ ] Admin & transfer scenarios, and the full E2E (enroll → … → spend at a *different* leader →
      double spend rejected → lock expiry).
- [ ] Load test against the compose stack.

---

## 4. `:shared` domain + apps (both)

Every user-visible screen below needs Compose **and** SwiftUI, with strings in en + pt-BR on both
platforms (`app.md §9` C10). New Swift files must be registered in `project.pbxproj`.

### Shared infrastructure
- [ ] `data/vault/`: encrypted kstore `Vault` (ChaCha20-Poly1305 with the vault key, fresh nonce per
      write, schema versioning + migrations) (`app.md §6`). iOS: `NSURLIsExcludedFromBackupKey`,
      `NSFileProtectionComplete`.
- [ ] `data/net/`: Ktor client (OkHttp / Darwin) with `OhttpPlugin`, pinned key config and relay URL in
      `BuildConfig`, constant `User-Agent: aqe`, no cookies, no `Accept-Language`, cache off (`app.md §7.1`).
- [ ] `ApiService` with typed endpoints and `ApiFailure` mapping, clock-offset resync on `401`,
      exponential backoff (`app.md §7.3`).
- [ ] Signed config fetch + verification, OHTTP key update, update banner data (`app.md §10`).
- [ ] Admin-set chain + leader list verification, cached in the vault (C7).
- [ ] Platform interfaces in `platform/`: `ImageSanitizer`, `CameraCapture` (capture side),
      `BiometricGate`, `BackgroundScheduler` (`app.md §4`); extend `createAppGraph(...)`.
- [ ] `StatusSync` and `TokenRefill` schedulers using `SyncTiming` jitter (`app.md §7.4`).
- [ ] Release logging: Napier no-op, Ktor `Logging` debug only.
- [ ] Domain tests with fakes: claim/submit/voucher state machines, resume after a crash at each step of
      `app.md §8`, restore from mnemonic (`app.md §11`).

### A1: onboarding and identity
- [ ] BIP-39 in `IdentityRepository`: show 24 words (3×8, under `ScreenPrivacy`, no copy), verify
      4 random words, restore with autocomplete + checksum + optional passphrase (`app.md §5.1`).
- [ ] Wipe also deletes the vault and caches (TODO noted in `IdentityRepository.wipe`).
- [ ] Onboarding warning that vouchers can't be recovered without a vault export.
- [ ] Restore flow: re-derive quest keys over the public list and query blind status, throttled (`app.md §6`).

### A2: feed
- [ ] Quest feed (full list, paged), on-device `QuestSearch`, signature + leader + transfer verification,
      drop invalid quests (`app.md §5.2`, C6, C7).
- [ ] Quest detail with `ClaimBlocker` reasons; leader petnames / key fingerprints.

### A3: enrollment, tokens, claim
- [ ] Enrollment screen (scan/paste invite), first token batch.
- [ ] Token issuance (blind, finalize, store), refill at a random time in the epoch; Settings → Tokens.
- [ ] Claim: derive quest key, spend token, sign, handle `409` by restoring the existing claim.
- [ ] QR scanner without ML Kit: `zxing-cpp` (Android), `AVCaptureMetadataOutput` (iOS) (`app.md §3`).

### A4: proof submission
- [ ] 🔒 `ImageSanitizer` (Android `ImageDecoder` → JPEG; iOS `CGImageDestination` with empty
      properties), with metadata-stripping tests on photos from ≥ 3 real devices with GPS on (`app.md §4`, C3).
- [ ] 🔒 `CameraCapture` in-app only (CameraX / `AVCaptureSession`), never an external camera intent.
- [ ] Submit proof screen: sanitized preview, text, SECRET_PREIMAGE scan/type, upload progress.
- [ ] Pipeline in exact `app.md §8` order: envelope → seal → ciphertext-only cache file → resumable
      chunked upload → **persist blinding to vault first** → submit → delete cache.
- [ ] My quests: local claims, statuses, abandon, SUPERSEDED → resubmit banner.

### A5: leader mode
- [ ] Leader mode: show leader pubkeys as QR for the admins; leader home (authorization, voucher key,
      pending count, transfers).
- [ ] Voucher keys: generate per epoch, register, include in encrypted export.
- [ ] Create/edit quest (limits, markdown subset, preimage secret QR, anonymity warning < 3 quests),
      manage (pause/resume/close, counts).
- [ ] 🔒 Review inbox: stream + decrypt in memory, never to disk/image cache/clipboard (C4); approve
      (blind-sign), reject with sealed reason.
- [ ] Background auto-signing for `APPROVED_PENDING_SIGNATURE`.
- [ ] Invites: mint codes on device, upload hashes, show usage.

### A6: wallet and spending
- [ ] Collect vouchers: finalize + verify blind sigs from status sync.
- [ ] Wallet: balance, per-leader breakdown, history (replace both `WalletPlaceholder`s).
- [ ] Spend points: lock at redeemer, QR with 10-minute countdown, receipt, return on timeout.
- [ ] Accept points (leader): scan, accept, per-day history.
- [ ] `BackgroundScheduler`: WorkManager (no Play Services) / `BGAppRefreshTask` + foreground refresh.

### A6b: transfers
- [ ] Leader takeover screen (show admin-signed transfer, accept & re-sign).
- [ ] Participant resubmission: re-encrypt to the new leader key and new voucher-key blinding;
      reject forged transfers (< M signatures) and leader changes not covered by the chain.

### Settings and the rest (`app.md §5.4`)
- [ ] Language override, app lock (`BiometricGate`), network status (relay reachability, pinned key
      fingerprint), export encrypted vault, wipe with typed confirmation, about + licences.
- [ ] C8: local crash log the user can export manually.
- [ ] C1: zero byte arrays after use across the new crypto paths.

### App tests (`app.md §11`)
- [ ] Android: Compose UI tests (backup verification, claim disabled states, review approve/reject),
      ViewModel JVM tests, instrumented `SecretStore` and `ImageSanitizer` tests.
- [ ] iOS: XCTest for `AppModel`/state holders, XCUITest for the same scenarios, Keychain persistence
      and absence after reinstall.
- [ ] E2E: compose stack + Android emulator, full lifecycle and the transfer scenario.

---

## 5. CI, build and distribution

- [ ] ⛔ PR workflow runs **tests**, not only conventions: protocol + shared JVM/Android tests,
      `:shared:compileIosMainKotlinMetadata`, backend tests with Docker, a macOS job for iOS simulator
      tests + `xcodebuild` (`app.md §10`). Today tests run only in the release workflows.
- [ ] 🔒 C2: test that fails on any network host other than the relay (Ktor mock engine).
- [ ] 🔒 Forbidden-dependency allow-list check over the resolved dependency graph (no Play Services,
      analytics, crash reporters, ad SDKs, self-networking SDKs) (`app.md §3`).
- [ ] Lint rule forbidding logging of `*key*|*seed*|*mnemonic*|*token*|*sig*` (`app.md §7.1`).
- [ ] S7: OWASP dependency-check in CI and Renovate for `libs.versions.toml`.
- [ ] C9: R8/obfuscation for Android release; strip iOS symbols.
- [ ] Reproducible Android builds (pinned JDK, `SOURCE_DATE_EPOCH`, deterministic resources) and a
      reproducibility diff check in CI (`app.md §10`).
- [ ] Offline signing procedure for the APK and `.ipa` documented and rehearsed (`releasing.md`).
- [ ] Self-hosted F-Droid repo; AltStore/SideStore source JSON; install guide covering the 7-day
      re-sign limit (`app.md §10`, ADR 0005).
- [ ] Container images published to a registry (today the release only build-checks them).

## 6. Operations

- [ ] Production deployment definition: ≥ 2 `:server` replicas, managed Postgres with TLS, private
      S3/MinIO subnet, Cloudflare relay in front; separate redeemer infra (`backend.md §10`).
- [ ] Secrets management for `MASTER_KEY`, DB and S3 credentials (KMS or equivalent).
- [ ] Alerting on the internal metrics: OHTTP key expiry, issuer rotation, transfer timeout, job failures.
- [ ] Runbooks: key rotation (OHTTP, issuer, config key), admin-set rotation, leader revocation,
      invite-tree revocation, restore from backup, incident response for a leaked preimage secret.
- [ ] Privacy notice / terms in en and pt-BR describing exactly what the server stores.

---

## 7. Production exit criteria

- [ ] Every RFC and golden vector passes on JVM, Android and iOS simulator.
- [ ] Every item in `app.md §9` (C1–C10) and `backend.md §9` (S1–S10) verified, with evidence linked.
- [ ] Full E2E and admin/transfer suites green in CI with **no skipped** Testcontainers tests.
- [ ] External crypto audit findings resolved; threat-model review signed off.
- [ ] All section 1 decisions closed, and the specs moved from "Draft v0.1" to a released status.
