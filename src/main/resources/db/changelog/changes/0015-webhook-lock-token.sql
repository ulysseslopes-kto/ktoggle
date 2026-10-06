--liquibase formatted sql

--changeset ktoggle:0015-webhook-lock-token
-- Identifies the claim that owns a SENDING delivery: a pod whose lock expired (and whose row was claimed again) can
-- neither send it nor record its outcome.
ALTER TABLE webhook_delivery ADD COLUMN lock_token UUID;
