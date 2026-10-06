--liquibase formatted sql

--changeset ktoggle:0014-shadow-runs
-- Shadow comparison results: what GrowthBook and ktoggle would serve to the same simulated users, per client key.
-- Attributes in the examples are simulated, never real user data.
CREATE TABLE shadow_run (
    id                 UUID PRIMARY KEY,
    client_key         VARCHAR(100) NOT NULL,
    started_at         TIMESTAMPTZ  NOT NULL,
    duration_ms        BIGINT       NOT NULL,
    status             VARCHAR(30)  NOT NULL,
    bundle_hash        VARCHAR(64),
    samples            INT          NOT NULL,
    features_compared  INT          NOT NULL,
    divergent_features INT          NOT NULL,
    divergences        JSONB        NOT NULL,
    error              VARCHAR(500),
    triggered_by       VARCHAR(255) NOT NULL
);
CREATE INDEX shadow_run_client_idx ON shadow_run (client_key, started_at DESC);
