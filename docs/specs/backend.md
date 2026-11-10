# FERA Quests — Backend Spec

|                 |                                                                                                   |
|-----------------|---------------------------------------------------------------------------------------------------|
| Source          | *Anonymous Quest Engine – Technical & Architectural Specification* (PDF)                          |
| Status          | Draft v0.1 — 2026-11-09                                                                           |
| Companion specs | [`protocol.md`](protocol.md) (crypto & wire formats — normative) · [`app.md`](app.md) (client)    |
| Modules         | `:server` (Ktor, JVM) · `:redeemer` (Ktor, JVM) · both depend on `:protocol` (its `jvm()` target) |

---

## 1. Goals & principles

The backend is a **blind coordinator**. It stores signed quests, enforces rate limits and slot
counts, and relays ciphertext. It must be designed so that a full compromise of the server and
database reveals:

- no IP addresses, user agents or device identifiers (they are never received or never stored);
- no proof content (sealed to leaders, protocol §5);
- no link between a participant's quests (separate quest keys, blind tokens, OHTTP);
- no link between a completed quest and a redeemed voucher (blind signatures, separate redeemer).

**Design rules**

1. **Store the minimum.** Every column must justify itself. Timestamps are truncated to the minute
   unless a finer value is needed for correctness.
2. **No identity-bearing logs.** Logs carry no request bodies, keys, tokens or IPs. Paths are logged
   as route templates (`/v1/claims/{id}`), never concrete IDs.
3. **Same code, same bytes.** All crypto comes from `:protocol`. The server never re-implements a
   primitive.
4. **Verification on both sides.** The server verifies every signature it stores. Clients still
   verify independently and never trust the server's word.

---

## 2. Repository layout

The backend lives in the same Gradle build as the app:

```
settings.gradle.kts
  include(":protocol", ":shared", ":androidApp", ":server", ":redeemer")

protocol/            KMP: android, iosArm64, iosSimulatorArm64, jvm   (shared — see protocol.md)
server/              kotlin("jvm") + application + ktor plugin
  src/main/kotlin/org/ferabrasil/quests/server/
    Application.kt          module wiring, config load
    config/                 AppConfig (Hoplite or Ktor HOCON)
    ohttp/                  GatewayRoute, KeyConfigProvider, inner dispatch
    auth/                   AqeAuth plugin (protocol §9), ReplayCache, PrivateTokenVerifier
    routes/                 QuestsRoutes, ClaimsRoutes, UploadsRoutes, SubmissionsRoutes,
                            StatusRoutes, TokensRoutes, LeaderRoutes, ConfigRoutes
    service/                QuestService, ClaimService, SubmissionService, IssuerService,
                            EnrollmentService, ArtifactStore, LeaderRegistry
    db/                     Exposed table objects, repositories, Flyway migrations
    jobs/                   ExpiryJob, RetentionJob, KeyRotationJob
    admin/                  AdminCli (subcommands: leader add/revoke, invite mint, keys rotate)
  src/main/resources/db/migration/V1__init.sql …
  src/test/kotlin/…         Testcontainers (Postgres, MinIO)
redeemer/            kotlin("jvm"), separate deployable and separate database (see §8)
deploy/
  docker-compose.yml        postgres, minio, server, redeemer-postgres, redeemer, (dev) ohttp-relay
  Dockerfile.server, Dockerfile.redeemer
```

Gradle changes:

- In `:protocol`, add `jvm()` alongside the existing KMP targets.
- Root `build.gradle.kts`: add `alias(libs.plugins.kotlin.jvm).apply(false)` and
  `alias(libs.plugins.ktor).apply(false)`.
- `libs.versions.toml` additions: `ktor-server-core`, `-netty`, `-content-negotiation`,
  `-status-pages`, `-call-id` (not used for logging), `ktor-server-test-host`,
  `exposed-core/-jdbc/-java-time`, `postgresql`, `hikaricp`, `flyway-core`,
  `flyway-database-postgresql`, `minio` (or `aws.sdk.kotlin:s3`), `logback-classic`,
  `testcontainers-postgresql`, `testcontainers-minio`, `clikt` (admin CLI). Pin versions on
  creation, and reuse the existing `ktor = "3.6.0"` version ref for the server artifacts.

**Runtime:** JDK 21, Ktor Netty engine, PostgreSQL 16+, MinIO (S3-compatible), reachable **only on
the internal network**.

---

## 3. Architecture

```
 Client app ──TLS──► OHTTP Relay (independent operator)  ──TLS──►  :server
                       sees: client IP, ciphertext                   │
                       not:  content                                 ▼
                                                           ┌───────────────────────┐
                                                           │ OHTTP Gateway route   │  POST /ohttp
                                                           │  HPKE decap → BHTTP   │
                                                           │  inner dispatch       │──► in-process
                                                           └──────────┬────────────┘     Ktor pipeline
                                                                      ▼                  (inner routes, §5)
                                          ┌──────────── AqeAuth / PrivateToken auth ───────────┐
                                          ▼                    ▼                    ▼          ▼
                                    QuestService        ClaimService        SubmissionService  IssuerService
                                          │                    │                    │          │
                                          └────────── PostgreSQL ──────────┘   ArtifactStore ─► MinIO (internal)
 Client app ──► Cloudflare relay ──► :redeemer (own gateway, own DB, operated by the leaders)
```

### 3.1 OHTTP gateway

- **Outer endpoints**, the only ones exposed publicly (behind TLS):
    - `GET /ohttp/keys` returns the `application/ohttp-keys` key config.
    - `POST /ohttp` takes and returns `message/ohttp-req` / `message/ohttp-res`.
- The gateway rejects outer requests whose source is not an allow-listed relay IP or mTLS identity.
  This stops clients bypassing the relay and accidentally exposing their IP.
- **Inner dispatch:** the decapsulated BHTTP request is turned into a synthetic Ktor
  `ApplicationCall` and run through the inner routing tree, using `TestApplicationEngine`-style
  in-process dispatch or a dedicated `Route` handler that maps methods and paths. The inner response
  is BHTTP-encoded, padded to a 4 KiB multiple, and encapsulated.
