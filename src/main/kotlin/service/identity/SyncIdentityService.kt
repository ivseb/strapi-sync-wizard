package it.sebi.service.identity

import it.sebi.database.dbQuery
import it.sebi.models.DbSchema
import it.sebi.models.StrapiInstance
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.slf4j.LoggerFactory

/**
 * Stable, direction-agnostic identity layer (Phase 1).
 *
 * Each Strapi instance carries a `sync_identity` sidecar table in its own Postgres schema:
 *
 *   sync_identity(sync_id uuid, uid, document_id, locale, created_at)
 *
 * The `sync_id` is a shared UUID that identifies the SAME logical entity across instances.
 * It is assigned at the origin by a global Strapi middleware (see the Strapi-side package) and,
 * for content moved by this tool, propagated on apply so identity stays shared (anti-drift).
 *
 * Because the sidecar lives in the instance's `public` schema (not a `strapi_*`/`admin_*`/`up_*`
 * table), it is automatically included in the Postgres snapshots and therefore travels with the
 * air-gap artifact.
 *
 * The match between two instances becomes an EXACT JOIN on `sync_id`; the legacy heuristics
 * (uniqueKey + positional pairing, dHash for files) are demoted to fallback/bootstrap.
 */
object SyncIdentityService {

    private val logger = LoggerFactory.getLogger(SyncIdentityService::class.java)

    const val TABLE = "sync_identity"

    private fun String.sqlEsc(): String = replace("'", "''")

    /** Locale literal for SQL: `'en'` or `NULL`. */
    private fun localeLiteral(locale: String?): String =
        if (locale == null) "NULL" else "'${locale.sqlEsc()}'"

    /**
     * Idempotently create the sidecar table + indexes on the given instance DB.
     * Safe to call repeatedly; cheap (IF NOT EXISTS). Must be called before any read that
     * JOINs `sync_identity`, otherwise the read would fail.
     */
    suspend fun ensureTable(instance: StrapiInstance) {
        val db = instance.database ?: run {
            logger.warn("Cannot ensure $TABLE on '${instance.name}': no DB connection configured")
            return
        }
        dbQuery(db) {
            exec(
                """
                CREATE TABLE IF NOT EXISTS $TABLE (
                    sync_id     uuid        NOT NULL,
                    uid         varchar(255) NOT NULL,
                    document_id varchar(255) NOT NULL,
                    locale      varchar(255),
                    created_at  timestamptz NOT NULL DEFAULT now()
                )
                """.trimIndent()
            )
            // Uniqueness including NULL locales (NULLS treated equal via COALESCE expression index)
            exec("CREATE UNIQUE INDEX IF NOT EXISTS ${TABLE}_doc_locale_ux ON $TABLE (document_id, (COALESCE(locale, '')))")
            exec("CREATE INDEX IF NOT EXISTS ${TABLE}_sync_id_ix ON $TABLE (sync_id)")
            exec("CREATE INDEX IF NOT EXISTS ${TABLE}_uid_ix ON $TABLE (uid)")
        }
    }

    /**
     * Bootstrap: assign a fresh local sync_id to every existing entry that doesn't have one yet.
     * Generates INSTANCE-LOCAL ids (NOT shared across instances yet — that is reconciliation's job).
     * Returns the number of rows inserted.
     */
    suspend fun backfill(instance: StrapiInstance, dbSchema: DbSchema): Int {
        val db = instance.database ?: run {
            logger.warn("Cannot backfill $TABLE on '${instance.name}': no DB connection configured")
            return 0
        }
        ensureTable(instance)
        var inserted = 0
        for (table in dbSchema.tables) {
            val meta = table.metadata ?: continue
            val uid = meta.apiUid
            if (!uid.startsWith("api::")) continue
            val hasDocumentId = table.columns.any { it.name == "document_id" }
            if (!hasDocumentId) continue
            val localeExpr = if (table.columns.any { it.name == "locale" }) "t.locale" else "NULL"
            // Wrap the INSERT in a CTE and SELECT count(*) so we get a reliable inserted-row count
            // (forced as a SELECT statement so the JDBC layer runs executeQuery and hands us a ResultSet).
            val sql = """
                WITH ins AS (
                    INSERT INTO $TABLE (sync_id, uid, document_id, locale)
                    SELECT gen_random_uuid(), '${uid.sqlEsc()}', t.document_id, $localeExpr
                    FROM "${table.name}" t
                    WHERE t.document_id IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM $TABLE si
                          WHERE si.document_id = t.document_id
                            AND si.locale IS NOT DISTINCT FROM $localeExpr
                      )
                    GROUP BY t.document_id, $localeExpr
                    RETURNING 1
                )
                SELECT count(*)::int AS n FROM ins
            """.trimIndent()
            val count = dbQuery(db) {
                exec(sql, explicitStatementType = StatementType.SELECT) { rs ->
                    if (rs.next()) rs.getInt("n") else 0
                }
            }
            inserted += count ?: 0
        }
        logger.info("Backfilled $inserted sync_identity rows on '${instance.name}'")
        return inserted
    }

    /**
     * Upsert a single identity, OVERWRITING any existing sync_id for (document_id, locale).
     * Used for:
     *  - anti-drift after apply (target inherits the SOURCE sync_id), and
     *  - reconciliation (write the canonical sync_id to both sides).
     */
    suspend fun upsertIdentity(
        instance: StrapiInstance,
        uid: String,
        documentId: String,
        locale: String?,
        syncId: String
    ) {
        val db = instance.database ?: return
        val loc = localeLiteral(locale)
        dbQuery(db) {
            exec(
                """
                UPDATE $TABLE
                SET sync_id = '${syncId.sqlEsc()}'::uuid, uid = '${uid.sqlEsc()}'
                WHERE document_id = '${documentId.sqlEsc()}' AND locale IS NOT DISTINCT FROM $loc
                """.trimIndent()
            )
            exec(
                """
                INSERT INTO $TABLE (sync_id, uid, document_id, locale)
                SELECT '${syncId.sqlEsc()}'::uuid, '${uid.sqlEsc()}', '${documentId.sqlEsc()}', $loc
                WHERE NOT EXISTS (
                    SELECT 1 FROM $TABLE
                    WHERE document_id = '${documentId.sqlEsc()}' AND locale IS NOT DISTINCT FROM $loc
                )
                """.trimIndent()
            )
        }
    }

    /** Read the sync_id for a single (document_id, locale) on an instance, or null. */
    suspend fun getSyncId(instance: StrapiInstance, documentId: String, locale: String?): String? {
        val db = instance.database ?: return null
        val loc = localeLiteral(locale)
        return dbQuery(db) {
            exec(
                "SELECT sync_id FROM $TABLE WHERE document_id = '${documentId.sqlEsc()}' AND locale IS NOT DISTINCT FROM $loc LIMIT 1"
            ) { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }
}
