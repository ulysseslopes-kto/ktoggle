--liquibase formatted sql

--changeset ktoggle:0011-project-editors
-- Per-project permissions: when either list is non-empty, only those Keycloak roles / users (and admins) can change
-- the project's features. Empty lists keep the project open to every editor.
ALTER TABLE project ADD COLUMN editor_roles JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE project ADD COLUMN editor_users JSONB NOT NULL DEFAULT '[]'::jsonb;