- Inner requests **have no IP**. Any code path that reads `call.request.origin.remoteHost` is a bug,
  and a detekt rule bans it.
- Gateway HPKE keys rotate every 30 days. The previous key stays accepted for 7 days. Key configs
  are published in the signed `/v1/config` (§5.1).
- Max encapsulated request size is 256 KiB, and the server returns `413` above that.

### 3.2 Process & concurrency

- Stateless `:server` replicas behind the relay. All state lives in Postgres and MinIO.
- The replay cache (protocol §9) and the token spent-set use **Postgres** (unique-constraint
  inserts), not memory, so that they work across replicas.
- Background jobs (§7) run on one leader replica, elected with a Postgres advisory lock.

---

## 4. Database schema (PostgreSQL)

The source spec's three tables are kept and **amended**. Every amendment is marked ➕ and has a
reason. Migration: `V1__init.sql` (Flyway).

```sql
-- ── Admin set chain (protocol §4.1, M-of-N co-signing) ──
CREATE TABLE admin_sets (
    version          INTEGER PRIMARY KEY,
    threshold        INTEGER NOT NULL CHECK (threshold >= 1),
    admins           VARCHAR(64)[] NOT NULL,            -- sorted Ed25519 pubkeys
    signatures       JSONB NOT NULL,                    -- [{admin_pubkey, sig}] by ≥ M of version-1 (empty for genesis)
    CHECK (threshold <= cardinality(admins))
);

-- ── Authorized leaders (missing from source spec; needed for "authorized Leaders") ──
CREATE TABLE leaders (
    pubkey           VARCHAR(64) PRIMARY KEY,           -- Ed25519 leader signing key (hex)
    enc_pubkey       VARCHAR(64) NOT NULL,              -- X25519 key for sealed proofs
    status           VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    authorized_at    TIMESTAMPTZ NOT NULL DEFAULT date_trunc('minute', now()),
    admin_set_version INTEGER NOT NULL REFERENCES admin_sets(version),
    auth_signatures  JSONB NOT NULL,                    -- ≥ M admins over SigInput("leader_auth", …, AUTHORIZE, …)
    revoke_signatures JSONB                             -- ≥ M admins over SigInput("leader_auth", …, REVOKE, …)
);

-- ── Quest transfers (leader revocation, §6.6) ──
CREATE TABLE quest_transfers (
    quest_id         UUID NOT NULL,                     -- FK added after quests
    seq              INTEGER NOT NULL,
    from_leader      VARCHAR(64) NOT NULL REFERENCES leaders(pubkey),
    to_leader        VARCHAR(64) NOT NULL REFERENCES leaders(pubkey),
    signatures       JSONB NOT NULL,                    -- ≥ M admins over SigInput("quest_transfer", …)
    previous_status  VARCHAR(32) NOT NULL,              -- restored when the new leader re-signs
    PRIMARY KEY (quest_id, seq)
);

CREATE TABLE leader_voucher_keys (                      -- ➕ protocol §7.2
    key_id           VARCHAR(64) PRIMARY KEY,           -- sha256(spki_der) hex
    leader_pubkey    VARCHAR(64) NOT NULL REFERENCES leaders(pubkey),
    public_key_spki  BYTEA NOT NULL,                    -- RSA-3072
    valid_from       TIMESTAMPTZ NOT NULL,
    valid_until      TIMESTAMPTZ NOT NULL,
    signature        VARCHAR(128) NOT NULL              -- leader signing key over (key_id, valid_from, valid_until)
);

-- ── Quests (source §4, amended) ──
CREATE TABLE quests (
    id                   UUID PRIMARY KEY,                -- ➕ no DEFAULT: client-generated so it is covered by the signature (protocol §4)
    leader_pubkey        VARCHAR(64) NOT NULL REFERENCES leaders(pubkey),   -- ➕ FK
    leader_enc_pubkey    VARCHAR(64) NOT NULL,
    voucher_key_id       VARCHAR(64) NOT NULL REFERENCES leader_voucher_keys(key_id),  -- ➕
    title                VARCHAR(255) NOT NULL,
    description          TEXT NOT NULL,
    verification_mode    VARCHAR(32) NOT NULL DEFAULT 'MANUAL_REVIEW'
                         CHECK (verification_mode IN ('MANUAL_REVIEW','SECRET_PREIMAGE')),
    secret_preimage_hash VARCHAR(64),
    max_participants     INTEGER CHECK (max_participants IS NULL OR max_participants > 0),
    reward_units         INTEGER NOT NULL DEFAULT 1 CHECK (reward_units >= 0),  -- ➕ points; upper bound = MAX_REWARD_UNITS (config, enforced in service)
    claimed_count        INTEGER NOT NULL DEFAULT 0,      -- ➕ slot accounting (§6.2)
    status               VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED'
                         CHECK (status IN ('PUBLISHED','PAUSED','TRANSFER_PENDING','CLOSED','EXPIRED')),
    created_at           TIMESTAMPTZ DEFAULT date_trunc('minute', now()),
    expires_at           TIMESTAMPTZ,
    signature            VARCHAR(128) NOT NULL,
    CHECK (verification_mode <> 'SECRET_PREIMAGE' OR secret_preimage_hash IS NOT NULL)
);
CREATE INDEX quests_status_created ON quests (status, created_at DESC);
ALTER TABLE quest_transfers ADD FOREIGN KEY (quest_id) REFERENCES quests(id) ON DELETE CASCADE;

-- ── Claims (source §4, amended) ──
CREATE TABLE quest_claims (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quest_id                 UUID NOT NULL REFERENCES quests(id) ON DELETE CASCADE,
    participant_quest_pubkey VARCHAR(64) NOT NULL,
    status_tag               BYTEA NOT NULL,              -- ➕ protocol §8 (32 bytes)
    status                   VARCHAR(32) NOT NULL DEFAULT 'CLAIMED'
                             CHECK (status IN ('CLAIMED','SUBMITTED','APPROVED','REJECTED','EXPIRED','ABANDONED')),
    claimed_at               TIMESTAMPTZ DEFAULT date_trunc('minute', now()),
    expires_at               TIMESTAMPTZ,
    claim_signature          VARCHAR(128) NOT NULL,       -- ➕ audit: proves the participant key authorised it
    UNIQUE (quest_id, participant_quest_pubkey)
);
CREATE INDEX quest_claims_status_tag ON quest_claims (status_tag);

-- ── Submissions (source §4, amended) ──
CREATE TABLE quest_submissions (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quest_id                 UUID NOT NULL REFERENCES quests(id) ON DELETE CASCADE,       -- ➕ cascade
    claim_id                 UUID NOT NULL REFERENCES quest_claims(id) ON DELETE CASCADE,
    participant_quest_pubkey VARCHAR(64) NOT NULL,
    proof_format             VARCHAR(32) NOT NULL CHECK (proof_format IN ('IMAGE','TEXT','IMAGE_TEXT','PREIMAGE')),
    sealed_artifact_url      TEXT,                        -- internal object key, never a public URL
    sealed_artifact_sha256   VARCHAR(64),                 -- ➕ integrity, covered by submission signature
    sealed_artifact_size     BIGINT,                      -- ➕
    proof_data               TEXT,                        -- PREIMAGE secret, or small sealed text (base64url)
    blinded_msgs             BYTEA[] NOT NULL DEFAULT '{}',  -- ➕ protocol §7.3
    blind_sigs               BYTEA[],                     -- ➕ filled on approval
    sealed_reject_reason     BYTEA,                       -- ➕ sealed to participant (app §5.3)
    status                   VARCHAR(32) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING','APPROVED_PENDING_SIGNATURE','APPROVED','REJECTED','SUPERSEDED')),
    submission_signature     VARCHAR(128) NOT NULL,       -- ➕
    review_signature         VARCHAR(128),                -- ➕ leader's signed decision
    submitted_at             TIMESTAMPTZ DEFAULT date_trunc('minute', now()),
    reviewed_at              TIMESTAMPTZ
);
CREATE INDEX quest_submissions_quest_status ON quest_submissions (quest_id, status);
-- ➕ at most one live submission per claim (SUPERSEDED ones are kept until retention, §6.6)
CREATE UNIQUE INDEX quest_submissions_one_live ON quest_submissions (claim_id) WHERE status <> 'SUPERSEDED';

-- ── Chunked uploads (D3) ──
CREATE TABLE upload_sessions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_id          UUID NOT NULL REFERENCES quest_claims(id) ON DELETE CASCADE,
    object_key        TEXT NOT NULL,                     -- random, not derived from ids
    declared_size     BIGINT NOT NULL CHECK (declared_size <= 16777216 + 65536),
    next_chunk        INTEGER NOT NULL DEFAULT 0,
    multipart_id      TEXT,                              -- S3 multipart upload id
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed         BOOLEAN NOT NULL DEFAULT FALSE
);

-- ── Privacy Pass issuer (D1) ──
CREATE TABLE issuer_keys (
    token_key_id      BYTEA PRIMARY KEY,                 -- RFC 9578 token_key_id = SHA-256(pkI)
    public_key_spki   BYTEA NOT NULL,
    private_key_enc   BYTEA NOT NULL,                    -- envelope-encrypted with KMS/env master key
    epoch_start       TIMESTAMPTZ NOT NULL,
    issue_until       TIMESTAMPTZ NOT NULL,
    redeem_until      TIMESTAMPTZ NOT NULL
);
CREATE TABLE spent_tokens (
    token_hash        BYTEA PRIMARY KEY,                 -- sha256(token)
    token_key_id      BYTEA NOT NULL REFERENCES issuer_keys(token_key_id) ON DELETE CASCADE
);
CREATE TABLE enrollments (                               -- protocol §6.4
    root_pubkey       VARCHAR(64) PRIMARY KEY,
    invite_id         UUID NOT NULL,
    enrolled_at       TIMESTAMPTZ NOT NULL DEFAULT date_trunc('day', now()),
    revoked           BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE issuance_quota (
    root_pubkey       VARCHAR(64) NOT NULL REFERENCES enrollments(root_pubkey) ON DELETE CASCADE,
    token_key_id      BYTEA NOT NULL REFERENCES issuer_keys(token_key_id) ON DELETE CASCADE,
    issued            INTEGER NOT NULL,
    PRIMARY KEY (root_pubkey, token_key_id)
);
CREATE TABLE invites (
    id                UUID PRIMARY KEY,
    code_hash         BYTEA NOT NULL UNIQUE,             -- sha256(code); code itself never stored
    minted_by         VARCHAR(64) NOT NULL,              -- leader or admin pubkey
    parent_invite_id  UUID REFERENCES invites(id),       -- invite tree for revocation
    used              BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at        TIMESTAMPTZ NOT NULL
);

-- ── Replay cache (protocol §9) ──
CREATE UNLOGGED TABLE request_replay (
    sig_hash          BYTEA PRIMARY KEY,
    seen_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**Deliberately absent:** IPs, user agents, device IDs, push tokens, any mapping from a root key to
quest keys, and any mapping from an invite to the quest keys it later produced.

> ℹ️ **Decided 2026-11-09:** the full invite tree is stored (`enrollments.root_pubkey` ↔
> `invite_id` ↔ `invites.minted_by` / `parent_invite_id`). This allows
> `enrollment revoke --invite-tree`, at the accepted cost of the server knowing who invited whom
> (protocol §6.4).

---

## 5. API (inner endpoints, reached only through `/ohttp`)

Common rules:

- JSON bodies (`application/json`, kotlinx.serialization with `explicitNulls = false`). DTOs live in
  `:protocol` `model/`.
- **Auth column:** `—` = none · `Q` = `AQE-Auth` with the participant **quest key** (and the key
  must match the resource) · `R` = root key · `L` = an ACTIVE leader key · `PT` =
  `Authorization: PrivateToken` (RFC 9577).
- Errors: `{"error":"<code>","message":"…"}` using the status codes in the table in §5.9.
- Pagination: `?cursor=<opaque>&limit=≤100`. Responses also include `next_cursor`.

### 5.1 Config & directory

| Method & path        | Auth | Description                                                                                                                                                                                                                                                                                                                               |
|----------------------|------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /v1/config`     | —    | `{ ohttp_key_configs[], issuer: {name, token_key_id, public_key, epoch_end}, limits: {tokens_per_epoch, claim_ttl_days, max_reward_units, max_artifact_bytes, …}, status_prefix_bits, redeemer_url, latest_app_version, release_manifest }`, signed by the **config key**, plus the config key's M-of-N admin certificate (protocol §4.1) |
| `GET /v1/admin-sets` | —    | The full admin-set chain (normally 1–3 entries)                                                                                                                                                                                                                                                                                           |
| `GET /v1/leaders`    | —    | All leaders (ACTIVE and REVOKED) with their M-of-N `auth_signatures` / `revoke_signatures`, the `admin_set_version` they were signed under, and their voucher keys (with signatures)                                                                                                                                                      |

