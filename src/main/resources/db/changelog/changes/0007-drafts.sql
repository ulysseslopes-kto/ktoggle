--liquibase formatted sql

--changeset ktoggle:0007-environment-requires-review
-- Publishing a draft that touches an environment with requires_review needs an approval (four-eyes).
ALTER TABLE environment ADD COLUMN requires_review BOOLEAN NOT NULL DEFAULT FALSE;

--changeset ktoggle:0007-review-settings
-- Singleton (id = 1): who may approve drafts and how. Edited by admins only, every change audited.
CREATE TABLE review_settings (
    id                     INT PRIMARY KEY CHECK (id = 1),
    approver_roles         JSONB        NOT NULL,
    approver_users         JSONB        NOT NULL,
    allow_self_approval    BOOLEAN      NOT NULL,
    reset_review_on_change BOOLEAN      NOT NULL,
    bypass_enabled         BOOLEAN      NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    updated_by             VARCHAR(200) NOT NULL,
    version                BIGINT       NOT NULL
);
INSERT INTO review_settings VALUES (1, '["ktoggle-admin","ktoggle-approver"]'::jsonb, '[]'::jsonb, FALSE, TRUE, TRUE,
                                    now(), 'system:migration', 0);

--changeset ktoggle:0007-feature-draft
-- Every change to an existing feature is staged here; only publishing a draft changes what SDKs receive.
CREATE TABLE feature_draft (
    id                 UUID PRIMARY KEY,
    feature_key        VARCHAR(150) NOT NULL REFERENCES feature (key),
    title              VARCHAR(300),
    base_revision      INT          NOT NULL,
    status             VARCHAR(30)  NOT NULL,
    proposed           JSONB        NOT NULL,
    created_by         VARCHAR(200) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(200) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    published_revision INT,
    version            BIGINT       NOT NULL
);
CREATE INDEX feature_draft_feature_idx ON feature_draft (feature_key, status);
CREATE INDEX feature_draft_status_idx ON feature_draft (status, updated_at DESC);

--changeset ktoggle:0007-draft-event
-- Append-only conversation/lifecycle log of a draft (requests, approvals, comments, publication).
CREATE TABLE draft_event (
    id          UUID PRIMARY KEY,
    draft_id    UUID         NOT NULL REFERENCES feature_draft (id),
    type        VARCHAR(30)  NOT NULL,
    actor       VARCHAR(200) NOT NULL,
    comment     TEXT,
    occurred_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX draft_event_draft_idx ON draft_event (draft_id, occurred_at);
CREATE TRIGGER draft_event_append_only BEFORE UPDATE OR DELETE ON draft_event
    FOR EACH ROW EXECUTE FUNCTION ktoggle_reject_mutation();
