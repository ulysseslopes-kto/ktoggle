--liquibase formatted sql

--changeset ktoggle:0001-project
CREATE TABLE project (
    id          UUID PRIMARY KEY,
    key         VARCHAR(100) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL
);

--changeset ktoggle:0001-environment
CREATE TABLE environment (
    id          UUID PRIMARY KEY,
    key         VARCHAR(50)  NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL
);

--changeset ktoggle:0001-attribute
-- "pii" drives LGPD handling: PII attributes are never stored in clear in decision events.
CREATE TABLE attribute (
    id             UUID PRIMARY KEY,
    key            VARCHAR(100) NOT NULL UNIQUE,
    datatype       VARCHAR(20)  NOT NULL,
    description    TEXT,
    hash_attribute BOOLEAN      NOT NULL DEFAULT FALSE,
    pii            BOOLEAN      NOT NULL DEFAULT TRUE,
    enum_values    JSONB,
    archived       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL
);

--changeset ktoggle:0001-saved-group
CREATE TABLE saved_group (
    id            UUID PRIMARY KEY,
    key           VARCHAR(100) NOT NULL UNIQUE,
    name          VARCHAR(200) NOT NULL,
    description   TEXT,
    type          VARCHAR(20)  NOT NULL,
    attribute_key VARCHAR(100),
    list_values   JSONB,
    condition     JSONB,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL,
    CONSTRAINT saved_group_shape CHECK (
        (type = 'LIST' AND attribute_key IS NOT NULL AND list_values IS NOT NULL AND condition IS NULL)
     OR (type = 'CONDITION' AND condition IS NOT NULL AND attribute_key IS NULL AND list_values IS NULL))
);

--changeset ktoggle:0001-sdk-connection
CREATE TABLE sdk_connection (
    id                 UUID PRIMARY KEY,
    name               VARCHAR(200) NOT NULL,
    client_key         VARCHAR(100) NOT NULL UNIQUE,
    environment_key    VARCHAR(50)  NOT NULL REFERENCES environment (key),
    project_keys       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    -- When set, automatic publication is suspended and this bundle stays active (emergency rollback).
    pinned_bundle_hash VARCHAR(64),
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    version            BIGINT       NOT NULL
);
