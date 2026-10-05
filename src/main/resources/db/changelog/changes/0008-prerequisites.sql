--liquibase formatted sql

--changeset ktoggle:0008-feature-prerequisites
-- Feature-level prerequisites: [{"featureKey": "...", "condition": {...}}], evaluated against the parent's value.
-- Rule-level prerequisites live inside the rules JSON of feature_environment.
ALTER TABLE feature ADD COLUMN prerequisites JSONB NOT NULL DEFAULT '[]'::jsonb;