### 5.2 Enrollment & tokens

| Method & path     | Auth | Request → Response                                                                                                                                                                                                                      |
|-------------------|------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `POST /v1/enroll` | R    | `{invite_code}` → `204`. Hashes the code, checks it is unused and unexpired, marks it used, and inserts the enrollment. Rate-limited globally.                                                                                          |
| `POST /v1/tokens` | R    | `{token_key_id, blinded_requests: [b64]}` (≤ remaining quota) → `{blind_signatures: [b64]}`. Requires an enrollment that is not revoked. Within one transaction: `issued + n ≤ TOKENS_PER_EPOCH`. Uses the RFC 9578 §6 issuance format. |

### 5.3 Quests (participant view)

| Method & path         | Auth | Description                                                                                                                                                                                                                             |
|-----------------------|------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /v1/quests`      | —    | All quests with status `PUBLISHED`/`PAUSED`/`TRANSFER_PENDING`, newest first, **no filter parameters** (app §9 C6). `slots_remaining = max_participants − claimed_count`. Each quest includes its `transfers[]` chain (protocol §11.1). |
| `GET /v1/quests/{id}` | —    | A single quest, including CLOSED/EXPIRED, so that old claims still render                                                                                                                                                               |

### 5.4 Claims, uploads, submissions

| Method & path                      | Auth   | Request → Response                                                                                                                                                                                               |
|------------------------------------|--------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `POST /v1/quests/{id}/claims`      | PT + Q | `{participant_quest_pubkey, token_nonce, claim_signature}` → `201 {claim_id, expires_at}`. See §6.2.                                                                                                             |
| `DELETE /v1/claims/{id}`           | Q      | Abandon (only while CLAIMED). Decrements `claimed_count`.                                                                                                                                                        |
| `POST /v1/claims/{id}/uploads`     | Q      | `{declared_size}` → `{upload_id, chunk_size: 196608}`. Requires the claim to be CLAIMED and the quest to be MANUAL_REVIEW.                                                                                       |
| `PUT /v1/uploads/{uid}/chunks/{n}` | Q      | `application/octet-stream` body (≤ chunk_size). `n` must equal `next_chunk` (idempotent if repeated with an identical hash). Streamed straight into an S3 multipart part. **Nothing is buffered to local disk.** |
| `GET /v1/uploads/{uid}`            | Q      | `{next_chunk}`, used for resume                                                                                                                                                                                  |
| `POST /v1/claims/{id}/submissions` | Q      | `{proof_format, upload_id?, sealed_artifact_sha256?, proof_data?, blinded_msgs[], submission_signature}` → `201 {submission_id, status}`. See §6.3/§6.4.                                                         |

### 5.5 Blind status

| Method & path                 | Auth | Response                                                                                                                                                                                                                                                    |
|-------------------------------|------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /v1/status/{prefix_hex}` | —    | `[{status_tag, claim_id, claim_status, submission_status?, blind_sigs?, sealed_reject_reason?}]` for every claim whose `status_tag` starts with the prefix (protocol §8). Implemented as a range scan on `status_tag` (`>= prefix‖00… AND < prefix+1‖00…`). |

