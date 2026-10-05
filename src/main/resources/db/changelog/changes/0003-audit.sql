--liquibase formatted sql

--changeset ktoggle:0003-immutability-function splitStatements:false
-- Shared guard for append-only tables: any UPDATE/DELETE is rejected at the database level,
-- so immutability does not depend on application code.
CREATE OR REPLACE FUNCTION ktoggle_reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only (% rejected)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

--changeset ktoggle:0003-audit-log
-- Hash-chained control-plane audit trail: hash = sha256(JCS(entry without hash)), entry includes prev_hash.
CREATE TABLE audit_log (
    seq          BIGINT PRIMARY KEY,
    id           UUID         NOT NULL UNIQUE,
    change_id    UUID         NOT NULL,
    actor        VARCHAR(200) NOT NULL,
    action       VARCHAR(50)  NOT NULL,
    entity_type  VARCHAR(50)  NOT NULL,
    entity_key   VARCHAR(200) NOT NULL,
    before_state JSONB,
    after_state  JSONB,
    reason       TEXT,
    occurred_at  TIMESTAMPTZ  NOT NULL,
    prev_hash    VARCHAR(64),
    hash         VARCHAR(64)  NOT NULL UNIQUE
);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_key, seq DESC);
CREATE INDEX audit_log_change_idx ON audit_log (change_id);
CREATE INDEX audit_log_actor_idx ON audit_log (actor, occurred_at DESC);

--changeset ktoggle:0003-audit-log-immutable
CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW EXECUTE FUNCTION ktoggle_reject_mutation();
CREATE TRIGGER feature_revision_append_only BEFORE UPDATE OR DELETE ON feature_revision
    FOR EACH ROW EXECUTE FUNCTION ktoggle_reject_mutation();
