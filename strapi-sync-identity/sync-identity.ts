/**
 * strapi-sync-identity — global Documents middleware (Strapi v5).
 *
 * Assigns a stable, direction-agnostic `sync_id` (UUID) to every api::* entry at creation time,
 * stored in the `sync_identity` sidecar table. ZERO schema changes, ZERO developer involvement.
 *
 * Copy this file next to `src/index.ts` and call `registerSyncIdentity(strapi)` from `register()`.
 *
 * The middleware is idempotent: it never overwrites an existing identity for a (document_id, locale),
 * so it is safe even if the sidecar was pre-populated by the sync tool's backfill/reconciliation.
 */
export function registerSyncIdentity(strapi: any): void {
  strapi.documents.use(async (context: any, next: any) => {
    const result = await next();

    try {
      const action: string = context?.action;
      const uid: string = context?.uid ?? "";

      // Only assign for editor-driven creates of API content types.
      if ((action === "create" || action === "clone") && uid.startsWith("api::")) {
        const documentId: string | undefined = result?.documentId ?? context?.params?.documentId;
        const locale: string | null =
          result?.locale ?? context?.params?.locale ?? null;

        if (documentId) {
          // Insert only if absent — preserves any sync_id already set by the sync tool.
          await strapi.db.connection.raw(
            `INSERT INTO sync_identity (sync_id, uid, document_id, locale)
             SELECT gen_random_uuid(), ?, ?, ?
             WHERE NOT EXISTS (
               SELECT 1 FROM sync_identity
               WHERE document_id = ? AND locale IS NOT DISTINCT FROM ?
             )`,
            [uid, documentId, locale, documentId, locale]
          );
        }
      }
    } catch (e: any) {
      // Never block content operations because of identity bookkeeping.
      strapi.log?.warn?.(`[sync-identity] could not assign sync_id: ${e?.message ?? e}`);
    }

    return result;
  });

  strapi.log?.info?.("[sync-identity] documents middleware registered");
}
