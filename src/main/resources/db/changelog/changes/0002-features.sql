--liquibase formatted sql

--changeset ktoggle:0002-feature
CREATE TABLE feature (
    id            UUID PRIMARY KEY,
    key           VARCHAR(150) NOT NULL UNIQUE,
    project_key   VARCHAR(100) REFERENCES project (key),
    value_type    VARCHAR(20)  NOT NULL,
    default_value JSONB        NOT NULL,
    description   TEXT,
    owner         VARCHAR(200),
    tags          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    archived      BOOLEAN      NOT NULL DEFAULT FALSE,
    revision      INT          NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(200) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(200) NOT NULL,
    version       BIGINT       NOT NULL
);
CREATE INDEX feature_project_idx ON feature (project_key);

--changeset ktoggle:0002-feature-environment
-- Keys (environment, project, saved group) are immutable, so they are used as references directly.
-- Rules are an ordered list of a sealed type (force | rollout; experiment in phase 2), stored as JSONB.
CREATE TABLE feature_environment (
    id             UUID PRIMARY KEY,
    feature_id     UUID    NOT NULL REFERENCES feature (id) ON DELETE CASCADE,
    environment_key VARCHAR(50) NOT NULL REFERENCES environment (key),
    enabled        BOOLEAN NOT NULL DEFAULT FALSE,
    rules          JSONB   NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT feature_environment_uk UNIQUE (feature_id, environment_key)
);

--changeset ktoggle:0002-feature-revision
CREATE TABLE feature_revision (
    id          UUID PRIMARY KEY,
    feature_id  UUID         NOT NULL REFERENCES feature (id),
    feature_key VARCHAR(150) NOT NULL,
    revision    INT          NOT NULL,
    snapshot    JSONB        NOT NULL,
    change_id   UUID         NOT NULL,
    comment     TEXT,
    created_by  VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT feature_revision_uk UNIQUE (feature_id, revision)
);
