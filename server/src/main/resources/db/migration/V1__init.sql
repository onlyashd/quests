-- FERA Quests :server — initial schema (backend.md §4).
-- Deliberately absent: IPs, user agents, device IDs, push tokens, any mapping from a
-- root key to quest keys, and any mapping from an invite to the quest keys it produced.

-- ── Admin set chain (protocol §4.1, M-of-N co-signing) ──
CREATE TABLE admin_sets (
    version          INTEGER PRIMARY KEY,
    threshold        INTEGER NOT NULL CHECK (threshold >= 1),
    admins           VARCHAR(64)[] NOT NULL,            -- sorted Ed25519 pubkeys
    signatures       JSONB NOT NULL,                    -- [{admin_pubkey, sig}] by ≥ M of version-1 (empty for genesis)
    CHECK (threshold <= cardinality(admins))
);

-- ── Authorized leaders ──
CREATE TABLE leaders (
    pubkey            VARCHAR(64) PRIMARY KEY,          -- Ed25519 leader signing key (hex)
    enc_pubkey        VARCHAR(64) NOT NULL,             -- X25519 key for sealed proofs
    status            VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED')),
    authorized_at     TIMESTAMPTZ NOT NULL DEFAULT date_trunc('minute', now()),
    admin_set_version INTEGER NOT NULL REFERENCES admin_sets(version),
    auth_signatures   JSONB NOT NULL,                   -- ≥ M admins over SigInput("leader_auth", …, AUTHORIZE, …)
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

-- ── Leader voucher keys (protocol §7.2) ──
CREATE TABLE leader_voucher_keys (
    key_id           VARCHAR(64) PRIMARY KEY,           -- sha256(spki_der) hex
    leader_pubkey    VARCHAR(64) NOT NULL REFERENCES leaders(pubkey),
    public_key_spki  BYTEA NOT NULL,                    -- RSA-3072
    valid_from       TIMESTAMPTZ NOT NULL,
    valid_until      TIMESTAMPTZ NOT NULL,
    signature        VARCHAR(128) NOT NULL              -- leader signing key over (key_id, valid_from, valid_until)
);

-- ── Quests ──
CREATE TABLE quests (
    id                   UUID PRIMARY KEY,              -- no DEFAULT: client-generated, covered by the signature
    leader_pubkey        VARCHAR(64) NOT NULL REFERENCES leaders(pubkey),
    leader_enc_pubkey    VARCHAR(64) NOT NULL,
    voucher_key_id       VARCHAR(64) NOT NULL REFERENCES leader_voucher_keys(key_id),
    title                VARCHAR(255) NOT NULL,
    description          TEXT NOT NULL,
    verification_mode    VARCHAR(32) NOT NULL DEFAULT 'MANUAL_REVIEW'
                         CHECK (verification_mode IN ('MANUAL_REVIEW','SECRET_PREIMAGE')),
    secret_preimage_hash VARCHAR(64),
    max_participants     INTEGER CHECK (max_participants IS NULL OR max_participants > 0),
    reward_units         INTEGER NOT NULL DEFAULT 1 CHECK (reward_units >= 0),  -- upper bound MAX_REWARD_UNITS enforced in service
    claimed_count        INTEGER NOT NULL DEFAULT 0,    -- slot accounting (§6.2)
    status               VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED'
                         CHECK (status IN ('PUBLISHED','PAUSED','TRANSFER_PENDING','CLOSED','EXPIRED')),
    created_at           TIMESTAMPTZ DEFAULT date_trunc('minute', now()),
    expires_at           TIMESTAMPTZ,
    signature            VARCHAR(128) NOT NULL,
    CHECK (verification_mode <> 'SECRET_PREIMAGE' OR secret_preimage_hash IS NOT NULL)
);
CREATE INDEX quests_status_created ON quests (status, created_at DESC);
ALTER TABLE quest_transfers ADD FOREIGN KEY (quest_id) REFERENCES quests(id) ON DELETE CASCADE;

-- ── Claims ──
CREATE TABLE quest_claims (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quest_id                 UUID NOT NULL REFERENCES quests(id) ON DELETE CASCADE,
    participant_quest_pubkey VARCHAR(64) NOT NULL,
    status_tag               BYTEA NOT NULL,            -- protocol §8 (32 bytes)
    status                   VARCHAR(32) NOT NULL DEFAULT 'CLAIMED'
                             CHECK (status IN ('CLAIMED','SUBMITTED','APPROVED','REJECTED','EXPIRED','ABANDONED')),
    claimed_at               TIMESTAMPTZ DEFAULT date_trunc('minute', now()),
    expires_at               TIMESTAMPTZ,
    claim_signature          VARCHAR(128) NOT NULL,     -- audit: proves the participant key authorised it
    UNIQUE (quest_id, participant_quest_pubkey)
);
CREATE INDEX quest_claims_status_tag ON quest_claims (status_tag);

-- ── Submissions ──
CREATE TABLE quest_submissions (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quest_id                 UUID NOT NULL REFERENCES quests(id) ON DELETE CASCADE,
    claim_id                 UUID NOT NULL REFERENCES quest_claims(id) ON DELETE CASCADE,
    participant_quest_pubkey VARCHAR(64) NOT NULL,
    proof_format             VARCHAR(32) NOT NULL CHECK (proof_format IN ('IMAGE','TEXT','IMAGE_TEXT','PREIMAGE')),
    sealed_artifact_url      TEXT,                      -- internal object key, never a public URL
    sealed_artifact_sha256   VARCHAR(64),               -- integrity, covered by submission signature
    sealed_artifact_size     BIGINT,
    proof_data               TEXT,                      -- PREIMAGE secret, or small sealed text (base64url)
    blinded_msgs             BYTEA[] NOT NULL DEFAULT '{}',  -- protocol §7.3
    blind_sigs               BYTEA[],                   -- filled on approval
    sealed_reject_reason     BYTEA,                     -- sealed to participant (app §5.3)
    status                   VARCHAR(32) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING','APPROVED_PENDING_SIGNATURE','APPROVED','REJECTED','SUPERSEDED')),
    submission_signature     VARCHAR(128) NOT NULL,
    review_signature         VARCHAR(128),              -- leader's signed decision
    submitted_at             TIMESTAMPTZ DEFAULT date_trunc('minute', now()),
    reviewed_at              TIMESTAMPTZ
);
CREATE INDEX quest_submissions_quest_status ON quest_submissions (quest_id, status);
-- At most one live submission per claim (SUPERSEDED ones are kept until retention, §6.6).
CREATE UNIQUE INDEX quest_submissions_one_live ON quest_submissions (claim_id) WHERE status <> 'SUPERSEDED';

-- ── Chunked uploads (D3) ──
CREATE TABLE upload_sessions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_id          UUID NOT NULL REFERENCES quest_claims(id) ON DELETE CASCADE,
    object_key        TEXT NOT NULL,                    -- random, not derived from ids
    declared_size     BIGINT NOT NULL CHECK (declared_size <= 16777216 + 65536),
    next_chunk        INTEGER NOT NULL DEFAULT 0,
    multipart_id      TEXT,                             -- S3 multipart upload id
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed         BOOLEAN NOT NULL DEFAULT FALSE
);

