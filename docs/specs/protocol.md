# FERA Quests — Shared Protocol & Cryptography Spec

| | |
|---|---|
| Source | *Anonymous Quest Engine – Technical & Architectural Specification* (PDF) |
| Status | Draft v0.1 — 2026-11-09 |
| Companion specs | [`app.md`](app.md) (client) · [`backend.md`](backend.md) (server) |
| Implemented in | Gradle module `:protocol` (KMP: `android`, `iosArm64`, `iosSimulatorArm64`, `jvm`) |

This document is the **single source of truth** for everything both the client and the server must agree on byte-for-byte: key derivation, signatures, the sealed box, Privacy Pass tokens, blind-signature vouchers, OHTTP, and the request-signing scheme. The client spec and the backend spec reference this file; they must not redefine any of it.

> **Rule:** any code that produces or verifies bytes defined here lives in `:protocol` and is shared by `:shared` (both apps) and `:server`. Never re-implement it on one side only.

---

## 1. Conventions

| Item | Convention |
|---|---|
| Byte strings in JSON | base64url, no padding (RFC 4648 §5) |
| Public keys in DB / URLs | lowercase hex (32-byte key → 64 chars, matching `VARCHAR(64)` in the source schema) |
| Signatures in DB | lowercase hex (64-byte Ed25519 sig → 128 chars, matching `VARCHAR(128)`) |
| UUIDs | lowercase canonical textual form (`8-4-4-4-12`) when hashed or signed, UTF-8 encoded |
| Integers in binary layouts | unsigned big-endian |
| `lp(x)` | `u32be(len(x)) ‖ x` — length-prefixed field |
| `‖` | byte concatenation |
| Protocol version tag | `"AQE-v1"` — every domain-separation string starts with this |
| Timestamps on the wire | Unix seconds (int64). Client-supplied timestamps are only used for replay windows, never stored. |

All randomness comes from the platform CSPRNG (`SecRandomCopyBytes` on iOS, `SecureRandom` on Android/JVM — via libsodium `randombytes_buf` in common code).

---

## 2. Cryptographic library choices

| Primitive | Used for | Library (common code) |
|---|---|---|
| Ed25519 (RFC 8032) | identity, quest-claim, leader signatures | libsodium via `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings` |
| X25519 | sealed box, OHTTP HPKE | libsodium |
| ChaCha20-Poly1305 IETF (RFC 8439) | sealed box AEAD, HPKE AEAD | libsodium (`crypto_aead_chacha20poly1305_ietf_*`) |
| HMAC-SHA256 / HKDF-SHA256 (RFC 5869) | all key derivation | libsodium HMAC + hand-written HKDF (≈30 lines, tested against RFC 5869 vectors) |
| PBKDF2-HMAC-SHA512 | BIP-39 seed | `dev.whyoleg.cryptography` (provider: JDK on Android/JVM, Apple on iOS) |
| SHA-256 / SHA-384 | hashing, RSABSSA | `dev.whyoleg.cryptography` |
| RSA blind signatures (RFC 9474) | Privacy Pass tokens + reward vouchers | **written in `:protocol`** on top of a multiplatform bignum (`com.ionspin.kotlin:bignum`); JVM side may use `java.math.BigInteger` behind an `expect`. Must pass all RFC 9474 Appendix A test vectors. |
| HPKE (RFC 9180), base mode | OHTTP encapsulation | **written in `:protocol`** from the primitives above; must pass RFC 9180 test vectors for suite (0x0020, 0x0001, 0x0003) |

Pin exact library versions in `gradle/libs.versions.toml` when the module is created. All hand-written primitives (HKDF, HPKE, RSABSSA, BHTTP) must ship with the official RFC test vectors as `commonTest` cases, and they must run on every target (JVM, Android, iOS simulator).

---

## 3. Identity & key derivation

### 3.1 Root seed