### 5.6 Leader

| Method & path                                               | Auth                        | Description                                                                                                                                                                                             |
|-------------------------------------------------------------|-----------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `POST /v1/leader/voucher-keys`                              | L                           | Register a voucher key (protocol §7.2). This also pushes it to the redeemer (§8).                                                                                                                       |
| `POST /v1/leader/quests`                                    | L                           | Body is a full `QuestDto` minus server fields. Verify the signature, that `leader_pubkey == kid`, that `voucher_key_id` belongs to this leader and is valid through `expires_at`, and the field limits. |
| `PATCH /v1/leader/quests/{id}`                              | L                           | `{status: PAUSED                                                                                                                                                                                        |PUBLISHED|CLOSED}`. Owner only. |
| `GET /v1/leader/submissions?status=PENDING                  | APPROVED_PENDING_SIGNATURE` | L                                                                                                                                                                                                       | Submissions on this leader's quests, with `blinded_msgs`, `sealed_artifact_size`, and `proof_data` (sealed text). |
| `GET /v1/leader/submissions/{sid}/artifact?offset=&length=` | L                           | Ranged read of the sealed artifact (≤ 192 KiB per request), streamed from MinIO.                                                                                                                        |
| `POST /v1/leader/submissions/{sid}/review`                  | L                           | `{decision, blind_sigs?, sealed_reject_reason?, review_signature}`. See §6.5.                                                                                                                           |
| `POST /v1/leader/invites`                                   | L                           | `{code_hashes: [b64]}` (≤ 50) → `204`. The leader generates the codes on device, and the server stores only the hashes.                                                                                 |
| `GET /v1/leader/transfers`                                  | L                           | Quests in `TRANSFER_PENDING` whose latest transfer has `to_leader == kid`                                                                                                                               |
| `POST /v1/leader/quests/{id}/takeover`                      | L                           | Body is the re-signed `QuestDto` with the new `leader_pubkey`, `leader_enc_pubkey` and `voucher_key_id`. Every other signed field must be unchanged. Status goes back to `previous_status`. See §6.6.   |

