--liquibase formatted sql

--changeset ktoggle:0004-bundle
-- Content-addressed, immutable bundle. "content" is the RFC 8785 canonical body and hash = sha256(content).
-- Envelope fields (signature, created_*) are deliberately outside the hash.
CREATE TABLE bundle (
    hash             VARCHAR(64)  PRIMARY KEY,
    contract_version VARCHAR(50)  NOT NULL,
    client_key       VARCHAR(100) NOT NULL,
    environment_key  VARCHAR(50)  NOT NULL,
    content          TEXT         NOT NULL,
    -- sha256(JCS(payload)) — lets the publisher skip bundles whose SDK payload would not change.
    payload_hash     VARCHAR(64)  NOT NULL,
    signature_alg    VARCHAR(50)  NOT NULL,
    key_id           VARCHAR(300) NOT NULL,
    signature        TEXT         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(200) NOT NULL
);
CREATE INDEX bundle_client_idx ON bundle (client_key, created_at DESC);
CREATE TRIGGER bundle_append_only BEFORE UPDATE OR DELETE ON bundle
    FOR EACH ROW EXECUTE FUNCTION ktoggle_reject_mutation();

--changeset ktoggle:0004-bundle-activation
-- Per-connection hash chain of which bundle was active from when, by whom and why.
CREATE TABLE bundle_activation (
    id           UUID PRIMARY KEY,
    client_key   VARCHAR(100) NOT NULL,
    position     BIGINT       NOT NULL,
    bundle_hash  VARCHAR(64)  NOT NULL REFERENCES bundle (hash),
    kind         VARCHAR(20)  NOT NULL,
    change_id    UUID         NOT NULL,
    activated_by VARCHAR(200) NOT NULL,
    activated_at TIMESTAMPTZ  NOT NULL,
    reason       TEXT,
    prev_hash    VARCHAR(64),
    hash         VARCHAR(64)  NOT NULL UNIQUE,
    CONSTRAINT bundle_activation_uk UNIQUE (client_key, position)
);
CREATE INDEX bundle_activation_time_idx ON bundle_activation (client_key, activated_at DESC);
CREATE TRIGGER bundle_activation_append_only BEFORE UPDATE OR DELETE ON bundle_activation
    FOR EACH ROW EXECUTE FUNCTION ktoggle_reject_mutation();

--changeset ktoggle:0004-bundle-archive
-- Tracks the WORM (S3 Object Lock) copy of each bundle. Separate table because bundle is immutable.
CREATE TABLE bundle_archive (
    bundle_hash VARCHAR(64)  PRIMARY KEY REFERENCES bundle (hash),
    location    VARCHAR(500) NOT NULL,
    archived_at TIMESTAMPTZ  NOT NULL
);

--changeset ktoggle:0004-delivery-log
-- Aggregated per (client, bundle, channel, pod, hour window). No IP addresses are stored (LGPD).
CREATE TABLE delivery_log (
    id           UUID PRIMARY KEY,
    client_key   VARCHAR(100) NOT NULL,
    bundle_hash  VARCHAR(64)  NOT NULL,
    channel      VARCHAR(20)  NOT NULL,
    pod          VARCHAR(200) NOT NULL,
    window_start TIMESTAMPTZ  NOT NULL,
    first_seen   TIMESTAMPTZ  NOT NULL,
    last_seen    TIMESTAMPTZ  NOT NULL,
    deliveries   BIGINT       NOT NULL,
    sdk_hint     VARCHAR(100),
    CONSTRAINT delivery_log_uk UNIQUE (client_key, bundle_hash, channel, pod, window_start)
);
CREATE INDEX delivery_log_client_idx ON delivery_log (client_key, last_seen DESC);
