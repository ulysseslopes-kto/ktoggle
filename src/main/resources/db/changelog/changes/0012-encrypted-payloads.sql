--liquibase formatted sql

--changeset ktoggle:0012-encrypted-payloads
-- GrowthBook-compatible encrypted payloads (AES-128-CBC). The key is given to the SDKs of this connection
-- (decryptionKey / encryptionKey); bundles stay in clear text, signed and replayable.
ALTER TABLE sdk_connection ADD COLUMN encrypt_payload BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sdk_connection ADD COLUMN decryption_key VARCHAR(64);