### 5.7 Admin (M-of-N, offline signing)

There is **no network admin API.** Admin operations run through `java -jar server.jar admin …`
(Clikt). Actions that need co-signing (protocol §4.1) use a **propose → sign → apply** workflow, so
that no single machine ever holds M admin keys:

```
admin propose leader-add    --pubkey … --enc-pubkey …          > req.json   # bastion: unsigned request
admin propose leader-revoke --pubkey … --transfer-to <leader>   > req.json   # includes one quest_transfer per open quest
admin propose quest-transfer --quest … --to <leader>            > req.json
admin propose admin-set     --threshold 2 --admins a,b,c        > req.json
admin propose config-key    --pubkey … --valid-days 90          > req.json
admin sign  req.json --key <hardware key | offline file>        # on each admin's own machine; appends a signature
admin apply req.json                                            # bastion: verifies ≥ M distinct current admins, then commits
```

- `leader-revoke` **must** name a target leader (or `--transfer-map file.csv` to split quests across
  several leaders) for every quest that isn't CLOSED/EXPIRED. `apply` refuses if any open quest is
  left without a transfer.
- Single-admin commands: `invite mint --count N`, `issuer rotate`, `ohttp rotate`, `config sign`
  (with the certified config key), `enrollment revoke --invite-tree <id>`.
- Every `apply` writes an append-only audit row (action, request hash, signer pubkeys, time).

### 5.8 Response padding & constant shape

- The BHTTP padding (protocol §10) hides length, and some fields need extra care. For example,
  `GET /v1/status` always returns its result in an array, even when empty, so that its shape never
  changes.

### 5.9 Error codes

| HTTP | `error`                                                                        | When                                                           |
|------|--------------------------------------------------------------------------------|----------------------------------------------------------------|
| 400  | `bad_request`                                                                  | Schema/size violations                                         |
| 401  | `bad_auth`                                                                     | Bad signature, stale `ts`, replayed signature, kid not allowed |
| 402  | `token_required` / `token_invalid` / `token_spent`                             | Privacy Pass failures on claim                                 |
| 403  | `forbidden`                                                                    | Not the owner (leader), not enrolled, leader revoked           |
| 404  | `not_found`                                                                    |                                                                |
| 409  | `already_claimed` / `already_submitted` / `id_conflict` / `chunk_out_of_order` |                                                                |
| 410  | `quest_unavailable`                                                            | Quest PAUSED/CLOSED/EXPIRED, or no slots                       |
| 413  | `too_large`                                                                    |                                                                |
| 429  | `quota_exceeded`                                                               | Token issuance quota, enroll rate                              |

---

## 6. Core business logic

### 6.1 Quest lifecycle

```
PUBLISHED ⇄ PAUSED ──► CLOSED
    │   ▲      │            ▲
    │   └─ TRANSFER_PENDING ◄┘ (leader revoked; new leader re-signs → previous status)
    └── expires_at ──► EXPIRED (job)
```

- No new claims unless the quest is PUBLISHED and has slots left. Existing claims can still submit
  while the quest is PAUSED, and until the claim expires once it is CLOSED or EXPIRED. While
  TRANSFER_PENDING, submissions are **refused** (`410`) because the leader's encryption key is about
  to change.

### 6.2 Claim (race-safe slot accounting)

Single transaction:

1. Verify `AQE-Auth` with `kid == participant_quest_pubkey`, and verify `claim_signature` (protocol
   §4).
2. Verify the Privacy Pass token: the key is in its redeem window, the RSA signature is valid, and
   the challenge digest matches `issuer_name` and `origin_info="claim"`. Check that
   `token.nonce == token_nonce`.
3. `INSERT INTO spent_tokens` (unique violation → `402 token_spent`).
4.
`UPDATE quests SET claimed_count = claimed_count + 1 WHERE id=$1 AND status='PUBLISHED' AND (expires_at IS NULL OR expires_at > now()) AND (max_participants IS NULL OR claimed_count < max_participants) RETURNING …`.
Zero rows → roll back (the token is **not** consumed) and return `410`.
5. `INSERT INTO quest_claims` with `status_tag = SHA-256("AQE-v1 status" ‖ pubkey)` and
   `expires_at = min(quest.expires_at, now() + CLAIM_TTL)` (default 7 days). A unique violation
   means the claim already exists: roll back and return `409 already_claimed` with the existing
   `claim_id`.

The conditional `UPDATE` (rather than `SELECT … FOR UPDATE` + check) avoids lock convoys under claim
floods. Token verification happens **before** the transaction, so invalid floods never touch the
quest row.

### 6.3 Upload (chunked stream proxy — D3)

- `object_key = "a/" + random 128-bit hex`. MinIO stores only ciphertext.
- Each `PUT chunk` maps to one S3 multipart part. S3 needs parts of at least 5 MiB except the last,
  so the server **coalesces** client chunks into 5 MiB parts using a bounded in-memory buffer (≤ 5
  MiB per active session, with active sessions capped by config). Alternatively, use MinIO's
  `compose`. The coalescing approach is preferred.
- On submission finalize: complete the multipart upload, compute and compare SHA-256 (computed
  incrementally during upload and stored in session state), and check that `size == declared_size`.
- Incomplete sessions older than 24 h are aborted by `RetentionJob`.
- MinIO server access logging is **disabled**. MinIO is reachable only from `:server`, so it never
  sees client IPs anyway, which is the point of D3.

### 6.4 Submission

1. The claim belongs to `kid` and is CLAIMED and not expired, and there is no existing submission.
2. Verify `submission_signature` over the fields in protocol §4.
3. `len(blinded_msgs) == quest.reward_units` (≤ `MAX_REWARD_UNITS`), and each one is exactly 384
   bytes (RSA-3072).