1. Generate 256 bits of entropy, which gives a **24-word BIP-39 mnemonic** (English wordlist; a pt-BR wordlist can be offered for display only, and the canonical form is always English).
2. `MasterSeed = PBKDF2-HMAC-SHA512(password = NFKD(mnemonic), salt = "mnemonic" ‖ NFKD(passphrase), iter = 2048, L = 64)`. This is standard BIP-39. The passphrase is optional and empty by default.
3. `MasterSeed` (64 bytes) is used directly as the HKDF PRK, as in the source spec (§3.1). Its length is at least HashLen, so this is valid per RFC 5869.

### 3.2 Derived keys

All derived keys use `HKDF-Expand(PRK = MasterSeed, info, L = 32)`.

| Key | `info` | Algorithm | Purpose |
|---|---|---|---|
| Root identity | `"AQE-v1 root:ed25519"` | Ed25519 | Authenticates **token issuance only** (§6.4). Never used on quest endpoints. |
| Quest key *i* | `"AQE-v1 quest:" ‖ QuestID_i` (QuestID as lowercase textual UUID) | Ed25519 | Claim, upload and submission signatures for quest *i*. |
| Leader signing | `"AQE-v1 leader:ed25519"` | Ed25519 | Signs quests, reviews and leader API requests. |
| Leader encryption | `"AQE-v1 leader:x25519"` | X25519 | Recipient key for sealed proofs (`leader_enc_pubkey`). |
| Local vault key | `"AQE-v1 local:vault"` | ChaCha20-Poly1305 key | Encrypts the on-device database (see app spec §6). |

`Ed25519-KeyGen(seed)` means the RFC 8032 secret key is the 32-byte seed itself (libsodium `crypto_sign_seed_keypair`). The X25519 private key is the 32-byte output, clamped per RFC 7748.

> ℹ️ **Deviation from the source spec (decided 2026-11-09).** The source spec uses `"quest:" ‖ QuestID`. We use `"AQE-v1 quest:" ‖ QuestID` so that every label is versioned. This label is frozen at first release, because changing it later would orphan every existing claim.

### 3.3 Unlinkability properties (what this buys and what it doesn't)

- The server, a leader, or a network observer cannot link `PK_quest_i` to `PK_quest_j` or to the root key. Proof: HKDF outputs are independent PRF outputs.
- **Recovery:** restoring the mnemonic re-derives every quest key, so claims can be recovered by re-deriving keys for the quests listed publicly and querying status (§8).
- **Does not hide timing.** If one user claims quests A and B within seconds, the two OHTTP requests can be correlated by time. The client mitigates this with jitter (app spec §7.4).

---

## 4. Signatures and canonical payloads

Every Ed25519 signature in the system is computed over a **domain-separated, length-prefixed** byte string. JSON is never signed directly.

```
SigInput(context, fields...) = "AQE-v1" ‖ 0x00 ‖ lp(context) ‖ lp(field_1) ‖ … ‖ lp(field_n)
```

| Context | Signed by | Fields (in order) |
|---|---|---|
| `quest` | leader signing key | `quest_id`, `leader_pubkey`, `leader_enc_pubkey`, `voucher_key_id`, `title`, `description`, `verification_mode`, `secret_preimage_hash` (empty if null), `u32(max_participants)` (0 = unlimited), `u32(reward_units)`, `i64(expires_at)` (0 = none) |
| `claim` | quest key | `quest_id`, `participant_quest_pubkey`, `token_nonce` (§6.3) |
| `submission` | quest key | `quest_id`, `claim_id`, `proof_format`, `sha256(sealed_artifact)` **or** `sha256(proof_data)`, `sha256(concat(blinded_msgs))` |
| `review` | leader signing key | `submission_id`, `decision` (`APPROVED`/`REJECTED`), `sha256(concat(blind_sigs))` |
| `request` | quest key, root key, or leader key | see §9 |
| `admin_set` | ≥ M admins of the **previous** set | `u32(version)`, `u32(threshold M)`, `lp(admin_pubkey_1)` … `lp(admin_pubkey_N)` (sorted) |
| `leader_auth` | ≥ M admins | `leader_pubkey`, `leader_enc_pubkey`, `action` (`AUTHORIZE`/`REVOKE`), `i64(issued_at)` |
| `quest_transfer` | ≥ M admins | `quest_id`, `from_leader_pubkey`, `to_leader_pubkey`, `u32(seq)` |
| `config_key` | ≥ M admins | `config_pubkey`, `i64(valid_from)`, `i64(valid_until)` |
| `config` | config signing key | `sha256(config_json_bytes)` |
| `voucher_key` | leader signing key | `voucher_key_id`, `i64(valid_from)`, `i64(valid_until)` |

