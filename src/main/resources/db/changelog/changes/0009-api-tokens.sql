--liquibase formatted sql

--changeset ktoggle:0009-api-tokens
-- Tokens for automation (CI, scripts). Only the SHA-256 of the secret is stored; the secret is shown once at creation.
CREATE TABLE api_token (
    id           UUID PRIMARY KEY,
    name         VARCHAR(60)  NOT NULL,
    prefix       VARCHAR(20)  NOT NULL,
    token_hash   CHAR(64)     NOT NULL UNIQUE,
    role         VARCHAR(50)  NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    expires_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_by   VARCHAR(255),
    revoked_at   TIMESTAMPTZ
);
-- A name identifies one active token (revoked names can be reused).
CREATE UNIQUE INDEX api_token_active_name_idx ON api_token (name) WHERE revoked_at IS NULL;