4. By mode:
    - **MANUAL_REVIEW:** `proof_format ∈ {IMAGE, TEXT, IMAGE_TEXT}`. Either `upload_id` (artifact)
      or `proof_data` (sealed text ≤ 64 KiB, base64url) must be present. Status becomes `PENDING`.
    - **SECRET_PREIMAGE:** `proof_format = PREIMAGE`. Check
      `SHA-256("AQE-v1 preimage" ‖ quest_id ‖ proof_data) == secret_preimage_hash` in constant time.
      Mismatch → `400` (rate-limited per claim: 5 attempts, after which the claim moves to
      `REJECTED`). Match → status `APPROVED_PENDING_SIGNATURE` and claim `SUBMITTED`. The leader app
      signs later.
5. The claim status becomes `SUBMITTED`.

> ⚠️ In SECRET_PREIMAGE mode the preimage travels to the server in plaintext (inside OHTTP), so the
> server learns the secret after the first submission. That is acceptable because the secret only
> gates *this* quest, and slot accounting stops replays beyond `max_participants`. If a secret leaks
> publicly, the leader closes the quest.

### 6.5 Review

1. The leader owns the quest, and the submission is `PENDING` or `APPROVED_PENDING_SIGNATURE`.
2. Verify `review_signature` (protocol §4).
3. **APPROVED:** `len(blind_sigs) == len(blinded_msgs)`. The server **verifies** each blind
   signature against the leader's voucher key (`s^e mod n == blinded_msg`) so that a buggy leader
   client can't strand participants. Status → `APPROVED`, claim → `APPROVED`.
4. **REJECTED:** store `sealed_reject_reason` (≤ 2 KiB). Status → `REJECTED`, claim → `REJECTED`.
5. Set `reviewed_at`. Schedule artifact deletion (§7).

### 6.6 Leader revocation & quest transfer (decided 2026-11-09)

When admins apply a `leader-revoke` request (§5.7), in **one transaction**:

1. `leaders.status = 'REVOKED'` and `revoke_signatures` are stored. The revoked key can no longer
   call any `L` endpoint.
2. For each open quest of the revoked leader: insert `quest_transfers` (with the next `seq` and
   `previous_status`), and set `quests.status = 'TRANSFER_PENDING'`.
3. For each `PENDING` or `APPROVED_PENDING_SIGNATURE` submission on those quests:
    - Submission → `SUPERSEDED`. Its sealed artifact can only be decrypted by the revoked leader,
      and its blinded messages target the revoked leader's voucher key.
    - Claim → `CLAIMED`, with `expires_at = max(expires_at, now() + CLAIM_TTL)` so the participant
      has time to resubmit.
    - The artifact is queued for immediate deletion.
4. `APPROVED` and `REJECTED` submissions are left as they are. Points already issued stay spendable
   at the redeemer until their key's `accept_until` (§8). Revocation does **not** cut off issued
   points.

**Takeover:** the target leader's app lists its transfers (`GET /v1/leader/transfers`) and posts the
re-signed quest (`/takeover`). The server checks that:

- the latest transfer's `to_leader == kid`;
- the new quest signature verifies;
- all fields except `leader_pubkey`, `leader_enc_pubkey`, `voucher_key_id` and `signature` are
  byte-identical to the stored quest.

It then replaces those four columns and restores `previous_status`.

**Participants** see `submission_status = SUPERSEDED` through blind status (§5.5), and resubmit to
the new leader as a new submission row. The partial unique index allows this.

**If the target leader never takes over:** after `TRANSFER_TIMEOUT_DAYS` (default 14), `ExpiryJob`
alerts the admins (metric plus audit row). The quest stays TRANSFER_PENDING until the admins
transfer it again or close it.

---

## 7. Background jobs

| Job              | Schedule    | Action                                                                                                                                                                                                                                                                                                                                                                                                              |
|------------------|-------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ExpiryJob`      | every 5 min | Quests past `expires_at` → EXPIRED. Claims past `expires_at` still CLAIMED → EXPIRED, with `claimed_count` decremented (slot freed). Alert on TRANSFER_PENDING quests older than `TRANSFER_TIMEOUT_DAYS` (§6.6).                                                                                                                                                                                                    |
| `RetentionJob`   | hourly      | Retention periods decided 2026-11-09. Delete MinIO objects and null `sealed_artifact_url` **7 days after review** (gives the leader time to re-check), **60 days after submission** if never reviewed, and **immediately** for SUPERSEDED submissions. Abort stale uploads after 24 h. Purge `request_replay` rows older than 120 s. Delete claims, submissions and status tags **30 days after a terminal state**. |
| `KeyRotationJob` | daily       | Create the next issuer key 24 h before the epoch boundary. Drop `issuer_keys` (cascading `spent_tokens`, `issuance_quota`) past `redeem_until`. Warn (via an admin metric) when the OHTTP key is near expiry.                                                                                                                                                                                                       |

All jobs take a Postgres advisory lock (`pg_try_advisory_lock`) so that only one replica runs each
job.

---

## 8. Redemption counter (`:redeemer`) — points spending

A separate Ktor app with **its own database, credentials and OHTTP gateway**, **operated by the
leaders** (decided 2026-11-09) on infrastructure separate from `:server`. It implements points
spending as described in protocol §7.4.

**Trust:** the redeemer does not trust `:server`. It fetches the admin-set chain and the leader list
from `:server` but verifies every M-of-N signature itself against the genesis admin set pinned in
its own config.

| Method & path (inner, via the redeemer's Cloudflare relay) | Auth | Description                                                                                                                                                                                                                                                                                                                                 |
|------------------------------------------------------------|------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `GET /v1/voucher-keys`                                     | —    | Registered voucher keys                                                                                                                                                                                                                                                                                                                     |
| `POST /v1/voucher-keys`                                    | L    | Registers a key. `:server` forwards it on leader registration, and the redeemer verifies the leader's `voucher_key` signature and the leader's authorization itself.                                                                                                                                                                        |
| `POST /v1/spends`                                          | —    | Participant: `{spend_code_hash, vouchers: [{voucher_key_id, serial, sig}]}` (≤ 500) → `201 {count}`. Verifies every RSABSSA signature (protocol §7), checks every key is within `accept_until` and that no serial is SPENT or LOCKED, then locks them all for `SPEND_LOCK_MINUTES` (10). All-or-nothing; `409` lists the offending serials. |
| `GET /v1/spends/{spend_code_hash}`                         | —    | `{status: LOCKED                                                                                                                                                                                                                                                                                                                            |ACCEPTED|EXPIRED, count, accepted_by_alias?}`, polled by the participant |
| `POST /v1/spends/accept`                                   | L    | Leader: `{spend_code}` → hashes it, finds the LOCKED spend, marks its vouchers SPENT, and returns `{points: n}`. The accepting leader must be **ACTIVE**. Vouchers issued by **REVOKED** leaders are still accepted (decided 2026-11-09).                                                                                                   |
| `GET /v1/leader/accepted?from=&to=`                        | L    | The leader's own history of accepted points per day, for reconciliation                                                                                                                                                                                                                                                                     |

```sql
CREATE TABLE voucher_keys   (key_id VARCHAR(64) PRIMARY KEY, leader_pubkey VARCHAR(64) NOT NULL,
                             public_key_spki BYTEA NOT NULL, valid_until TIMESTAMPTZ NOT NULL,
                             accept_until TIMESTAMPTZ NOT NULL);           -- valid_until + 90 days