The `quest_id` is generated **client-side by the leader** (UUIDv4) so that it can be covered by the signature. The server rejects a collision with `409`.

### 4.1 Admin set (M-of-N co-signing) — decided 2026-11-09

There is no single admin key. Privileged actions need signatures from **at least M distinct members of the current admin set** (defaults: M = 2, N = 3; both configurable when the set is created or rotated). Each signature is an ordinary Ed25519 signature over the same `SigInput`, so no threshold cryptography is involved.

```kotlin
@Serializable data class AdminSignature(val adminPubkey: String, val sig: String)
@Serializable data class AdminSet(val version: Int, val threshold: Int, val admins: List<String>,
                                  val signatures: List<AdminSignature>)   // by ≥ M of version-1
```

- **Genesis set** (version 1) is pinned in the app (`BuildConfig`) and in server config.
- **Rotation:** set *v+1* is valid only when signed by ≥ M admins of set *v*. Clients walk the chain from their pinned genesis set (or the latest set they have cached) and reject any gap.
- **Actions that need M-of-N:** authorize or revoke a leader, transfer a quest, certify the config signing key, rotate the admin set.
- **Actions that need one admin (operational, low stakes):** mint invite codes, rotate issuer and OHTTP keys. These are performed through the admin CLI on the bastion host.
- **Config signing key:** `/v1/config` changes too often (OHTTP key rotation) to need co-signing every time. It is signed by an online **config key**, whose certificate (`config_key`, validity ≤ 90 days) is co-signed by M admins.
- Admin private keys are kept offline (hardware keys or air-gapped machines) and never sit on a server.

---

## 5. Sealed box (end-to-end encrypted proofs)

The format below is a strict superset of the source spec §3.2. A single-chunk payload is byte-identical to `[32 ephPK][12 nonce][ciphertext][16 tag]`.

### 5.1 Key agreement

```
(esk, epk)  = X25519 fresh ephemeral key pair (per submission)
shared      = X25519(esk, leader_enc_pubkey)          ; abort if all-zero
key         = HKDF-SHA256(IKM = shared,
                          salt = epk ‖ leader_enc_pubkey,
                          info = "AQE-v1 sealed-box",
                          L = 32)
base_nonce  = random 12 bytes
```

### 5.2 Wire format (chunked STREAM construction)

Photos can be several MB, and both the client (encrypt while streaming) and the leader (decrypt while downloading) must work in bounded memory. The plaintext is therefore split into chunks of `CHUNK = 65 536` bytes, and the last chunk may be shorter (including 0 bytes).

```
SealedArtifact = epk(32) ‖ base_nonce(12) ‖ C_0 ‖ C_1 ‖ … ‖ C_{n-1}
C_i            = ChaCha20-Poly1305-Encrypt(key,
                    nonce = base_nonce XOR u96be(i),
                    aad   = "AQE-v1" ‖ quest_id ‖ claim_id ‖ u32be(i) ‖ u8(is_last),
                    pt    = chunk_i)                      ; |C_i| = |chunk_i| + 16
```

