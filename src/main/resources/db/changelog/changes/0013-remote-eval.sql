--liquibase formatted sql

--changeset ktoggle:0013-remote-eval
-- Remote evaluation: SDKs POST their attributes and get evaluated values; rules never leave the server.
ALTER TABLE sdk_connection ADD COLUMN remote_eval BOOLEAN NOT NULL DEFAULT FALSE;
