--liquibase formatted sql

--changeset ktoggle:0005-decision-event
-- Opt-in decision events reported by SDK featureUsage callbacks. Partitioned monthly by occurred_at (client-provided and stable across retries,
-- so (event_id, occurred_at) makes ingestion idempotent; skewed timestamps are rejected on ingest);
-- partitions are created ahead of time by DecisionPartitionMaintainer. Only non-PII attributes are kept
-- in clear; the full attribute set is represented by an HMAC (attributes_digest) so a claimed attribute
-- set can be verified later without storing it.
CREATE TABLE decision_event (
    event_id          UUID         NOT NULL,
    client_key        VARCHAR(100) NOT NULL,
    bundle_hash       VARCHAR(64)  NOT NULL,
    feature_key       VARCHAR(150) NOT NULL,
    value             JSONB,
    rule_id           VARCHAR(100),
    source            VARCHAR(50),
    sdk               VARCHAR(100),
    occurred_at       TIMESTAMPTZ  NOT NULL,
    received_at       TIMESTAMPTZ  NOT NULL,
    attributes        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    attributes_digest VARCHAR(64)  NOT NULL,
    digest_key_id     VARCHAR(50)  NOT NULL,
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);
CREATE TABLE decision_event_default PARTITION OF decision_event DEFAULT;
CREATE INDEX decision_event_feature_idx ON decision_event (feature_key, occurred_at DESC);
CREATE INDEX decision_event_client_idx ON decision_event (client_key, occurred_at DESC);