- `is_last = 1` only for the final chunk. A truncated stream fails authentication.
- Binding `quest_id` and `claim_id` in the AAD stops a sealed artifact from being replayed into another claim.
- The decryptor knows chunk boundaries from the total length: every chunk except the last is exactly `CHUNK + 16` bytes.

### 5.3 Plaintext envelope

The plaintext is a self-describing envelope **padded to a size bucket** so that ciphertext length does not fingerprint the content:

```
Envelope = u8(version=1) ‖ lp(json_header) ‖ lp(body) ‖ padding(0x00…)
json_header = {"format":"IMAGE"|"TEXT"|"IMAGE_TEXT", "mime":"image/jpeg", "text":"…optional…"}
```

Padded length is the next value of `max(4 KiB, 2^k)`, capped at `MAX_ARTIFACT = 16 MiB`. Example: a 1.3 MB JPEG is padded to 2 MiB. Images are already sanitized (app spec §8) before they reach this step.

### 5.4 Why not HPKE here?

This construction is essentially HPKE base mode with a STREAM layer on top. We keep the source spec's explicit layout so that the wire format stays inspectable. HPKE proper (§7) is used only for OHTTP.

---

## 6. Privacy Pass rate-limit tokens (Decision D1)

> ⚠️ **Correction to the source spec.** The PDF cites "RFC 9505" for Privacy Pass. RFC 9505 is a survey of censorship techniques. The correct references are **RFC 9576** (architecture), **RFC 9577** (HTTP authentication scheme) and **RFC 9578** (issuance protocols).

### 6.1 Token type

Use **token type `0x0002` — Blind RSA (2048-bit)**, RFC 9578 §6, with RSABSSA-SHA384-PSS-Deterministic (RFC 9474).

Rationale: the reward vouchers (§7) need RSA blind signatures anyway, so one audited RFC 9474 implementation serves both. The alternative, VOPRF (`0x0001`), would require a P-384 group implementation in Kotlin/Native, which is a second piece of novel crypto.

### 6.2 Roles

In RFC 9576 terms the deployment uses the **joint Origin–Issuer** model, with the backend playing both roles. The Attester function is described in §6.4.

### 6.3 Token use

- `TokenChallenge.issuer_name = "quests.fera"` (config), `origin_info = "claim"`, `redemption_context` = empty (cross-quest tokens, so that the token reveals nothing about which quest it will be spent on).
- A claim request carries `Authorization: PrivateToken token=<base64url>` (RFC 9577), **inside** the OHTTP-encapsulated request.
- The 32-byte token `nonce` is also included in the `claim` signature (§4), which binds the token to the claim.
- The server keeps a spent-set of `sha256(token)` per issuer key epoch. A token is valid for one claim only.

### 6.4 Issuance gate (Attester) — DECIDED: invite codes (2026-11-09)

Blind tokens hide *which* user spends a token, but something still has to limit *how many* tokens a user can get. Otherwise a bot can generate a million root keys and get a million tokens. The source spec leaves this open. Device attestation (Play Integrity / App Attest) conflicts with sideloading (D4). Chosen design:

1. **Enrollment.** A root key becomes eligible for tokens only after it redeems a single-use **invite code**. Invite codes are minted by leaders or admins (backend spec §5.6, §5.7). The server stores the **invite tree**: who minted each invite, which invite each root key used, and which invites that root key's owner later minted (if they become a leader).
2. **Quota.** Each enrolled root key may obtain at most `TOKENS_PER_EPOCH` tokens per issuer-key epoch. Defaults are 10 tokens and a 7-day epoch. Both are **server-configurable** and published in `/v1/config`.
3. Issuance requests are signed with the root key (§9). The server sees "root key R asked for 10 tokens". Because of blinding, it cannot link those tokens to later claims.

Mass Sybil attacks then cost one invite per identity. Admins can revoke an entire invite branch if abuse is detected.

