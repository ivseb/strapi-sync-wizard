-- strapi-sync-identity — manual creation of the sidecar table (alternative to the knex migration).
-- Run against each Strapi instance's database (public schema). Idempotent.

CREATE TABLE IF NOT EXISTS sync_identity (
    sync_id     uuid         NOT NULL,
    uid         varchar(255) NOT NULL,
    document_id varchar(255) NOT NULL,
    locale      varchar(255),
    created_at  timestamptz  NOT NULL DEFAULT now()
);

-- Uniqueness including NULL locales (treated equal via COALESCE expression index).
CREATE UNIQUE INDEX IF NOT EXISTS sync_identity_doc_locale_ux ON sync_identity (document_id, (COALESCE(locale, '')));
CREATE INDEX IF NOT EXISTS sync_identity_sync_id_ix ON sync_identity (sync_id);
CREATE INDEX IF NOT EXISTS sync_identity_uid_ix ON sync_identity (uid);