CREATE TABLE spends         (spend_code_hash BYTEA PRIMARY KEY,
                             status VARCHAR(16) NOT NULL CHECK (status IN ('LOCKED','ACCEPTED','EXPIRED')),
                             count INTEGER NOT NULL, locked_until TIMESTAMPTZ NOT NULL,
                             accepted_by VARCHAR(64), accepted_day DATE);
CREATE TABLE voucher_states (serial_hash BYTEA PRIMARY KEY,               -- sha256(serial)
                             key_id VARCHAR(64) NOT NULL REFERENCES voucher_keys(key_id),
                             state VARCHAR(8) NOT NULL CHECK (state IN ('LOCKED','SPENT')),
                             spend_code_hash BYTEA NOT NULL REFERENCES spends(spend_code_hash));
```

- **Lock expiry job (every minute):** spends past `locked_until` that are still LOCKED → EXPIRED,
  and their `voucher_states` rows are deleted, which releases the vouchers.
- Vouchers are accepted until `valid_until + 90 days`. Spent-voucher rows can be purged after
  `accept_until`.
- The redeemer shares **no** database, network segment or logs with `:server`.

---

## 9. Security & operations

| #   | Requirement                                                                                                                                                                                                                                                                                           |
|-----|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| S1  | **No IP / UA logging.** Netty access logs off, no Ktor `CallLogging` (or a custom one that logs only route template, status and latency bucket). Reverse proxy (if any) has `access_log off`. PostgreSQL: `log_connections=off`, `log_statement=none`, `log_min_error_statement=panic` for user data. |
| S2  | **Secrets:** issuer, OHTTP and config-key private keys are envelope-encrypted with a master key from env/KMS. **Admin keys never live on any server.** They stay on each admin's hardware key or offline machine, and only signatures travel (§5.7).                                                  |
| S3  | **Input limits:** title ≤ 255, description ≤ 10 000 chars, `proof_data` ≤ 64 KiB, artifact ≤ 16 MiB + overhead, `reward_units` and `blinded_msgs` ≤ `MAX_REWARD_UNITS` (configurable, default 100).                                                                                                   |
| S4  | **Constant-time** comparisons for preimage hashes and invite hashes.                                                                                                                                                                                                                                  |
| S5  | **Metrics:** Prometheus counters aggregated only (claims/min, submissions/min, errors by code), with no labels carrying IDs. Exposed on an internal port.                                                                                                                                             |
| S6  | **Backups:** Postgres backups encrypted at rest and kept 14 days. MinIO is **not** backed up (artifacts are ephemeral by design).                                                                                                                                                                     |
| S7  | **Dependency hygiene:** OWASP dependency-check in CI, and Renovate for `libs.versions.toml`.                                                                                                                                                                                                          |
| S8  | **Abuse:** a global rate limit (token bucket in Postgres or in-memory per replica) on `/v1/enroll` and `/v1/tokens`. Per-client limits are impossible by design, which is why D1 exists.                                                                                                              |
| S9  | **Static checks:** detekt rules that ban `remoteHost`, `request.userAgent()`, and logging of DTO bodies.                                                                                                                                                                                              |
| S10 | **Threat-model review** and an external crypto audit of `:protocol` before production.                                                                                                                                                                                                                |

---

## 10. Local development & deployment

`deploy/docker-compose.yml` (dev):

| Service                         | Image                                                                           | Notes                                                                                                                                       |
|---------------------------------|---------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| `postgres`                      | `postgres:16`                                                                   | `quests` DB                                                                                                                                 |
| `minio`                         | `minio/minio`                                                                   | bucket `artifacts`, internal network only                                                                                                   |
| `server`                        | built from `:server` (`./gradlew :server:installDist` or the Ktor fat-jar task) | ports: 8080 (outer `/ohttp`), 9090 (metrics, internal)                                                                                      |
| `redeemer-postgres`, `redeemer` | built from `:redeemer`                                                          | separate network                                                                                                                            |
| `ohttp-relay` (dev only)        | a minimal relay written in `:server` test sources                               | **production uses the Cloudflare OHTTP relay** (decided 2026-11-09). There is one relay endpoint for `:server` and another for `:redeemer`. |

Configuration (env, via Ktor `application.conf` / Hoplite):

```
DB_URL, DB_USER, DB_PASSWORD
S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY, S3_SECRET_KEY
MASTER_KEY                          # envelope key for issuer/ohttp/config private keys
RELAY_ALLOWLIST                     # Cloudflare relay egress ranges / mTLS identity (per Cloudflare agreement)
GENESIS_ADMIN_SET                   # path to the pinned genesis admin set JSON (same file baked into the app)
ISSUER_NAME=quests.fera