> **Accepted trade-off (decided 2026-11-09):** storing the invite tree means the server learns *who invited whom* (as root keys, not real identities). It still cannot link a root key to any quest key, claim or submission.

### 6.5 Batch size & key rotation

- The client requests tokens in batches of `TOKENS_PER_EPOCH`, all at once, at a random time early in the epoch. This avoids revealing "I'm about to claim" through issuance timing.
- Issuer keys rotate every epoch. The previous key stays valid for redemption for one extra epoch, and the spent-set for a key is deleted once that key has fully expired.

---

## 7. Reward points — Chaumian e-cash vouchers (Decision D2)

**Rewards are points (decided 2026-11-09).** One voucher = **one point**. A participant's balance is the count of unspent vouchers in their own wallet. There is **no server-side points account**, because an account balance would link every quest that fed into it and break unlinkability. Points are bearer tokens: whoever holds them can spend them, at **any** leader (§7.4).

### 7.1 Scheme

RSABSSA-SHA384-PSS-Randomized (RFC 9474 §5) with a 3072-bit modulus.

### 7.2 Keys

- Each leader generates one **voucher key per epoch** (30 days), on device and at random. RSA keys are not derived from the mnemonic, because deterministic RSA keygen is not standardized. The key is backed up in the leader's encrypted vault export.
- The public key is registered with the backend **and** the redemption counter, and is signed by the leader signing key (`voucher_key_id = sha256(spki_der)`).
- All vouchers are worth **one point**. A quest worth *k* points yields *k* vouchers. This avoids per-amount keys that would partition the anonymity set. The maximum *k* per quest is server-configurable (default 100).

### 7.3 Flow

```
participant                         backend                    leader device
───────────                         ───────                    ─────────────
for j in 1..reward_units:
  serial_j  = random(32)
  msg_j     = "AQE-v1 voucher" ‖ voucher_key_id ‖ serial_j
  (blinded_j, inv_j) = Blind(pk_v, msg_j)
store (serial_j, inv_j) locally
submit { …proof…, blinded_msgs: [blinded_j] } ──►  store
                                                   ──► leader fetches submission
                                                        decrypt, review
                                                        blind_sig_j = BlindSign(sk_v, blinded_j)
                                                   ◄── review {APPROVED, blind_sigs}
status poll ◄────────────────────── blind_sigs
sig_j = Finalize(pk_v, msg_j, blind_sig_j, inv_j)   ; verify locally
voucher_j = (voucher_key_id, serial_j, sig_j)
```

### 7.4 Spending points (redemption)

Points are spent **at a leader**, usually in person. The **redemption counter** (backend spec §8) is a separate service **operated by the leaders** on infrastructure separate from `:server`. Any active leader can accept points issued by any leader, including leaders who were later revoked (decided 2026-11-09).

```
participant app                         redeemer                         leader app
───────────────                         ────────                         ──────────
spend_code = random(16)
select n vouchers
POST /v1/spends {sha256(spend_code), vouchers[n]}  ──►  verify each sig, check none spent/locked,
                                                         lock them for 10 min
show QR(spend_code, n)  ─────────────── in person ─────────────────────► scan QR
                                                    ◄── POST /v1/spends/accept {spend_code} (leader-auth)
                                                         mark vouchers SPENT, record (leader, n, day)
                                                    ──► {points: n}  → leader hands over the reward
GET /v1/spends/{hash} → ACCEPTED  ◄──
mark vouchers spent locally
```

- Sending the vouchers ahead of time keeps the QR code small (≈ 30 bytes instead of ≈ 480 bytes per voucher).
- If the leader doesn't accept within 10 minutes, the lock expires and the vouchers can be spent again.
- The redeemer sees which leaders issued the vouchers and which leader accepted them, but never the quest, the claim or the participant's keys.

