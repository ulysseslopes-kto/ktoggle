--liquibase formatted sql

--changeset ktoggle:0010-webhooks
-- Outgoing notifications. The secret signs every delivery (HMAC-SHA256) so receivers can verify the origin.
CREATE TABLE webhook (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    url         VARCHAR(2000) NOT NULL,
    format      VARCHAR(20)   NOT NULL,
    events      JSONB         NOT NULL,
    secret      VARCHAR(100)  NOT NULL,
    enabled     BOOLEAN       NOT NULL,
    created_by  VARCHAR(255)  NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_by  VARCHAR(255)  NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    version     BIGINT        NOT NULL
);

-- Transactional outbox: rows are written in the same transaction as the change they announce, then delivered
-- (with retries) by whichever pod claims them first (FOR UPDATE SKIP LOCKED).
CREATE TABLE webhook_delivery (
    id               UUID PRIMARY KEY,
    webhook_id       UUID         NOT NULL REFERENCES webhook (id) ON DELETE CASCADE,
    event            VARCHAR(60)  NOT NULL,
    payload          JSONB        NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    attempts         INT          NOT NULL,
    next_attempt_at  TIMESTAMPTZ  NOT NULL,
    locked_until     TIMESTAMPTZ,
    last_status_code INT,
    last_error       VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL,
    delivered_at     TIMESTAMPTZ
);
CREATE INDEX webhook_delivery_due_idx ON webhook_delivery (status, next_attempt_at);
CREATE INDEX webhook_delivery_webhook_idx ON webhook_delivery (webhook_id, created_at DESC);
