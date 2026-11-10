-- FERA Quests :redeemer — initial schema (backend.md §8).
-- Shares no database, network segment or logs with :server.

CREATE TABLE voucher_keys (
    key_id           VARCHAR(64) PRIMARY KEY,           -- sha256(spki_der) hex
    leader_pubkey    VARCHAR(64) NOT NULL,
    public_key_spki  BYTEA NOT NULL,                    -- RSA-3072
    valid_until      TIMESTAMPTZ NOT NULL,
    accept_until     TIMESTAMPTZ NOT NULL               -- valid_until + VOUCHER_ACCEPT_GRACE_DAYS
);

CREATE TABLE spends (
    spend_code_hash  BYTEA PRIMARY KEY,                 -- sha256(spend_code)
    status           VARCHAR(16) NOT NULL CHECK (status IN ('LOCKED','ACCEPTED','EXPIRED')),
    count            INTEGER NOT NULL,
    locked_until     TIMESTAMPTZ NOT NULL,
    accepted_by      VARCHAR(64),
    accepted_day     DATE
);
-- Lock-expiry job scans LOCKED spends by deadline.
CREATE INDEX spends_locked_until ON spends (locked_until) WHERE status = 'LOCKED';

CREATE TABLE voucher_states (
    serial_hash      BYTEA PRIMARY KEY,                 -- sha256(serial)
    key_id           VARCHAR(64) NOT NULL REFERENCES voucher_keys(key_id),
    state            VARCHAR(8) NOT NULL CHECK (state IN ('LOCKED','SPENT')),
    spend_code_hash  BYTEA NOT NULL REFERENCES spends(spend_code_hash)
);
CREATE INDEX voucher_states_spend ON voucher_states (spend_code_hash);