> ℹ️ **Anonymity-set caveat:** a voucher reveals its issuing leader. If that leader ran only one quest in the voucher-key epoch, spending reveals that quest. The app warns leaders about this (app spec §5.3). In-person spending also reveals the participant's face to the accepting leader. That is inherent to in-person rewards, and blinding still prevents the leader from linking the points to specific quests or submissions.

---

## 8. Blind status lookup

Participants learn the outcome of a submission (and collect blind signatures) without revealing which claim they are asking about.

```
status_tag    = SHA-256("AQE-v1 status" ‖ participant_quest_pubkey)          ; 32 bytes, stored server-side
bucket_prefix = first STATUS_PREFIX_BITS (default 12) bits of status_tag
```

- Client: `GET /v1/status/{prefix_hex}`. The server returns **every** row whose tag shares the prefix (tag, claim status, submission status, blind sigs). The client filters locally.
- Each claim is polled in its own OHTTP request, with random jitter. Never batch several prefixes in one request.
- `STATUS_PREFIX_BITS` is tuned so that each bucket holds at least about 20 rows at expected volume (backend config).

---

## 9. Request authentication

Requests that must be attributable to a key carry a signature header (all inside OHTTP):

```
AQE-Auth: v1; kid=<hex pubkey>; ts=<unix>; sig=<base64url>
sig = Ed25519(sk, SigInput("request", method, path_with_query, u64(ts), sha256(body)))
```

- The server accepts `|now − ts| ≤ 60 s` and keeps a 120-second replay cache of `sha256(sig)`.
- Which key is acceptable depends on the endpoint (backend spec §5): quest key for claim/submission endpoints, root key for token issuance, leader key for leader endpoints.

---

## 10. Oblivious HTTP transport (Decision D3)

- **RFC 9458 OHTTP** carrying **RFC 9292 Binary HTTP** (known-length form).
- HPKE suite: KEM `0x0020` DHKEM(X25519, HKDF-SHA256), KDF `0x0001` HKDF-SHA256, AEAD `0x0003` ChaCha20-Poly1305.
- **Key config:** the gateway publishes `application/ohttp-keys`. The client ships with a **pinned** key config (via BuildConfig), so it can bootstrap without fetching it through a path that reveals its IP. Updates arrive inside a signed config response (backend spec §5.1).
- **Relay: Cloudflare** (decided 2026-11-09), using Cloudflare's managed OHTTP relay service (Privacy Gateway relay), which requires a commercial agreement. We run only the gateway. Trust assumption: Cloudflare sees client IPs and timing but not content, and the gateway (us) sees content but not IPs. Privacy holds as long as Cloudflare and the backend operator don't collude. The redeemer's gateway (backend §8) uses its own Cloudflare relay endpoint.
- **Size limit:** each encapsulated request/response is ≤ 256 KiB. Larger artifacts are uploaded as multiple independent OHTTP requests (backend spec §5.4), which is how the source spec's "chunked stream proxy" is realised.
- **Response padding:** inner BHTTP responses are padded (BHTTP padding field) to the next multiple of 4 KiB.

---

## 11. Shared data types (`:protocol` module, `kotlinx.serialization`)

```kotlin
enum class VerificationMode { MANUAL_REVIEW, SECRET_PREIMAGE }
enum class ProofFormat     { IMAGE, TEXT, IMAGE_TEXT, PREIMAGE }
enum class QuestStatus     { PUBLISHED, PAUSED, TRANSFER_PENDING, CLOSED, EXPIRED }
enum class ClaimStatus     { CLAIMED, SUBMITTED, APPROVED, REJECTED, EXPIRED, ABANDONED }
enum class SubmissionStatus{ PENDING, APPROVED_PENDING_SIGNATURE, APPROVED, REJECTED, SUPERSEDED }

@Serializable data class QuestTransfer(
  val seq: Int, val fromLeaderPubkey: String, val toLeaderPubkey: String,
  val signatures: List<AdminSignature>)                 // ≥ M admins over SigInput("quest_transfer", …)

@Serializable data class QuestDto(
  val id: String, val leaderPubkey: String, val leaderEncPubkey: String, val voucherKeyId: String,
  val title: String, val description: String, val verificationMode: VerificationMode,
  val secretPreimageHash: String?, val maxParticipants: Int?, val rewardUnits: Int,
  val status: QuestStatus, val slotsRemaining: Int?, val expiresAt: Long?, val signature: String,
  val transfers: List<QuestTransfer> = emptyList())     // ordered by seq; see §11.1
```

