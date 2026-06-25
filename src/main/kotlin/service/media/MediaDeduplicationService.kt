package it.sebi.service.media

import it.sebi.database.dbQuery
import it.sebi.models.StrapiInstance
import it.sebi.service.computeFingerprints
import it.sebi.service.fetchFilesFromDb
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.slf4j.LoggerFactory

/** One physical file in a duplicate group, with how many content entries reference it. */
@Serializable
data class DupFileInfo(
    val id: Int,
    val documentId: String,
    val name: String,
    val ext: String,
    val sizeBytes: Long,
    val folder: String,
    val mime: String,
    val url: String,
    val sha: String?,
    val refs: Int,
    val refsByType: Map<String, Int>,
)

/** A set of files considered the same asset. kind = CERTAIN (identical bytes) | SUSPECT (same name, different bytes). */
@Serializable
data class DupGroup(
    val key: String,
    val kind: String,
    val sha: String?,
    val name: String,
    val totalRefs: Int,
    val files: List<DupFileInfo>,
    val suggestedCanonicalId: Int,
)

@Serializable
data class MediaDuplicatesReport(
    val instanceId: Int,
    val totalFiles: Int,
    val certainGroups: List<DupGroup>,
    val suspectGroups: List<DupGroup>,
    val removableCopies: Int,
)

/** One place where a file is used (resolved from files_related_mph). */
@Serializable
data class FileReference(
    val relatedType: String,
    val field: String?,
    val relatedId: Int,
    val label: String?,
    val documentId: String?,
    val isComponent: Boolean,
)

@Serializable
data class FileReferencesResponse(val fileId: Int, val total: Int, val references: List<FileReference>)

@Serializable
data class DedupGroupRequest(val canonicalId: Int, val redundantIds: List<Int>)

@Serializable
data class DedupRequest(val groups: List<DedupGroupRequest>)

@Serializable
data class DedupReport(
    val applied: Boolean,
    val groupsProcessed: Int,
    val refsRepointed: Int,
    val filesDeleted: Int,
    val backupSchema: String? = null,
    val binariesRequested: Boolean = false,
    val binariesDeleted: Int = 0,
    val binariesFailed: Int = 0,
)

/**
 * Per-instance media library deduplication.
 *
 * scanDuplicates groups a single instance's files into CERTAIN duplicates (identical bytes, by
 * sha256) and SUSPECT ones (same name+ext+folder but different bytes), annotating each copy with how
 * many content entries reference it (via files_related_mph). applyDedup converges all references of
 * the redundant copies onto a chosen canonical file, then deletes the redundant copies — after
 * backing up the affected rows.
 */
object MediaDeduplicationService {
    private val logger = LoggerFactory.getLogger(MediaDeduplicationService::class.java)

    private class Ref { val byType = mutableMapOf<String, Int>() }

