package it.sebi.service.content

import it.sebi.database.dbQuery
import it.sebi.models.DbSchema
import it.sebi.models.DbTable
import it.sebi.models.StrapiContent
import it.sebi.models.StrapiInstance
import it.sebi.service.buildDbSchemaForInstance
import it.sebi.service.contentFingerprint
import it.sebi.service.contentLabelOf
import it.sebi.service.exportSourcePrefetch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.slf4j.LoggerFactory

/** COUNT(*) helper (exec returns Unit in this Exposed version, so DML row counts are taken via COUNT). */
private fun JdbcTransaction.countRows(table: String, where: String): Int {
    var c = 0
    exec("SELECT count(*) AS c FROM $table WHERE $where", explicitStatementType = StatementType.SELECT) { rs ->
        if (rs.next()) c = rs.getInt("c")
    }
    return c
}

/** One content entry inside a duplicate group, with how many other entries reference it. */
@Serializable
data class DupEntryInfo(
    val id: Int,
    val documentId: String,
    val label: String,
    val locale: String? = null,
    val createdAt: String? = null,
    val refs: Int,
)

/** A set of entries considered the same logical content within one collection table.
 *  kind = CERTAIN (identical content fingerprint) | SUSPECT (same natural key, content differs). */
@Serializable
data class ContentDupGroup(
    val key: String,
    val kind: String,
    val label: String,
    val totalRefs: Int,
    val entries: List<DupEntryInfo>,
    val suggestedCanonicalId: Int,
)

/** Detail for a single collection table: its certain/suspect duplicate groups. */
@Serializable
data class ContentTableDuplicatesReport(
    val instanceId: Int,
    val table: String,
    val apiUid: String,
    val displayName: String,
    val totalEntries: Int,
    val certainGroups: List<ContentDupGroup>,
    val suspectGroups: List<ContentDupGroup>,
    val removableEntries: Int,
)

/** One row in the all-tables summary listing only tables that HAVE duplicates. */
@Serializable
data class ContentTableSummary(
    val table: String,
    val apiUid: String,
    val displayName: String,
    val totalEntries: Int,
    val certainGroups: Int,
    val suspectGroups: Int,
    val removableEntries: Int,
)

@Serializable
data class ContentDuplicatesSummary(
    val instanceId: Int,
    val tablesScanned: Int,
    val tables: List<ContentTableSummary>,
)

/** One place where a content entry is used (resolved from a *_lnk table or files morph). */
@Serializable
data class ContentReference(
    val ownerTable: String,
    val ownerId: Int,
    val field: String?,
    val lnkTable: String,
    val ownerLabel: String? = null,
    val ownerDocumentId: String? = null,
)

@Serializable
data class ContentReferencesResponse(val table: String, val entryId: Int, val total: Int, val references: List<ContentReference>)

@Serializable
data class ContentDedupGroupRequest(val canonicalId: Int, val redundantIds: List<Int>)

/** Per-table groups of entries to converge. */
@Serializable
data class ContentDedupTableRequest(val table: String, val groups: List<ContentDedupGroupRequest>)

@Serializable
data class ContentDedupRequest(val tables: List<ContentDedupTableRequest>)

/** A single planned/executed action (for the dry-run plan and the apply report). */
@Serializable
data class ContentDedupAction(
    val table: String,
    val canonicalId: Int,
    val redundantIds: List<Int>,
    val lnkRepoints: Int,
    val collisionsRemoved: Int,
    val entriesDeleted: Int,
    val cmpsDeleted: Int,
    val ownLnkRowsDeleted: Int,
)

@Serializable
data class ContentDedupReport(
    val applied: Boolean,
    val groupsProcessed: Int,
    val refsRepointed: Int,
    val collisionsRemoved: Int,
    val entriesDeleted: Int,
    val backupSchema: String? = null,
    val actions: List<ContentDedupAction> = emptyList(),
)