### 11.1 Quest transfer verification

When a leader is revoked, admins transfer that leader's quests to other leaders (backend spec §6.6). The quest keeps its `id`, so participants' quest keys stay valid. The new leader re-signs the quest with their own keys. The client accepts a quest only if:

1. `signature` is valid under `leaderPubkey`, and `leaderPubkey` is an authorized leader (or was authorized when it signed, for CLOSED/EXPIRED quests).
2. If `transfers` is non-empty: `seq` runs 1..n with no gaps, each entry has ≥ M valid admin signatures, each `from` equals the previous entry's `to`, and the last `to == leaderPubkey`.
3. If the client has a stored snapshot of this quest with a different leader, the chain from (2) must include that leader. Otherwise the quest is rejected as a possible hijack.

The full DTO list sits next to the endpoint definitions in [`backend.md`](backend.md) §5. The Kotlin types are defined once, in `:protocol`, under `org.ferabrasil.quests.protocol`.

**`SECRET_PREIMAGE` mode:** `secret_preimage_hash = hex(SHA-256("AQE-v1 preimage" ‖ quest_id ‖ secret))`. The leader distributes `secret` physically, for example as a QR code at a location. The participant submits `secret` as `proof_data`. The server verifies the hash and auto-approves, and the leader's device signs the vouchers asynchronously (backend spec §6.4).

---

## 12. Required test vectors (`:protocol` `commonTest`)

| Suite | Source |
|---|---|
| HKDF-SHA256 | RFC 5869 Appendix A |
| BIP-39 | trezor/python-mnemonic `vectors.json` (passphrase `"TREZOR"`) |
| Ed25519 | RFC 8032 §7.1 |
| ChaCha20-Poly1305 | RFC 8439 §2.8.2 |
| HPKE | RFC 9180 test vectors, suite (0x0020,0x0001,0x0003), base mode |
| RSABSSA | RFC 9474 Appendix A (both variants) |
| Privacy Pass type 0x0002 | RFC 9578 Appendix A.2 |
| BHTTP / OHTTP | RFC 9292 / RFC 9458 Appendix A |
| Sealed box & SigInput | project-owned golden vectors in `protocol/src/commonTest/resources/vectors/*.json`, generated once and frozen |

The same test suite must pass on `jvmTest`, `androidHostTest` and `iosSimulatorArm64Test`.

---

## 13. Decisions

The choices in this document are explained in Architecture Decision Records ([`../adr/`](../adr)):
[0002](../adr/0002-privacy-pass-rate-limit-tokens.md) Privacy Pass blind RSA tokens (§6, D1) ·
[0003](../adr/0003-points-as-chaumian-ecash-vouchers.md) e-cash vouchers (§7, D2) ·
[0004](../adr/0004-ohttp-through-cloudflare-relay.md) OHTTP through Cloudflare (§10, D3) ·
[0006](../adr/0006-invite-codes-as-sybil-gate.md) invite-code issuance gate (§6.4) ·
[0007](../adr/0007-m-of-n-admin-co-signing.md) M-of-N admin set (§4.1) ·
[0008](../adr/0008-leader-revocation-transfers-quests.md) quest transfer (§11.1) ·
[0009](../adr/0009-versioned-quest-key-label.md) versioned quest-key label (§3.2).
Changing any of them requires a new ADR.
