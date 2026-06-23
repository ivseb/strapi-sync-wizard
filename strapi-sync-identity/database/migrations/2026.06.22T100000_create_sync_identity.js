"use strict";

/**
 * strapi-sync-identity — create the `sync_identity` sidecar table.
 *
 * This is a plain table (NOT a Strapi content-type): Strapi neither exposes nor manages it.
 * It lives in the `public` schema so it travels with the Postgres snapshots taken by the sync tool.
 */
module.exports = {
  async up(knex) {
    const exists = await knex.schema.hasTable("sync_identity");
    if (!exists) {
      await knex.schema.createTable("sync_identity", (t) => {
        t.uuid("sync_id").notNullable();
        t.string("uid", 255).notNullable();
        t.string("document_id", 255).notNullable();
        t.string("locale", 255).nullable();
        t.timestamp("created_at", { useTz: true }).notNullable().defaultTo(knex.fn.now());
      });
    }
    // Uniqueness including NULL locales (treated equal via COALESCE expression index).
    await knex.raw(
      `CREATE UNIQUE INDEX IF NOT EXISTS sync_identity_doc_locale_ux ON sync_identity (document_id, (COALESCE(locale, '')))`
    );
    await knex.raw(`CREATE INDEX IF NOT EXISTS sync_identity_sync_id_ix ON sync_identity (sync_id)`);
    await knex.raw(`CREATE INDEX IF NOT EXISTS sync_identity_uid_ix ON sync_identity (uid)`);
  },

  async down(knex) {
    await knex.schema.dropTableIfExists("sync_identity");
  },
};