# Tunables (decided 2026-11-09). All are published in /v1/config so the app adapts without a release.
TOKENS_PER_EPOCH=10                 # configurable
TOKEN_EPOCH_DAYS=7
CLAIM_TTL_DAYS=7                    # configurable
MAX_REWARD_UNITS=100                # configurable
MAX_ARTIFACT_BYTES=16777216
VOUCHER_EPOCH_DAYS=30               # app uses this when generating voucher keys
VOUCHER_ACCEPT_GRACE_DAYS=90        # redeemer config
STATUS_PREFIX_BITS=12
ARTIFACT_RETENTION_DAYS=7  UNREVIEWED_RETENTION_DAYS=60  TERMINAL_RETENTION_DAYS=30
TRANSFER_TIMEOUT_DAYS=14
SPEND_LOCK_MINUTES=10               # redeemer config
```

Changing `TOKENS_PER_EPOCH` takes effect at the next issuer epoch. Changing `CLAIM_TTL_DAYS` applies
to new claims only. Changing `MAX_REWARD_UNITS` applies to new quests only.

Production: two or more `:server` replicas, managed Postgres with TLS, MinIO or S3 on a private
subnet, and the Cloudflare relay in front of the gateway. The redeemer is operated by the leaders on
separate infrastructure.

---

## 11. Testing strategy

| Layer            | Tooling                                                           | Must cover                                                                                                                                                                                                                                                                        |
|------------------|-------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Unit             | `kotlin.test`, MockK                                              | Each service rule in §6, auth plugin (stale ts, replay, wrong kid)                                                                                                                                                                                                                |
| Integration      | `ktor-server-test-host` + Testcontainers (Postgres, MinIO)        | Every endpoint in §5, both happy and error paths. Inner requests go through a real HPKE/BHTTP gateway round trip using the `:protocol` client.                                                                                                                                    |
| Concurrency      | 500 parallel claims on a `max_participants = 50` quest            | Exactly 50 succeed, `claimed_count = 50`, and no failed claim spends its token                                                                                                                                                                                                    |
| Upload           | 16 MiB artifact in 192 KiB chunks, with a kill and resume halfway | Stored SHA-256 equals the client's, and the multipart upload is completed                                                                                                                                                                                                         |
| Privacy          | Log capture during the full E2E suite                             | No IP, no 64-hex keys, no base64 blobs > 32 chars in any log line                                                                                                                                                                                                                 |
| Crypto interop   | Same `:protocol` vectors on `jvmTest`                             | Server verify ⇔ client sign                                                                                                                                                                                                                                                       |
| E2E              | Compose stack + client domain layer (JVM)                         | enroll → tokens → claim → upload → submit → review → status → unblind → spend at a *different* leader → double spend rejected → lock expiry releases vouchers                                                                                                                     |
| Admin & transfer | Admin CLI against Testcontainers                                  | `apply` with M−1 signatures is refused. Revoking a leader with open quests and no transfer target is refused. Revoke → takeover → participant resubmits → approve. Points from the revoked leader are still spendable. A takeover that changes any other signed field is refused. |

---

## 12. Milestones (backend)

| ID  | Scope                                                                                                                                            | Exit criteria                                             |
|-----|--------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------|
| B0  | `:server` / `:redeemer` modules, Gradle wiring, Flyway V1, compose stack, `:protocol` `jvm()` target                                             | `./gradlew :server:test` green against Testcontainers     |
| B1  | OHTTP gateway plus inner dispatch, `/v1/config`, `/v1/admin-sets`, `/v1/leaders`, admin CLI propose/sign/apply for `leader-add` and `config-key` | App (A2) loads the feed through the dev relay             |
| B2  | Enrollment, invites, Privacy Pass issuer, key rotation job                                                                                       | Token quota enforced, RFC 9578 vectors pass on the server |
| B3  | Leader quest CRUD, claims with slot accounting                                                                                                   | Concurrency test passes                                   |
| B4  | Chunked uploads to MinIO, submissions (both modes), blind status                                                                                 | Upload/resume test passes, status bucket query works      |
| B5  | Review endpoint with blind-sig verification, retention job                                                                                       | Full E2E through approval                                 |
| B6  | `:redeemer` service (spends, locks, leader accept)                                                                                               | Spend, double-spend and lock-expiry tests pass            |
| B6b | Leader revocation and quest transfer (§6.6), `leader-revoke` / `quest-transfer` admin actions                                                    | Admin & transfer tests pass                               |
| B7  | Hardening: §9 checklist, log-privacy test, load test, external audit                                                                             | Sign-off                                                  |

Client milestones in [`app.md`](app.md) §12 depend on these: A2→B1, A3→B2+B3, A4→B4, A5→B5, A6→B6,
A6b→B6b.

---

## 13. Decisions log & open questions

**Decided:** architectural decisions are recorded in [`../adr/`](../adr):

- [0002](../adr/0002-privacy-pass-rate-limit-tokens.md) Privacy Pass tokens
  (D1) · [0003](../adr/0003-points-as-chaumian-ecash-vouchers.md) points and the separate redeemer
  (§8, D2) · [0004](../adr/0004-ohttp-through-cloudflare-relay.md) OHTTP via Cloudflare and proxied
  uploads (§3.1, §6.3, D3)
- [0006](../adr/0006-invite-codes-as-sybil-gate.md) invite codes, full invite tree stored
  (§4) · [0007](../adr/0007-m-of-n-admin-co-signing.md) M-of-N admin co-signing
  (§5.7) · [0008](../adr/0008-leader-revocation-transfers-quests.md) leader revocation and quest
  transfer (§6.6)

Operational defaults, decided 2026-11-09:

- Retention: the defaults in §7.
- Tunables: §10 defaults, with tokens/epoch, claim TTL and max reward configurable.

**Open**

1. **Admin set:** confirm M = 2 / N = 3 and who the genesis admins are (blocks B1).
2. **Cloudflare agreement:** needs a signed contract for the managed OHTTP relay, plus the relay
   egress ranges or mTLS identity for `RELAY_ALLOWLIST` (blocks production).
3. **Redeemer hosting:** the leaders operate it, but someone has to host it, ideally not on the same
   provider account as `:server`.
4. **Point expiry:** today points are accepted up to 90 days after their voucher key's 30-day epoch
   ends. Is that acceptable?