/**
 * Per-instance CONTENT (collection-type) deduplication.
 *
 * Mirrors [it.sebi.service.media.MediaDeduplicationService] but generalized over content entries and
 * their relation link tables. scanContentDuplicates groups one collection table's entries by a
 * canonical CONTENT FINGERPRINT (true duplicates: same logical content, different documentId) and by
 * natural key (suspects). getContentReferences walks every `*_lnk` table (+ the files morph) that
 * targets the table to list who references an entry. applyContentDedup converges references of the
 * redundant entries onto a chosen canonical entry, then deletes the redundant entries — after backing
 * up the affected tables into a dedicated schema. apply=false is a pure dry-run.
 */
object ContentDeduplicationService {
    private val logger = LoggerFactory.getLogger(ContentDeduplicationService::class.java)

    private val NK_FIELDS = listOf("title", "name", "slug", "code", "label", "uid", "key")

    /** A collection content table participating in dedup (api:: content type, has a primary id). */
    private fun collectionTables(schema: DbSchema): List<DbTable> =
        schema.tables.filter { t ->
            val uid = t.metadata?.apiUid
            uid != null && uid.startsWith("api::") && t.columns.any { it.name == "id" }
        }

    /** The link relations that target [targetTable]: every `*_lnk` table with an FK referencing it.
     *  Returns Triple(lnkTable, targetColumn, ownerColumn?) — ownerColumn is the OTHER FK column (the
     *  owner side); null for self-only / single-column link tables. */
    private data class LnkRel(val lnkTable: String, val targetCol: String, val ownerCol: String?, val ownerTable: String?)

    private fun lnkRelationsTargeting(schema: DbSchema, targetTable: String): List<LnkRel> {
        val rels = mutableListOf<LnkRel>()
        for (t in schema.tables) {
            if (!t.name.endsWith("_lnk")) continue
            val fksToTarget = t.foreignKeys.filter { it.referencedTable == targetTable }
            if (fksToTarget.isEmpty()) continue
            for (fk in fksToTarget) {
                val targetCol = fk.columns.firstOrNull() ?: continue
                // The owner side: the other FK column (could be the same table for self relations).
                val ownerFk = t.foreignKeys.firstOrNull { it != fk } ?: t.foreignKeys.firstOrNull { it == fk && t.foreignKeys.size == 1 }
                val ownerCol = ownerFk?.columns?.firstOrNull()?.takeIf { it != targetCol }
                rels.add(LnkRel(t.name, targetCol, ownerCol, ownerFk?.referencedTable))
            }
        }
        return rels
    }

    /** Suggest the canonical entry: most referenced, then lowest id (oldest). */
    private fun suggestCanonical(group: List<DupEntryInfo>): Int =
        group.maxWith(compareBy({ it.refs }, { -it.id })).id