    /** Download a single file's raw bytes (server-side, via the instance client) for previewing in the UI. */
    suspend fun downloadFileBytes(instance: StrapiInstance, fileId: Int): Pair<ByteArray, String>? {
        data class Row(val name: String, val ext: String, val mime: String, val url: String, val provider: String, val documentId: String)
        val row = dbQuery(instance.database) {
            var r: Row? = null
            exec(
                "SELECT name, ext, mime, url, provider, document_id FROM files WHERE id = $fileId",
                explicitStatementType = StatementType.SELECT,
            ) { rs ->
                if (rs.next()) r = Row(
                    name = rs.getString("name") ?: "file",
                    ext = rs.getString("ext") ?: "",
                    mime = rs.getString("mime") ?: "application/octet-stream",
                    url = rs.getString("url") ?: "",
                    provider = rs.getString("provider") ?: "local",
                    documentId = rs.getString("document_id") ?: "",
                )
            }
            r
        } ?: return null

        val img = it.sebi.models.StrapiImage(
            metadata = it.sebi.models.StrapiImageMetadata(
                id = fileId, documentId = row.documentId, name = row.name, hash = "", ext = row.ext,
                mime = row.mime, size = 0.0, url = row.url, previewUrl = null, provider = row.provider,
                folderPath = "/", folder = "/", locale = null, updatedAt = java.time.OffsetDateTime.now(),
            ),
            rawData = kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        val client = it.sebi.client.StrapiClient(instance)
        val tmp = client.downloadFile(img)
        return try {
            tmp.readBytes() to row.mime
        } finally {
            tmp.delete()
        }
    }

    private suspend fun referenceCounts(instance: StrapiInstance): Map<Int, Ref> = dbQuery(instance.database) {
        val m = mutableMapOf<Int, Ref>()
        exec(
            "SELECT file_id, related_type, count(*) AS c FROM files_related_mph WHERE file_id IS NOT NULL GROUP BY file_id, related_type",
            explicitStatementType = StatementType.SELECT,
        ) { rs ->
            while (rs.next()) {
                val fid = rs.getInt("file_id")
                val rt = rs.getString("related_type") ?: "?"
                val c = rs.getInt("c")
                m.getOrPut(fid) { Ref() }.byType.merge(rt, c, Int::plus)
            }
        }
        m
    } ?: emptyMap()

    /** Resolve every place a file is used (from files_related_mph), with a best-effort human label. */
    suspend fun getFileReferences(instance: StrapiInstance, fileId: Int): FileReferencesResponse {
        data class Morph(val relatedId: Int, val relatedType: String, val field: String?)
        val morphs = dbQuery(instance.database) {
            val l = mutableListOf<Morph>()
            exec(
                "SELECT related_id, related_type, field FROM files_related_mph WHERE file_id = $fileId ORDER BY related_type, field, \"order\"",
                explicitStatementType = StatementType.SELECT,
            ) { rs ->
                while (rs.next()) l.add(Morph(rs.getInt("related_id"), rs.getString("related_type") ?: "?", rs.getString("field")))
            }
            l
        } ?: emptyList()
        if (morphs.isEmpty()) return FileReferencesResponse(fileId, 0, emptyList())

        val schema = it.sebi.service.buildDbSchemaForInstance(instance)
        val byUid = schema?.tables?.filter { it.metadata != null }?.associateBy { it.metadata!!.apiUid } ?: emptyMap()
        val labelCols = listOf("title", "name", "label", "slug", "code", "display_name", "heading")

        val refs = mutableListOf<FileReference>()
        for ((rtype, list) in morphs.groupBy { it.relatedType }) {
            val isComponent = !rtype.contains("::")
            val table = byUid[rtype]
            val labelMap = mutableMapOf<Int, Pair<String?, String?>>()
            if (table != null) {
                val cols = table.columns.map { it.name }.toSet()
                val lbl = labelCols.firstOrNull { it in cols }
                val hasDoc = "document_id" in cols
                val selCols = listOfNotNull("id", lbl, if (hasDoc) "document_id" else null).joinToString(",")
                val ids = list.map { it.relatedId }.distinct().joinToString(",")
                try {
                    dbQuery(instance.database) {
                        exec("SELECT $selCols FROM \"${table.name}\" WHERE id IN ($ids)", explicitStatementType = StatementType.SELECT) { rs ->
                            while (rs.next()) {
                                val id = rs.getInt("id")
                                labelMap[id] = (lbl?.let { rs.getString(it) }) to (if (hasDoc) rs.getString("document_id") else null)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Could not resolve labels for $rtype on '${instance.name}': ${e.message}")
                }
            }
            for (m in list) {
                val (label, doc) = labelMap[m.relatedId] ?: (null to null)
                refs.add(FileReference(rtype, m.field, m.relatedId, label, doc, isComponent))
            }
        }
        return FileReferencesResponse(fileId, refs.size, refs)
    }

    suspend fun scanDuplicates(instance: StrapiInstance): MediaDuplicatesReport {
        require(!instance.isVirtual) { "Media deduplication requires a real (non-virtual) instance" }
        val files = computeFingerprints(instance, fetchFilesFromDb(instance))
        val refs = referenceCounts(instance)

        fun info(img: it.sebi.models.StrapiImage): DupFileInfo {
            val md = img.metadata
            val byType = refs[md.id]?.byType ?: emptyMap()
            return DupFileInfo(
                id = md.id,
                documentId = md.documentId,
                name = md.name,
                ext = md.ext,
                sizeBytes = md.calculatedSizeBytes ?: md.size.toLong(),
                folder = md.folder ?: "/",
                mime = md.mime,
                url = md.url,
                sha = md.calculatedSha,
                refs = byType.values.sum(),
                refsByType = byType,
            )
        }

        val infos = files.map { info(it) }

        fun suggestCanonical(group: List<DupFileInfo>): Int =
            group.maxWith(compareBy({ it.refs }, { it.id })).id

        // CERTAIN: identical bytes (sha256), more than one copy.
        val certain = infos.filter { it.sha != null }
            .groupBy { it.sha!! }
            .filter { it.value.size > 1 }
            .map { (sha, g) ->
                DupGroup(
                    key = "sha:$sha",
                    kind = "CERTAIN",
                    sha = sha,
                    name = g.first().name,
                    totalRefs = g.sumOf { it.refs },
                    files = g.sortedByDescending { it.refs },
                    suggestedCanonicalId = suggestCanonical(g),
                )
            }
            .sortedByDescending { it.files.size }

        val coveredIds = certain.flatMap { it.files }.map { it.id }.toSet()

        // SUSPECT: same name+ext+folder, different bytes (not already a certain group).
        val suspect = infos.filter { it.id !in coveredIds }
            .groupBy { "${it.name.trim().lowercase()}|${it.ext.lowercase()}|${it.folder.lowercase()}" }
            .filter { it.value.size > 1 }
            .map { (k, g) ->
                DupGroup(
                    key = "name:$k",
                    kind = "SUSPECT",
                    sha = null,
                    name = g.first().name,
                    totalRefs = g.sumOf { it.refs },
                    files = g.sortedByDescending { it.refs },
                    suggestedCanonicalId = suggestCanonical(g),
                )
            }
            .sortedByDescending { it.files.size }

        val removable = certain.sumOf { it.files.size - 1 } + suspect.sumOf { it.files.size - 1 }
        logger.info("Media dedup scan '${instance.name}': ${files.size} files, certain=${certain.size}, suspect=${suspect.size}, removable=$removable")
        return MediaDuplicatesReport(
            instanceId = instance.id,
            totalFiles = files.size,
            certainGroups = certain,
            suspectGroups = suspect,
            removableCopies = removable,
        )
    }

    /**
     * Converge every reference of the redundant copies onto the chosen canonical file, then delete
     * the redundant copies. Affected rows (files + files_related_mph) are backed up into a dedicated
     * schema first. With apply=false nothing is written (dry-run): the report shows what would change.
     */
    suspend fun applyDedup(instance: StrapiInstance, req: DedupRequest, apply: Boolean, deleteBinaries: Boolean = false): DedupReport {
        require(!instance.isVirtual) { "Media deduplication requires a real (non-virtual) instance" }
        val groups = req.groups.filter { it.redundantIds.isNotEmpty() }
        groups.forEach { require(it.canonicalId !in it.redundantIds) { "Canonical ${it.canonicalId} cannot be in its own redundant list" } }
        if (groups.isEmpty()) return DedupReport(applied = apply, groupsProcessed = 0, refsRepointed = 0, filesDeleted = 0, binariesRequested = deleteBinaries)

        val allRedundant = groups.flatMap { it.redundantIds }.distinct()
        val allInvolved = (groups.map { it.canonicalId } + allRedundant).distinct().joinToString(",")
        val redundantCsv = allRedundant.joinToString(",")

        // Phase 1 (transactional): backup + repoint references onto canonical + drop collisions.
        // When deleteBinaries is OFF we also remove the redundant DB rows here. When it is ON we leave
        // the rows so the upload API can delete binary+row together (Phase 2), with a DB fallback.
        val (refsToRepoint, backup) = dbQuery(instance.database) {
            var refs = 0
            exec("SELECT count(*) AS c FROM files_related_mph WHERE file_id IN ($redundantCsv)",
                explicitStatementType = StatementType.SELECT) { rs -> if (rs.next()) refs = rs.getInt("c") }

            if (!apply) return@dbQuery refs to null

            val bk = "media_dedup_backup_${System.currentTimeMillis()}"
            exec("CREATE SCHEMA \"$bk\"")
            exec("CREATE TABLE \"$bk\".files AS SELECT * FROM public.files WHERE id IN ($allInvolved)")
            exec("CREATE TABLE \"$bk\".files_related_mph AS SELECT * FROM public.files_related_mph WHERE file_id IN ($allInvolved)")

            for (g in groups) {
                val red = g.redundantIds.joinToString(",")
                exec("UPDATE files_related_mph SET file_id = ${g.canonicalId} WHERE file_id IN ($red)")
                exec(
                    """
                    DELETE FROM files_related_mph a USING files_related_mph b
                    WHERE a.file_id = ${g.canonicalId} AND b.file_id = ${g.canonicalId}
                      AND a.related_id = b.related_id
                      AND COALESCE(a.related_type,'') = COALESCE(b.related_type,'')
                      AND COALESCE(a.field,'') = COALESCE(b.field,'')
                      AND a.id > b.id
                    """.trimIndent()
                )
                if (!deleteBinaries) {
                    exec("DELETE FROM files_folder_lnk WHERE file_id IN ($red)")
                    exec("DELETE FROM files WHERE id IN ($red)")
                }
            }
            refs to bk
        } ?: (0 to null)

        if (!apply) {
            return DedupReport(
                applied = false, groupsProcessed = groups.size, refsRepointed = refsToRepoint,
                filesDeleted = allRedundant.size, binariesRequested = deleteBinaries,
            )
        }

        // Phase 2 (only when deleteBinaries): delete each redundant binary+row via the upload API
        // (uses the instance's configured storage provider). Rows that the API couldn't delete are
        // removed from the DB anyway so the Strapi state stays consistent (binary may be orphaned).
        var binariesDeleted = 0
        val apiFailed = mutableListOf<Int>()
        if (deleteBinaries) {
            val client = it.sebi.client.StrapiClient(instance)
            for (rid in allRedundant) {
                if (client.deleteUploadFile(rid)) binariesDeleted++ else apiFailed += rid
            }
            if (apiFailed.isNotEmpty()) {
                val csv = apiFailed.joinToString(",")
                dbQuery(instance.database) {
                    exec("DELETE FROM files_folder_lnk WHERE file_id IN ($csv)")
                    exec("DELETE FROM files WHERE id IN ($csv)")
                }
            }
        }

        logger.info("Media dedup applied on '${instance.name}': ${groups.size} groups, $refsToRepoint refs repointed, ${allRedundant.size} files removed, binaries=${if (deleteBinaries) "$binariesDeleted ok/${apiFailed.size} failed" else "kept"}, backup=$backup")
        return DedupReport(
            applied = true, groupsProcessed = groups.size, refsRepointed = refsToRepoint,
            filesDeleted = allRedundant.size, backupSchema = backup,
            binariesRequested = deleteBinaries, binariesDeleted = binariesDeleted, binariesFailed = apiFailed.size,
        )
    }
}