-- ── Privacy Pass issuer (D1) ──
CREATE TABLE issuer_keys (
    token_key_id      BYTEA PRIMARY KEY,                -- RFC 9578 token_key_id = SHA-256(pkI)
    public_key_spki   BYTEA NOT NULL,
    private_key_enc   BYTEA NOT NULL,                   -- envelope-encrypted with KMS/env master key
    epoch_start       TIMESTAMPTZ NOT NULL,
    issue_until       TIMESTAMPTZ NOT NULL,
    redeem_until      TIMESTAMPTZ NOT NULL
);
CREATE TABLE spent_tokens (
    token_hash        BYTEA PRIMARY KEY,                -- sha256(token)
    token_key_id      BYTEA NOT NULL REFERENCES issuer_keys(token_key_id) ON DELETE CASCADE
);
CREATE TABLE enrollments (                              -- protocol §6.4
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
    code_hash         BYTEA NOT NULL UNIQUE,            -- sha256(code); code itself never stored
    minted_by         VARCHAR(64) NOT NULL,             -- leader or admin pubkey
    parent_invite_id  UUID REFERENCES invites(id),      -- invite tree for revocation
    used              BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at        TIMESTAMPTZ NOT NULL
);

-- ── Replay cache (protocol §9) ──
CREATE UNLOGGED TABLE request_replay (
    sig_hash          BYTEA PRIMARY KEY,
    seen_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── Admin audit log (backend.md §5.7: "every apply writes an append-only audit row") ──
-- Not in the §4 listing; added so `admin apply` has somewhere to write.
CREATE TABLE admin_audit (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    action            VARCHAR(64) NOT NULL,
    request_hash      BYTEA NOT NULL,                   -- sha256 of the applied request file
    signer_pubkeys    VARCHAR(64)[] NOT NULL,
    applied_at        TIMESTAMPTZ NOT NULL DEFAULT date_trunc('minute', now())
);
-- Append-only: block UPDATE/DELETE at the database level.
CREATE FUNCTION admin_audit_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'admin_audit is append-only';
END;
$$;
CREATE TRIGGER admin_audit_no_update BEFORE UPDATE OR DELETE ON admin_audit
    FOR EACH ROW EXECUTE FUNCTION admin_audit_append_only();