    /** documentId/id -> inbound reference count, computed once per table by scanning every targeting
     *  lnk table (counts how many lnk rows point at each id of [table]). */
    private suspend fun referenceCountsByEntryId(instance: StrapiInstance, schema: DbSchema, table: String): Map<Int, Int> {
        val rels = lnkRelationsTargeting(schema, table)
        if (rels.isEmpty()) return emptyMap()
        val counts = mutableMapOf<Int, Int>()
        dbQuery(instance.database) {
            for (rel in rels) {
                try {
                    exec(
                        "SELECT \"${rel.targetCol}\" AS tid, count(*) AS c FROM \"${rel.lnkTable}\" WHERE \"${rel.targetCol}\" IS NOT NULL GROUP BY \"${rel.targetCol}\"",
                        explicitStatementType = StatementType.SELECT,
                    ) { rs ->
                        while (rs.next()) {
                            val tid = rs.getInt("tid")
                            counts.merge(tid, rs.getInt("c"), Int::plus)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Could not count refs from ${rel.lnkTable}.${rel.targetCol} on '${instance.name}': ${e.message}")
                }
            }
        }
        return counts
    }

    private fun localeOf(c: StrapiContent): String? = c.metadata.locale
    private fun createdAtOf(c: StrapiContent): String? =
        (c.rawData["created_at"] ?: c.rawData["createdAt"])?.jsonPrimitive?.contentOrNull

    /** Build duplicate groups for one already-loaded set of entries. */
    private fun buildGroups(
        entries: List<StrapiContent>,
        refCounts: Map<Int, Int>,
    ): Pair<List<ContentDupGroup>, List<ContentDupGroup>> {
        fun info(c: StrapiContent): DupEntryInfo? {
            val id = c.metadata.id ?: return null
            return DupEntryInfo(
                id = id,
                documentId = c.metadata.documentId,
                label = contentLabelOf(c),
                locale = localeOf(c),
                createdAt = createdAtOf(c),
                refs = refCounts[id] ?: 0,
            )
        }

        // Fingerprint with an EMPTY identity map: relations normalize to "doc:<documentId>". Two entries
        // are CERTAIN duplicates only when content AND the referenced documentIds match.
        val byFp = entries.groupBy { contentFingerprint(it, emptyMap()) }

        val certain = byFp.filter { it.value.size > 1 }
            .mapNotNull { (fp, list) ->
                val infos = list.mapNotNull { info(it) }
                if (infos.size < 2) return@mapNotNull null
                ContentDupGroup(
                    key = "fp:$fp",
                    kind = "CERTAIN",
                    label = infos.first().label,
                    totalRefs = infos.sumOf { it.refs },
                    entries = infos.sortedByDescending { it.refs },
                    suggestedCanonicalId = suggestCanonical(infos),
                )
            }
            .sortedByDescending { it.entries.size }

        val coveredDocs = certain.flatMap { it.entries }.map { it.documentId }.toSet()

        fun nk(c: StrapiContent): String? {
            for (f in NK_FIELDS) {
                val v = (c.cleanData[f] as? JsonPrimitive)?.contentOrNull
                if (!v.isNullOrBlank()) return "$f=${v.trim().lowercase()}"
            }
            return null
        }

        val suspect = entries.filter { it.metadata.documentId !in coveredDocs }
            .mapNotNull { c -> nk(c)?.let { it to c } }
            .groupBy({ it.first }, { it.second })
            .filter { it.value.size > 1 }
            .mapNotNull { (k, list) ->
                val infos = list.mapNotNull { info(it) }
                if (infos.size < 2) return@mapNotNull null
                ContentDupGroup(
                    key = "nk:$k",
                    kind = "SUSPECT",
                    label = infos.first().label,
                    totalRefs = infos.sumOf { it.refs },
                    entries = infos.sortedByDescending { it.refs },
                    suggestedCanonicalId = suggestCanonical(infos),
                )
            }
            .sortedByDescending { it.entries.size }

        return certain to suspect
    }

    /** Scan ONE collection table for duplicate entries. Read-only. */
    suspend fun scanContentDuplicates(instance: StrapiInstance, table: String): ContentTableDuplicatesReport {
        require(!instance.isVirtual) { "Content deduplication requires a real (non-virtual) instance" }
        val schema = buildDbSchemaForInstance(instance) ?: error("Could not build DB schema for '${instance.name}'")
        val dbTable = collectionTables(schema).firstOrNull { it.name == table }
            ?: error("Table '$table' is not a known collection content table")
        val uid = dbTable.metadata!!.apiUid

        val prefetch = exportSourcePrefetch(instance, schema)
        val entries = prefetch.sourceRowsByUid[uid] ?: emptyList()
        val refCounts = referenceCountsByEntryId(instance, schema, table)
        val (certain, suspect) = buildGroups(entries, refCounts)
        val removable = certain.sumOf { it.entries.size - 1 } + suspect.sumOf { it.entries.size - 1 }

        return ContentTableDuplicatesReport(
            instanceId = instance.id,
            table = table,
            apiUid = uid,
            displayName = dbTable.metadata!!.displayName,
            totalEntries = entries.size,
            certainGroups = certain,
            suspectGroups = suspect,
            removableEntries = removable,
        )
    }

    /** Scan every collection table; return only those that HAVE duplicate groups. Read-only. */
    suspend fun scanAllContentDuplicates(instance: StrapiInstance): ContentDuplicatesSummary {
        require(!instance.isVirtual) { "Content deduplication requires a real (non-virtual) instance" }
        val schema = buildDbSchemaForInstance(instance) ?: error("Could not build DB schema for '${instance.name}'")
        val tables = collectionTables(schema)
        val prefetch = exportSourcePrefetch(instance, schema)

        val summaries = mutableListOf<ContentTableSummary>()
        for (t in tables) {
            val uid = t.metadata!!.apiUid
            val entries = prefetch.sourceRowsByUid[uid] ?: continue
            if (entries.size < 2) continue
            val refCounts = referenceCountsByEntryId(instance, schema, t.name)
            val (certain, suspect) = buildGroups(entries, refCounts)
            if (certain.isEmpty() && suspect.isEmpty()) continue
            val removable = certain.sumOf { it.entries.size - 1 } + suspect.sumOf { it.entries.size - 1 }
            summaries.add(
                ContentTableSummary(
                    table = t.name,
                    apiUid = uid,
                    displayName = t.metadata!!.displayName,
                    totalEntries = entries.size,
                    certainGroups = certain.size,
                    suspectGroups = suspect.size,
                    removableEntries = removable,
                )
            )
        }
        summaries.sortByDescending { it.removableEntries }
        logger.info("Content dedup scan '${instance.name}': ${tables.size} tables scanned, ${summaries.size} with duplicates")
        return ContentDuplicatesSummary(instanceId = instance.id, tablesScanned = tables.size, tables = summaries)
    }

    /** List who references one entry: every targeting `*_lnk` table (+ files morph). Read-only. */
    suspend fun getContentReferences(instance: StrapiInstance, table: String, entryId: Int): ContentReferencesResponse {
        require(!instance.isVirtual) { "Content deduplication requires a real (non-virtual) instance" }
        val schema = buildDbSchemaForInstance(instance) ?: error("Could not build DB schema for '${instance.name}'")
        val rels = lnkRelationsTargeting(schema, table)
        val byName = schema.tables.associateBy { it.name }
        val labelCols = listOf("title", "name", "label", "slug", "code", "display_name", "heading")
        val refs = mutableListOf<ContentReference>()

        dbQuery(instance.database) {
            for (rel in rels) {
                val ownerCol = rel.ownerCol
                // Derive the relation field from the lnk table name when possible (best effort).
                val selCols = listOfNotNull(rel.targetCol, ownerCol).joinToString(",") { "\"$it\"" }
                try {
                    val owners = mutableListOf<Pair<Int?, Int>>() // ownerId? to (always-present) targetCol value
                    exec(
                        "SELECT $selCols FROM \"${rel.lnkTable}\" WHERE \"${rel.targetCol}\" = $entryId",
                        explicitStatementType = StatementType.SELECT,
                    ) { rs ->
                        while (rs.next()) {
                            val oid = if (ownerCol != null) rs.getInt(ownerCol).let { if (rs.wasNull()) null else it } else null
                            owners.add(oid to rs.getInt(rel.targetCol))
                        }
                    }
                    // Resolve owner labels.
                    val ownerTable = rel.ownerTable?.let { byName[it] }
                    val labelMap = mutableMapOf<Int, Pair<String?, String?>>()
                    val ownerIds = owners.mapNotNull { it.first }.distinct()
                    if (ownerTable != null && ownerIds.isNotEmpty()) {
                        val cols = ownerTable.columns.map { it.name }.toSet()
                        val lbl = labelCols.firstOrNull { it in cols }
                        val hasDoc = "document_id" in cols
                        val osel = listOfNotNull("id", lbl, if (hasDoc) "document_id" else null).joinToString(",") { "\"$it\"" }
                        try {
                            exec("SELECT $osel FROM \"${ownerTable.name}\" WHERE id IN (${ownerIds.joinToString(",")})", explicitStatementType = StatementType.SELECT) { rs ->
                                while (rs.next()) {
                                    val oid = rs.getInt("id")
                                    labelMap[oid] = (lbl?.let { rs.getString(it) }) to (if (hasDoc) rs.getString("document_id") else null)
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn("Could not resolve owner labels for ${ownerTable.name}: ${e.message}")
                        }
                    }
                    val field = rel.ownerCol?.removeSuffix("_id")
                    for ((oid, _) in owners) {
                        val (lbl, doc) = oid?.let { labelMap[it] } ?: (null to null)
                        refs.add(ContentReference(rel.ownerTable ?: rel.lnkTable, oid ?: -1, field, rel.lnkTable, lbl, doc))
                    }
                } catch (e: Exception) {
                    logger.warn("Could not read references from ${rel.lnkTable} on '${instance.name}': ${e.message}")
                }
            }

            // Files morph: an entry referencing files won't be a target here; but if other content
            // references THIS entry's documentId via files_related_mph that is handled by lnk tables.
            // The files morph targets content (related_id/related_type) for FILE usage, not the reverse,
            // so it is not an inbound reference to a content entry. Skipped intentionally.
        }

        return ContentReferencesResponse(table, entryId, refs.size, refs)
    }

    /**
     * Converge every reference of the redundant entries onto the chosen canonical entry, then delete
     * the redundant entries (their own component `*_cmps` rows and own `*_lnk` rows too). Affected
     * tables are backed up into a dedicated schema first. With apply=false nothing is written
     * (dry-run): the report shows the full planned action set.
     */
    suspend fun applyContentDedup(instance: StrapiInstance, req: ContentDedupRequest, apply: Boolean): ContentDedupReport {
        require(!instance.isVirtual) { "Content deduplication requires a real (non-virtual) instance" }
        val schema = buildDbSchemaForInstance(instance) ?: error("Could not build DB schema for '${instance.name}'")
        val byName = schema.tables.associateBy { it.name }

        // Validate + normalize the request.
        data class Job(val table: String, val groups: List<ContentDedupGroupRequest>)
        val jobs = req.tables.mapNotNull { tr ->
            val groups = tr.groups.filter { it.redundantIds.isNotEmpty() }
            groups.forEach { require(it.canonicalId !in it.redundantIds) { "Canonical ${it.canonicalId} cannot be in its own redundant list (table ${tr.table})" } }
            collectionTables(schema).firstOrNull { it.name == tr.table }
                ?: error("Table '${tr.table}' is not a known collection content table")
            if (groups.isEmpty()) null else Job(tr.table, groups)
        }
        if (jobs.isEmpty()) return ContentDedupReport(applied = apply, groupsProcessed = 0, refsRepointed = 0, collisionsRemoved = 0, entriesDeleted = 0)

        val backup = if (apply) "content_dedup_backup_${System.currentTimeMillis()}" else null
        val actions = mutableListOf<ContentDedupAction>()
        var totalRepointed = 0
        var totalCollisions = 0
        var totalDeleted = 0
        var totalGroups = 0

        dbQuery(instance.database) {
            if (apply) exec("CREATE SCHEMA \"$backup\"")

            for (job in jobs) {
                val rels = lnkRelationsTargeting(schema, job.table)
                // Tables this entry OWNS rows in: its own `*_lnk` (owner side) and `*_cmps`.
                val cmpsTable = byName["${job.table}_cmps"]?.name
                    ?: schema.tables.firstOrNull { it.name == "${job.table}_cmps" }?.name

                // Back up affected tables once (the entry table, every targeting lnk, the cmps table).
                if (apply) {
                    val affected = (listOf(job.table) + rels.map { it.lnkTable } + listOfNotNull(cmpsTable)).distinct()
                    for (tname in affected) {
                        val safe = tname.replace("\"", "")
                        try {
                            exec("CREATE TABLE \"$backup\".\"$safe\" AS SELECT * FROM public.\"$safe\"")
                        } catch (e: Exception) {
                            logger.warn("Backup of $safe skipped: ${e.message}")
                        }
                    }
                }

                for (g in job.groups) {
                    totalGroups++
                    val redCsv = g.redundantIds.joinToString(",")
                    var repointed = 0
                    var collisions = 0

                    // Count + repoint inbound references in every targeting lnk table.
                    for (rel in rels) {
                        try {
                            exec(
                                "SELECT count(*) AS c FROM \"${rel.lnkTable}\" WHERE \"${rel.targetCol}\" IN ($redCsv)",
                                explicitStatementType = StatementType.SELECT,
                            ) { rs -> if (rs.next()) repointed += rs.getInt("c") }
                        } catch (_: Exception) {}

                        if (apply) {
                            try {
                                exec("UPDATE \"${rel.lnkTable}\" SET \"${rel.targetCol}\" = ${g.canonicalId} WHERE \"${rel.targetCol}\" IN ($redCsv)")
                                // Dedup collisions: identical (owner, target) rows after repoint.
                                val ownerCol = rel.ownerCol
                                if (ownerCol != null) {
                                    exec(
                                        "SELECT count(*) AS c FROM (SELECT 1 FROM \"${rel.lnkTable}\" a JOIN \"${rel.lnkTable}\" b ON a.\"${rel.targetCol}\" = ${g.canonicalId} AND b.\"${rel.targetCol}\" = ${g.canonicalId} AND a.\"$ownerCol\" IS NOT DISTINCT FROM b.\"$ownerCol\" AND a.id > b.id) s",
                                        explicitStatementType = StatementType.SELECT,
                                    ) { rs -> if (rs.next()) collisions += rs.getInt("c") }
                                    exec(
                                        """
                                        DELETE FROM "${rel.lnkTable}" a USING "${rel.lnkTable}" b
                                        WHERE a."${rel.targetCol}" = ${g.canonicalId} AND b."${rel.targetCol}" = ${g.canonicalId}
                                          AND a."$ownerCol" IS NOT DISTINCT FROM b."$ownerCol"
                                          AND a.id > b.id
                                        """.trimIndent()
                                    )
                                }
                            } catch (e: Exception) {
                                logger.warn("Repoint failed on ${rel.lnkTable}: ${e.message}")
                            }
                        }
                    }

                    // Delete the redundant entries' own rows: their cmps rows, their owner-side lnk rows,
                    // then the entry rows themselves.
                    var cmpsDeleted = 0
                    var ownLnkDeleted = 0
                    var entriesDeleted = 0

                    // Own lnk rows: any lnk table whose OWNER side FK references this table.
                    val ownLnks = schema.tables.filter { it.name.endsWith("_lnk") }
                        .flatMap { t -> t.foreignKeys.filter { it.referencedTable == job.table }.map { t.name to it.columns.first() } }
                    if (apply) {
                        for ((ltable, col) in ownLnks) {
                            try {
                                ownLnkDeleted += countRows("\"$ltable\"", "\"$col\" IN ($redCsv)")
                                exec("DELETE FROM \"$ltable\" WHERE \"$col\" IN ($redCsv)")
                            } catch (e: Exception) {
                                logger.warn("Delete own lnk rows failed on $ltable: ${e.message}")
                            }
                        }
                        if (cmpsTable != null) {
                            try {
                                cmpsDeleted += countRows("\"$cmpsTable\"", "entity_id IN ($redCsv)")
                                exec("DELETE FROM \"$cmpsTable\" WHERE entity_id IN ($redCsv)")
                            } catch (e: Exception) {
                                logger.warn("Delete cmps rows failed on $cmpsTable: ${e.message}")
                            }
                        }
                        try {
                            entriesDeleted += countRows("\"${job.table}\"", "id IN ($redCsv)")
                            exec("DELETE FROM \"${job.table}\" WHERE id IN ($redCsv)")
                        } catch (e: Exception) {
                            logger.warn("Delete entry rows failed on ${job.table}: ${e.message}")
                        }
                    } else {
                        entriesDeleted = g.redundantIds.size
                    }

                    totalRepointed += repointed
                    totalCollisions += collisions
                    totalDeleted += if (apply) entriesDeleted else g.redundantIds.size
                    actions.add(
                        ContentDedupAction(
                            table = job.table,
                            canonicalId = g.canonicalId,
                            redundantIds = g.redundantIds,
                            lnkRepoints = repointed,
                            collisionsRemoved = collisions,
                            entriesDeleted = if (apply) entriesDeleted else g.redundantIds.size,
                            cmpsDeleted = cmpsDeleted,
                            ownLnkRowsDeleted = ownLnkDeleted,
                        )
                    )
                }
            }
        }

        logger.info("Content dedup ${if (apply) "applied" else "dry-run"} on '${instance.name}': $totalGroups groups, $totalRepointed refs repointed, $totalDeleted entries removed, backup=$backup")
        return ContentDedupReport(
            applied = apply,
            groupsProcessed = totalGroups,
            refsRepointed = totalRepointed,
            collisionsRemoved = totalCollisions,
            entriesDeleted = totalDeleted,
            backupSchema = backup,
            actions = actions,
        )
    }
}
