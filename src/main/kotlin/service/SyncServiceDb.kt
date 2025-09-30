package it.sebi.service

import it.sebi.client.StrapiClient
import it.sebi.database.dbQuery
import it.sebi.models.*
import it.sebi.utils.FileFingerprintUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * DB-based schema compatibility check for Strapi instances.
 * Reads latest JSON from strapi_database_schema.schema in each instance DB and compares structures.
 */


suspend fun fetchFilesFromDb(instance: StrapiInstance): List<StrapiImage> = withContext(Dispatchers.IO) {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    try {

        val sql = """
            WITH RECURSIVE folder_hierarchy AS (
                -- Caso base: cartelle radice (path contiene solo il proprio ID)
                SELECT
                    id,
                    path,
                    name,
                    ('/' || name)::text as full_path
                FROM upload_folders
                WHERE path = '/' || id::text

                UNION ALL

                -- Caso ricorsivo: cartelle che hanno un parent
                SELECT
                    uf.id,
                    uf.path,
                    uf.name,
                    (fh.full_path || '/' || uf.name)::text as full_path
                FROM upload_folders uf
                         JOIN folder_hierarchy fh ON uf.path LIKE fh.path || '/%'
                WHERE uf.path != '/' || uf.id::text
            )
            SELECT
                f.id,
                f.document_id,
                f.name,
                f.alternative_text,
                f.caption,
                f.hash,
                f.ext,
                f.mime,
                f.size,
                f.url,
                f.preview_url,
                f.provider,
                f.folder_path,
                f.locale,
                f.updated_at,
                COALESCE(fh.full_path, '/') as folder_name
            FROM files f
                     LEFT JOIN folder_hierarchy fh ON fh.path = f.folder_path
            WHERE f.document_id IS NOT NULL
            ORDER BY f.document_id, COALESCE(f.locale, ''), f.updated_at DESC, f.id DESC;
        """.trimIndent()
        // 1) Fetch metadata from DB (latest per document_id/locale)
        val imagesFromDb = dbQuery(instance.database) {
            this.exec(
                sql, explicitStatementType = StatementType.SELECT
            ) { rs ->
                val list = mutableListOf<StrapiImage>()
                while (rs.next()) {
                    val sizeBd: BigDecimal? = rs.getBigDecimal("size")
                    val ts = rs.getTimestamp("updated_at")
                    val updatedAt = ts?.toInstant()?.atOffset(ZoneOffset.UTC)
                    val folderPath = rs.getString("folder_path") ?: "/"
                    val meta = StrapiImageMetadata(
                        id = rs.getInt("id"),
                        documentId = rs.getString("document_id"),
                        alternativeText = rs.getString("alternative_text"),
                        caption = rs.getString("caption"),
                        name = rs.getString("name"),
                        hash = rs.getString("hash"),
                        ext = rs.getString("ext"),
                        mime = rs.getString("mime"),
                        size = sizeBd?.toDouble() ?: 0.0,
                        url = rs.getString("url"),
                        previewUrl = rs.getString("preview_url"),
                        provider = rs.getString("provider") ?: "local",
                        folderPath = folderPath,
                        folder = rs.getString("folder_name"),
                        locale = rs.getString("locale"),
                        updatedAt = updatedAt ?: OffsetDateTime.now()
                    )
                    val raw = buildJsonObject {
                        put("id", meta.id)
                        put("documentId", meta.documentId)
                        put("name", meta.name)
                        put("alternativeText", meta.alternativeText)
                        put("caption", meta.caption)
                        put("hash", meta.hash)
                        put("ext", meta.ext)
                        put("mime", meta.mime)
                        put("size", meta.size)
                        put("url", meta.url)
                        put("previewUrl", meta.previewUrl)
                        put("provider", meta.provider)
                        put("folderPath", meta.folderPath)
                        put("locale", meta.locale)
                        put("updatedAt", meta.updatedAt.toString())
                    }
                    list.add(StrapiImage(metadata = meta, rawData = raw))
                }
                list.toList()
            } ?: emptyList()
        }

        imagesFromDb.map { it.copy(metadata = it.metadata.copy(calculatedSizeBytes = it.metadata.size.toLong())) }
//        val semaphore = Semaphore(10)

        // 2) For each file, download it and compute hash and size, attach to metadata
//        val client = it.sebi.client.StrapiClient(instance)
//        imagesFromDb.map { img ->
//            async {
//                semaphore.withPermit {
//                    try {
//                        val f = client.downloadFile(img)
//                        val md5 = f.inputStream().use { ins -> calculateMD5Hash(ins) }
//                        val sizeBytes = f.length()
//                        f.delete()
//                        val newMeta = img.metadata.copy(
//                            calculatedHash = md5,
//                            calculatedSizeBytes = sizeBytes
//                        )
//                        img.copy(metadata = newMeta)
//                    } catch (e: Exception) {
//                        logger.warn("Failed to download/compute file '${img.metadata.name}' (id=${img.metadata.id}) from instance '${instance.name}': ${e.message}")
//                        img
//                    }
//                }
//            }
//        }.awaitAll()
    } catch (e: Exception) {
        logger.error("Error fetching files from DB for instance '${instance.name}': ${e.message}", e)
        emptyList()
    }
}

suspend fun fetchFilesRelatedMph(instance: StrapiInstance): List<FilesRelatedMph> = withContext(Dispatchers.IO) {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    try {
        dbQuery(instance.database) {
            exec(
                """
                    SELECT id,
                           file_id,
                           related_id,
                           related_type,
                           field,
                           "order" AS ord
                    FROM files_related_mph
                    ORDER BY id
                    """.trimIndent()
            ) { rs ->
                val list = mutableListOf<FilesRelatedMph>()
                while (rs.next()) {
                    val id = (rs.getObject("id") as? Number)?.toInt() ?: continue
                    val fileId = (rs.getObject("file_id") as? Number)?.toInt()
                    val relatedId = (rs.getObject("related_id") as? Number)?.toInt()
                    val relatedType = rs.getString("related_type")
                    val field = rs.getString("field")
                    val order = (rs.getObject("ord") as? Number)?.toDouble()
                    list.add(FilesRelatedMph(id, fileId, relatedId, relatedType, field, order))
                }
                list


            } ?: emptyList()
        }
    } catch (e: Exception) {
        logger.error("Error fetching files_related_mph from DB for instance '${instance.name}': ${e.message}", e)
        emptyList()
    }
}


suspend fun fetchCMPS(instance: StrapiInstance, dbSchema: DbSchema): CmpsMap = withContext(Dispatchers.IO) {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    val cmpsByEntityField: CmpsMap = mutableMapOf()

    try {
        dbQuery(instance.database) {
            val relatedCmpsTables = dbSchema.tables.filter { it.name.endsWith("_cmps") }

            val sql = relatedCmpsTables.map { cmps ->
                "SELECT '${cmps.name}' as table_n, entity_id, cmp_id, component_type, field, \"order\", id FROM \"${cmps.name}\""
            }.joinToString("\nUNION\n") { it }

            exec(sql) { rs ->
                while (rs.next()) {
                    val tableName = (rs.getObject("table_n") as? String) ?: continue
                    val eid = (rs.getObject("entity_id") as? Number)?.toInt() ?: continue
                    val field = rs.getString("field") ?: continue
                    val cmpId = (rs.getObject("cmp_id") as? Number)?.toInt() ?: continue
                    val compType = rs.getString("component_type")
                    val ord = (rs.getObject("order") as? Number)?.toDouble()
                    val id = (rs.getObject("id") as? Number)?.toInt() ?: 0
                    val tab = cmpsByEntityField.getOrPut(tableName) { mutableMapOf() }
                    val m = tab.getOrPut(eid) { mutableMapOf() }
                    val l = m.getOrPut(field) { mutableListOf() }
                    l.add(CmpRow(eid, field, cmpId, compType, ord, id))
                }
            }


        }

    } catch (e: Exception) {
        logger.error("Error fetching files_related_mph from DB for instance '${instance.name}': ${e.message}", e)
    }
    cmpsByEntityField
}

suspend fun fetchTables(instance: StrapiInstance, dbSchema: DbSchema): TableMap = withContext(Dispatchers.IO) {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    val tableMap: TableMap = mutableMapOf()

    val tablesQuery = dbSchema.tables.filter { it.metadata?.apiUid?.startsWith("api::") ?: false }.map { table ->
        """SELECT '${table.name}' as table_n, 
                    t.document_id,
                    to_jsonb(t) AS obj
                    FROM "${table.name}" t
                    WHERE t.document_id IS NOT NULL AND t.published_at IS NOT NULL
                    """

    }.joinToString("\nUNION\n") { it }

    val sqlQuery = """with cta as (
        |$tablesQuery)
        |select * from cta order by cta.table_n, document_id
    """.trimMargin()
    try {
        dbQuery(instance.database) {


            exec(sqlQuery, explicitStatementType = StatementType.SELECT) { rs ->
                while (rs.next()) {
                    val tableName = (rs.getObject("table_n") as? String) ?: continue
                    val documentId = rs.getString("document_id") ?: continue
                    val objStr = rs.getString("obj") ?: continue
                    val obj = parseJsonObject(objStr)
                    tableMap.getOrPut(tableName) { mutableMapOf() }.put(documentId, obj)

                }
            }


        }

    } catch (e: Exception) {
        logger.error("Error fetching files_related_mph from DB for instance '${instance.name}': ${e.message}", e)
    }
    tableMap
}

suspend fun fetchComponents(
    instance: StrapiInstance,
    dbSchema: DbSchema
): MutableMap<String, MutableMap<Int, JsonObject>> =
    withContext(Dispatchers.IO) {
        val logger = LoggerFactory.getLogger("SyncServiceDb")
        val components: MutableMap<String, MutableMap<Int, JsonObject>> = mutableMapOf()

        try {
            dbQuery(instance.database) {
                val relatedCmpsTables = dbSchema.tables.filter { it.name.startsWith("components_") }

                val sql = relatedCmpsTables.map { cmps ->
                    "SELECT '${cmps.name}' as table_n, id, to_jsonb(t) AS obj FROM \"${cmps.name}\" t"
                }.joinToString("\nUNION\n") { it }


                exec(sql) { rs ->
                    while (rs.next()) {
                        val tableName = (rs.getObject("table_n") as? String) ?: continue
                        val eid = (rs.getObject("id") as? Number)?.toInt() ?: continue
                        val obj = rs.getString("obj")?.let { parseJsonObject(it) } ?: continue

                        components.getOrPut(tableName) { mutableMapOf() }.put(eid, obj)
                    }
                }
                components.toMap()


            }

        } catch (e: Exception) {
            logger.error("Error fetching files_related_mph from DB for instance '${instance.name}': ${e.message}", e)
        }
        components
    }


suspend fun getfileCompareFromDb(
    mergeRequest: MergeRequestWithInstancesDTO,
    currentFileMapping: Map<String, MergeRequestDocumentMapping>
): List<ContentTypeFileComparisonResult> = coroutineScope {

    // 1) Fetch files from both DBs (latest per document_id/locale) concurrently
    val sourceDef = async { fetchFilesFromDb(mergeRequest.sourceInstance) }
    val targetDef = async { fetchFilesFromDb(mergeRequest.targetInstance) }
    val sourceFiles = sourceDef.await()
    val targetFiles = targetDef.await()

    // 2) Compute robust fingerprints (image dHash / PDF text hash) for both sides
    val sourceWithFpDef = async { computeFingerprints(mergeRequest.sourceInstance, sourceFiles) }
    val targetWithFpDef = async { computeFingerprints(mergeRequest.targetInstance, targetFiles) }
    val sourceWithFp = sourceWithFpDef.await()
    val targetWithFp = targetWithFpDef.await()

    // 3) Load existing document mappings for files to pair source->target by mapping first

    // Helpers
    fun keyOf(documentId: String, locale: String?): String {
        val loc = locale ?: ""
        return "$documentId|$loc"
    }

    fun keyOfTarget(m: StrapiImageMetadata): String = keyOf(m.documentId, m.locale)

    // 4) Index targets: by key and by fingerprint for fallback
    val targetByKey: MutableMap<String, StrapiImage> =
        targetWithFp.associateBy { keyOfTarget(it.metadata) }.toMutableMap()
    val targetByFp: MutableMap<String, MutableList<StrapiImage>> = mutableMapOf()
    for (t in targetWithFp) {
        val fp = t.metadata.calculatedHash
        if (!fp.isNullOrBlank()) targetByFp.getOrPut(fp) { mutableListOf() }.add(t)
    }

    val usedTargetIds = mutableSetOf<Int>()

    // 5) Iterate over source files, pair using mapping first, then fingerprint
    val results = mutableListOf<ContentTypeFileComparisonResult>()

    fun compareStateOf(s: StrapiImage?, t: StrapiImage?): ContentTypeComparisonResultKind {
        return when {
            s != null && t == null -> ContentTypeComparisonResultKind.ONLY_IN_SOURCE
            s == null && t != null -> ContentTypeComparisonResultKind.ONLY_IN_TARGET
            s != null && t != null -> {
                val sm = s.metadata
                val tm = t.metadata

                val sourceFp = sm.calculatedHash
                val targetFp = tm.calculatedHash
                if (!sourceFp.isNullOrBlank() && sourceFp == targetFp) {
                    ContentTypeComparisonResultKind.IDENTICAL
                } else {
                    val sourceSize = sm.calculatedSizeBytes ?: sm.size.toLong()
                    val targetSize = tm.calculatedSizeBytes ?: tm.size.toLong()
                    val sourceSizeKB = (sourceSize + 512) / 1024
                    val targetSizeKB = (targetSize + 512) / 1024

                    val isDifferent = when {
                        (sourceSizeKB != targetSizeKB) -> true
                        (sm.folderPath != tm.folderPath) -> true
                        (sm.name != tm.name) -> true
                        (sm.alternativeText != tm.alternativeText) -> true
                        (sm.caption != tm.caption) -> true
                        else -> false
                    }
                    if (isDifferent) ContentTypeComparisonResultKind.DIFFERENT else ContentTypeComparisonResultKind.IDENTICAL
                }
            }

            else -> ContentTypeComparisonResultKind.IDENTICAL
        }
    }

    for (s in sourceWithFp) {
        val sm = s.metadata
        val mappedDoc = currentFileMapping[sm.documentId]?.targetDocumentId ?: sm.documentId
        val k = keyOf(mappedDoc, sm.locale)
        var t: StrapiImage? = targetByKey[k]?.takeIf { usedTargetIds.add(it.metadata.id) }
        if (t == null) {
            // Try fingerprint association
            val fp = sm.calculatedHash
            if (!fp.isNullOrBlank()) {
                val candidates = targetByFp[fp]
                if (!candidates.isNullOrEmpty()) {
                    // Prefer same locale
                    val preferred =
                        candidates.firstOrNull { it.metadata.locale == sm.locale && !usedTargetIds.contains(it.metadata.id) }
                    val anyOther = preferred ?: candidates.firstOrNull { !usedTargetIds.contains(it.metadata.id) }
                    if (anyOther != null) {
                        t = anyOther
                        usedTargetIds.add(anyOther.metadata.id)
                    }
                }
            }
        }
        val state = compareStateOf(s, t)
        results.add(ContentTypeFileComparisonResult(sourceImage = s, targetImage = t, compareState = state))
    }

    // 6) Add remaining targets not paired
    for (t in targetWithFp) {
        if (!usedTargetIds.contains(t.metadata.id)) {
            results.add(
                ContentTypeFileComparisonResult(
                    sourceImage = null,
                    targetImage = t,
                    compareState = ContentTypeComparisonResultKind.ONLY_IN_TARGET
                )
            )
            usedTargetIds.add(t.metadata.id)
        }
    }

    results
}


val TECHNICAL_FIELDS = setOf(
    "id", "created_by_id", "updated_by_id", "created_at", "updated_at", "published_at"
)
private val IGNORE_COMPARE_FIELDS = TECHNICAL_FIELDS + setOf("locale")

private fun parseJsonObject(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

fun cleanObject(o: JsonObject): JsonObject {
    val sortedMap = o.entries
        .filter { (key, _) -> !IGNORE_COMPARE_FIELDS.contains(key) }
        .sortedBy { it.key }
        .associate { (key, value) -> key to cleanValue(value) }

    // Usa LinkedHashMap per preservare l'ordine
    return JsonObject(LinkedHashMap(sortedMap))

}

private fun cleanArray(arr: JsonArray): JsonArray = JsonArray(
    arr.map { cleanValue(it) }
        .let { items ->
            if (items.any { it is JsonObject && it.containsKey("__order") }) {
                items.sortedBy { (it as? JsonObject)?.get("__order")?.jsonPrimitive?.doubleOrNull ?: Double.MAX_VALUE }
            } else {
                items
            }
        }
)

private fun cleanValue(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> cleanObject(element)
    is JsonArray -> cleanArray(element)
    else -> element
}

suspend fun fetchPublishedRowsAsJson(
    instance: StrapiInstance,
    table: String,
    dbSchema: DbSchema,
    cmpsMap: CmpsMap,
    relationshipsCollector: MutableSet<ContentRelationship>? = null,
    currentSourceUid: String? = null,
    fileRelations: List<FilesRelatedMph>? = null,
    componentTableCache: MutableMap<String, MutableMap<Int, JsonObject>>,
    fileCache: Map<Int, String>,
    tableMap: TableMap
): List<Pair<JsonObject, List<StrapiLinkRef>>> = withContext(Dispatchers.IO) {
    try {
        val baseRows = tableMap[table]?.values?.toList() ?: dbQuery(instance.database) {
            exec(
                """
                    SELECT 
                           to_jsonb(t) AS obj
                    FROM "$table" t
                    WHERE t.document_id IS NOT NULL AND t.published_at IS NOT NULL
                    ORDER BY document_id, published_at DESC, id DESC
                    """.trimIndent()
            ) { rs ->
                val list = mutableListOf<JsonObject>()
                while (rs.next()) {
                    val objStr = rs.getString("obj")
                    if (objStr != null) list.add(parseJsonObject(objStr))
                }
                list

            } ?: emptyList()
        }
        if (baseRows.isEmpty()) return@withContext emptyList()
        val srcUid = currentSourceUid ?: dbSchema.tables.firstOrNull { it.name == table }?.metadata?.apiUid
        val linksCollector = mutableMapOf<Int, MutableList<StrapiLinkRef>>()
        val idToRoot: Map<Int, Int> =
            baseRows.mapNotNull { o -> o["id"]?.jsonPrimitive?.intOrNull?.let { it to it } }.toMap()
        val enriched = enrichTableRowsWithCmps(
            instance,
            table,
            baseRows,
            dbSchema,
            cmpsMap = cmpsMap,
            depth = 0,
            maxDepth = 10,
            currentSourceUid = srcUid,
            relationshipsCollector = relationshipsCollector,
            fileRelations = fileRelations,
            linksCollector = linksCollector,
            entityToRoot = idToRoot,
            pathPrefix = "",
            componentTableCache = componentTableCache
        )
        // Resolve documentIds for link targets across all rows and attach __links into raw JSON
        val allLinks = linksCollector.values.flatten()
        val idsByTable: Map<String, Set<Int>> = allLinks
            .mapNotNull { if (it.targetId != null) it.targetTable to it.targetId else null }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

        val docIdMaps = mutableMapOf<String, Map<Int, String>>()
        for ((tbl, ids) in idsByTable) {
            val hasDocIdColumn =
                dbSchema.tables.firstOrNull { it.name == tbl }?.columns?.any { it.name == "document_id" } == true
            val allowed = tbl == "files" || hasDocIdColumn
            if (!allowed) continue
            docIdMaps[tbl] = fetchDocumentIdsForTable(instance, tbl, ids, fileCache)
        }

        enriched.map { row ->
            val id = row["id"]?.jsonPrimitive?.intOrNull
            val linksForRow: List<StrapiLinkRef> =
                if (id != null) linksCollector[id]?.toList() ?: emptyList() else emptyList()

            // Build __links object grouped by field path with ordered documentIds
            val linksJson: JsonObject? = if (linksForRow.isNotEmpty()) {
                val grouped = linksForRow.groupBy { it.field }
                val fieldEntries = grouped.mapNotNull { (field, lst) ->
                    val sorted =
                        lst.sortedWith(compareBy<StrapiLinkRef> { it.order ?: Double.MAX_VALUE }.thenBy { it.id })
                    val docIds = sorted.mapNotNull { l ->
                        val tid = l.targetId ?: return@mapNotNull null
                        val map = docIdMaps[l.targetTable] ?: return@mapNotNull null
                        map[tid]
                    }
                    if (docIds.isEmpty()) null else field to JsonArray(docIds.map { JsonPrimitive(it) })
                }
                if (fieldEntries.isEmpty()) null else JsonObject(LinkedHashMap(fieldEntries.toMap()))
            } else null

            val rowWithLinks = if (linksJson != null) JsonObject(row + mapOf("__links" to linksJson)) else row
            val links = linksForRow
            rowWithLinks to links
        }
    } catch (e: Exception) {
        LoggerFactory.getLogger("SyncServiceDb").error("Error fetching rows from $table: ${e.message}", e)
        emptyList()
    }
}

fun resolveComponentTableName(componentType: String?, dbSchema: DbSchema): String? {
    if (componentType.isNullOrBlank()) return null
    val norm = componentType.replace("-", "_").replace(".", "_")
    val candidate = "components_${norm}s"
    val all = dbSchema.tables.map { it.name }
    if (all.contains(candidate)) return candidate
    // fallback: best match containing norm
    val best = all.filter { it.startsWith("components_") && it.contains(norm) }
        .maxByOrNull { it.length }
    return best
}

private suspend fun fetchRowsByIds(
    instance: StrapiInstance,
    table: String,
    ids: Collection<Int>,
    componentTableCache: MutableMap<String, MutableMap<Int, JsonObject>>
): List<JsonObject> =
    withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        if (componentTableCache[table] == null) {
            val tableRecords = dbQuery(instance.database) {
                exec("SELECT id,to_jsonb(c) AS obj FROM \"$table\" c") { rs ->
                    val out = mutableMapOf<Int, JsonObject>()
                    while (rs.next()) {
                        rs.getString("obj")?.let { out.put(rs.getInt("id"), parseJsonObject(it)) }
                    }
                    out

                } ?: mutableMapOf()
            }
            componentTableCache.put(table, tableRecords)
        }
        componentTableCache[table]?.filterKeys { ids.contains(it) }?.values?.toList() ?: emptyList()
    }

data class CmpRow(
    val entityId: Int,
    val field: String,
    val cmpId: Int,
    val componentType: String?,
    val order: Double?,
    val id: Int
)

private suspend fun enrichTableRowsWithCmps(
    instance: StrapiInstance,
    tableName: String,
    baseRows: List<JsonObject>,
    dbSchema: DbSchema,
    depth: Int = 0,
    maxDepth: Int = 10,
    cmpsMap: CmpsMap,
    currentSourceUid: String? = null,
    relationshipsCollector: MutableSet<ContentRelationship>? = null,
    fileRelations: List<FilesRelatedMph>? = null,
    linksCollector: MutableMap<Int, MutableList<StrapiLinkRef>>? = null,
    entityToRoot: Map<Int, Int>? = null,
    pathPrefix: String = "",
    componentTableCache: MutableMap<String, MutableMap<Int, JsonObject>>
): List<JsonObject> {
    if (depth >= maxDepth) return baseRows
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    val entityIds: List<Int> = baseRows.mapNotNull { it["id"]?.jsonPrimitive?.intOrNull }
    if (entityIds.isEmpty()) return baseRows

    // 1) Find *_cmps tables referencing this table (components to embed)
    val relatedCmpsTables = dbSchema.tables.filter { t ->
        t.name.endsWith("_cmps") && t.foreignKeys.any { fk -> fk.referencedTable == tableName }
    }

    // Container for embedded components grouped by (entity, field)


    val cmpsByEntityField: MutableMap<Int, MutableMap<String, MutableList<CmpRow>>> = mutableMapOf()

    // 2) Load component rows
    if (relatedCmpsTables.isNotEmpty()) {
        relatedCmpsTables.forEach { t ->
            cmpsMap[t.name]?.let { cmps ->
                entityIds.forEach { eid ->
                    cmps[eid]?.let { r ->
                        val m: MutableMap<String, MutableList<CmpRow>> =
                            cmpsByEntityField.getOrPut(eid) { mutableMapOf() }
                        m.putAll(r)

                    }
                }
            }
        }
    }

    // 3) Resolve component tables and recursively enrich component payloads
    val embeddedByEntityField: MutableMap<Int, MutableMap<String, List<JsonObject>>> = mutableMapOf()

    if (cmpsByEntityField.isNotEmpty()) {
        for ((eid, byField) in cmpsByEntityField) {
            val fieldMap = mutableMapOf<String, List<JsonObject>>()
            for ((field, rows) in byField) {
                val resultList = mutableListOf<JsonObject>()
                for (r in rows.sortedBy { it.order }) {
                    // Record relationship contentType/component or component/component via *_cmps
                    val compTable = resolveComponentTableName(r.componentType, dbSchema)
                    if (compTable == null) {
                        val placeholder = buildJsonObject {
                            put("cmp_id", r.cmpId)
                            if (r.componentType != null) put(
                                "component_type",
                                r.componentType
                            ) else put("component_type", JsonNull)
                            if (r.order != null) put("order", r.order) else put("order", JsonNull)
                        }
                        resultList.add(placeholder)
                    } else {
                        val compRows: List<JsonObject> =
                            fetchRowsByIds(instance, compTable, listOf(r.cmpId), componentTableCache).map { o ->
                                if (r.order != null)
                                    JsonObject(o.plus("__order" to JsonPrimitive(r.order)))
                                else
                                    o
                            }
                        val compIdToRoot: Map<Int, Int> = compRows.mapNotNull { o ->
                            o["id"]?.jsonPrimitive?.intOrNull?.let { cid -> cid to (entityToRoot?.get(eid) ?: eid) }
                        }.toMap()
                        val newPrefix = if (pathPrefix.isEmpty()) field else "$pathPrefix.$field"
                        val deeply = enrichTableRowsWithCmps(
                            instance,
                            compTable,
                            compRows,
                            dbSchema,
                            depth + 1,
                            maxDepth,
                            currentSourceUid = r.componentType,
                            relationshipsCollector = relationshipsCollector,
                            fileRelations = fileRelations,
                            cmpsMap = cmpsMap,
                            linksCollector = linksCollector,
                            entityToRoot = compIdToRoot,
                            pathPrefix = newPrefix,
                            componentTableCache = componentTableCache
                        )
                        if (deeply.isNotEmpty()) resultList.add(deeply.first())
                    }
                }
                fieldMap[field] = resultList
            }
            embeddedByEntityField[eid] = fieldMap
        }
    }

    // 4) Find *_lnk tables referencing this table (relations to other content types)
    val relatedLnkTables = dbSchema.tables.filter { t ->
        t.name.startsWith(tableName.split("_").take(4).joinToString("_") + "_") &&
                t.name.endsWith("_lnk") && t.foreignKeys.any { fk -> fk.referencedTable == tableName }
    }

    data class LnkRow(
        val entityId: Int,
        val field: String,
        val targetTable: String,
        val targetId: Int?,
        val order: Double?,
        val id: Int,
        val lnkTable: String
    )

    val linksByEntityField: MutableMap<Int, MutableMap<String, MutableList<LnkRow>>> = mutableMapOf()

    fun deriveOrderColumn(cols: List<DbColumn>): String? =
        cols.firstOrNull { it.name == "order" }?.name ?: cols.firstOrNull { it.name.endsWith("_ord") }?.name

    fun defaultFieldForLink(lnkTable: DbTable, targetFk: DbForeignKey): String {
        // Prefer explicit 'field' column when present; otherwise try to infer from target table or column name
        val targetTable = targetFk.referencedTable ?: return lnkTable.name
        val explicit = lnkTable.columns.firstOrNull { it.name == "field" }?.name
        if (explicit != null) return explicit // shouldn't reach here when building rows, but keep for safety
        val fromCol = targetFk.columns.firstOrNull()?.removeSuffix("_id")
        return fromCol ?: targetTable
    }

    if (relatedLnkTables.isNotEmpty()) {
        try {
            dbQuery(instance.database) {

                for (lnk in relatedLnkTables) {
                    // Parent FK column (points to current base table)
                    val parentFk = lnk.foreignKeys.firstOrNull { it.referencedTable == tableName } ?: continue
                    val parentCol = parentFk.columns.firstOrNull() ?: continue
                    val targetFks = lnk.foreignKeys.filter { it.referencedTable != tableName }
                    if (targetFks.isEmpty()) continue
                    val hasField = lnk.columns.any { it.name == "field" }
                    val orderCol = deriveOrderColumn(lnk.columns)

                    for (tfk in targetFks) {
                        val targetCol = tfk.columns.firstOrNull() ?: continue
                        val targetTable = tfk.referencedTable ?: continue
                        // Select minimal columns
                        val selectParts = mutableListOf(
                            "\"$parentCol\" as entity_id",
                            "\"$targetCol\" as related_id",
                            if (hasField) "field" else "NULL as field",
                            if (orderCol != null) "\"$orderCol\" as ord" else "NULL::double precision as ord",
                            "id"
                        )
                        val sql =
                            "SELECT ${selectParts.joinToString(", ")} FROM \"${lnk.name}\" WHERE \"$parentCol\" = ANY (ARRAY[${
                                entityIds.joinToString(",")
                            }]) ORDER BY ${if (hasField) "field, " else ""}${if (orderCol != null) "\"$orderCol\" NULLS LAST, " else ""}id"
                        exec(sql) { rs ->
                            while (rs.next()) {
                                val eid = (rs.getObject("entity_id") as? Number)?.toInt() ?: continue
                                val fieldName = if (hasField) rs.getString("field") else null
                                val derivedField = fieldName ?: defaultFieldForLink(lnk, tfk)
                                val rid = (rs.getObject("related_id") as? Number)?.toInt()
                                val ord = (rs.getObject("ord") as? Number)?.toDouble()
                                val id = (rs.getObject("id") as? Number)?.toInt() ?: 0
                                val m = linksByEntityField.getOrPut(eid) { mutableMapOf() }
                                val l = m.getOrPut(derivedField) { mutableListOf() }
                                l.add(LnkRow(eid, derivedField, targetTable, rid, ord, id, lnk.name))
                            }
                        }

                    }
                }

            }

        } catch (e: Exception) {
            logger.error("Error loading *_lnk rows for $tableName: ${e.message}", e)
        }
    }

    // 4b) File relations via files_related_mph (media)
    data class FileLinkRow(val entityId: Int, val field: String, val fileId: Int, val order: Double?, val id: Int)

    val fileLinksByEntityField: MutableMap<Int, MutableMap<String, MutableList<FileLinkRow>>> = mutableMapOf()
    if (fileRelations != null && currentSourceUid != null) {
        val idSet = entityIds.toSet()
        for (fr in fileRelations) {
            val rid = fr.relatedId
            val fid = fr.fileId
            if (fr.relatedType == currentSourceUid && rid != null && fid != null && idSet.contains(rid)) {
                val field = fr.field ?: "files"
                val m = fileLinksByEntityField.getOrPut(rid) { mutableMapOf() }
                val l = m.getOrPut(field) { mutableListOf() }
                l.add(FileLinkRow(rid, field, fid, fr.order, fr.id))
            }
        }
    }

    // 5) Attach data to base rows: embed components and link placeholders
    val enriched = baseRows.map { row ->
        val id = row["id"]?.jsonPrimitive?.intOrNull
        val additions = mutableMapOf<String, JsonElement>()

        // Components
        if (id != null) {
            val byField = embeddedByEntityField[id]
            if (byField != null) {
                for ((field, items) in byField) {
                    additions[field] = JsonArray(items)
                }
            }
        }

        // Links: collect relationships and StrapiContent.links only (no nested __link placeholders)
        if (id != null) {
            val byField = linksByEntityField[id]
            if (byField != null) {
                for ((field, rows) in byField) {
                    // Record relationship from current node to target content types via *_lnk
                    if (currentSourceUid != null) {
                        val uniqueTargets = rows.map { it.targetTable }.toSet()
                        for (tgt in uniqueTargets) {
                            val targetApiUid = dbSchema.tables.firstOrNull { it.name == tgt }?.metadata?.apiUid
                            val allowed = (tgt == "files") || (targetApiUid != null)
                            if (!allowed) continue
                            val resolvedTarget = if (tgt == "files") STRAPI_FILE_CONTENT_TYPE_NAME else targetApiUid!!
                            relationshipsCollector?.add(
                                ContentRelationship(
                                    sourceContentType = currentSourceUid,
                                    sourceField = field,
                                    targetContentType = resolvedTarget,
                                    relationType = "relation",
                                    isBidirectional = false
                                )
                            )
                        }
                    }
                    // Collect links with full field path
                    rows.sortedWith(compareBy<LnkRow> { it.order ?: Double.MAX_VALUE }.thenBy { it.id })
                        .forEach { r ->
                            val targetApiUid =
                                dbSchema.tables.firstOrNull { it.name == r.targetTable }?.metadata?.apiUid
                            val allowed = (r.targetTable == "files") || (targetApiUid != null)
                            if (!allowed) return@forEach
                            if (linksCollector != null) {
                                val rootId = entityToRoot?.get(id) ?: id
                                val fp = if (pathPrefix.isEmpty()) field else "$pathPrefix.$field"
                                val list = linksCollector.getOrPut(rootId) { mutableListOf() }
                                list.add(
                                    StrapiLinkRef(
                                        field = fp,
                                        sourceId = r.entityId,
                                        targetTable = r.targetTable,
                                        targetId = r.targetId,
                                        order = r.order,
                                        id = r.id,
                                        lnkTable = r.lnkTable
                                    )
                                )
                            }
                        }
                }
            }
        }

        // File links from files_related_mph: collect relationships and links only (no nested placeholders)
        if (id != null) {
            val byFieldFiles = fileLinksByEntityField[id]
            if (byFieldFiles != null) {
                for ((field, rows) in byFieldFiles) {
                    // Record relationship to files for this field
                    if (currentSourceUid != null) {
                        relationshipsCollector?.add(
                            ContentRelationship(
                                sourceContentType = currentSourceUid,
                                sourceField = field,
                                targetContentType = STRAPI_FILE_CONTENT_TYPE_NAME,
                                relationType = "relation",
                                isBidirectional = false
                            )
                        )
                    }
                    rows
                        .sortedWith(compareBy<FileLinkRow> { it.order ?: Double.MAX_VALUE }.thenBy { it.id })
                        .forEach { r ->
                            // Collect into StrapiContent.links with full path
                            if (linksCollector != null) {
                                val rootId = entityToRoot?.get(id) ?: id
                                val fp = if (pathPrefix.isEmpty()) field else "$pathPrefix.$field"
                                val list = linksCollector.getOrPut(rootId) { mutableListOf() }
                                list.add(
                                    StrapiLinkRef(
                                        field = fp,
                                        sourceId = r.entityId,
                                        targetTable = "files",
                                        targetId = r.fileId,
                                        order = r.order,
                                        id = r.id,
                                        lnkTable = "files_related_mph"
                                    )
                                )
                            }
                        }
                }
            }
        }

        if (additions.isEmpty()) row else JsonObject(row + additions)
    }

    return enriched
}

fun String.camelToSnakeCase(): String {
    return replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
}


fun toStrapiContent(o: JsonObject, links: List<StrapiLinkRef> = emptyList(), metadata: DbTableMetadata): StrapiContent {
    val id = o["id"]?.jsonPrimitive?.intOrNull
    val documentId = o["document_id"]?.jsonPrimitive?.content ?: ""
    val locale = o["locale"]?.jsonPrimitive?.content
    val raw = o
    val uniqueId = metadata.columns.filter { it.unique }
        .mapNotNull { raw[it.name.camelToSnakeCase()]?.toString()?.removeSurrounding("\"") }.joinToString("_")
        .ifEmpty { documentId }
    val clean = cleanObject(o)
    return StrapiContent(
        metadata = StrapiContentMetadata(id = id, documentId = documentId, uniqueId, locale),
        rawData = raw,
        cleanData = clean,
        links = links
    )
}


fun buildStatusMaps(
    singleTypes: Map<String, ContentTypeComparisonResultWithRelationships>,
    collectionTypes: Map<String, List<ContentTypeComparisonResultWithRelationships>>
): Map<String, Map<String, ContentTypeComparisonResultKind>> {
    val byType = mutableMapOf<String, MutableMap<String, ContentTypeComparisonResultKind>>()

    // Single types (key by UID from value.contentType)
    for (res in singleTypes.values) {
        val uid = res.contentType
        val docId = res.sourceContent?.metadata?.documentId
            ?: res.targetContent?.metadata?.documentId
        if (docId != null) {
            val status = res.compareState
            byType.getOrPut(uid) { mutableMapOf() }[docId] = status
        }
    }

    // Collection types (list-based) keyed by UID from entries
    for (list in collectionTypes.values) {
        val uid = list.firstOrNull()?.contentType ?: continue
        val map = byType.getOrPut(uid) { mutableMapOf() }
        list.forEach { entry ->
            val did = entry.sourceContent?.metadata?.documentId ?: entry.targetContent?.metadata?.documentId
            if (did != null) {
                map[did] = entry.compareState
            }
        }
    }

    return byType
}

private fun determineRelationshipStatusDb(
    sourceStatus: ContentTypeComparisonResultKind?,
    targetStatus: ContentTypeComparisonResultKind?
): ContentTypeComparisonResultKind? {
    if (sourceStatus == null || targetStatus == null) return null
    if (sourceStatus == ContentTypeComparisonResultKind.ONLY_IN_SOURCE || targetStatus == ContentTypeComparisonResultKind.ONLY_IN_SOURCE) {
        return ContentTypeComparisonResultKind.ONLY_IN_SOURCE
    }
    if (sourceStatus == ContentTypeComparisonResultKind.DIFFERENT || targetStatus == ContentTypeComparisonResultKind.DIFFERENT) {
        return ContentTypeComparisonResultKind.DIFFERENT
    }
    if (sourceStatus == ContentTypeComparisonResultKind.IDENTICAL && targetStatus == ContentTypeComparisonResultKind.IDENTICAL) {
        return ContentTypeComparisonResultKind.IDENTICAL
    }
    return null
}

private suspend fun fetchDocumentIdsForTable(
    instance: StrapiInstance,
    table: String,
    ids: Set<Int>,
    fileCache: Map<Int, String>
): Map<Int, String> =
    withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val result = mutableMapOf<Int, String>()
        var toCheckIds = ids
        if (table == "files") {
            val fromCache = toCheckIds.filter { fileCache.containsKey(it) }.toSet()
            fromCache.forEach { result[it] = fileCache[it]!! }
            toCheckIds = toCheckIds.filter { !fileCache.containsKey(it) }.toSet()
        }
        if (toCheckIds.isNotEmpty())
            try {

                val res = dbQuery(instance.database) {
                    val map = mutableMapOf<Int, String>()

                    val idList = ids.joinToString(",")
                    val sql = "SELECT id, document_id FROM \"$table\" WHERE id IN ($idList)"
                    exec(sql) { rs ->

                        while (rs.next()) {
                            val id = (rs.getObject("id") as? Number)?.toInt()
                            val doc = rs.getString("document_id")
                            if (id != null && doc != null) map[id] = doc
                        }
                        map
                    } ?: emptyMap()


                }
                result.putAll(res)
            } catch (e: Exception) {
                LoggerFactory.getLogger("SyncServiceDb")
                    .error("Error resolving documentIds from $table: ${e.message}", e)
            }
        result
    }


@OptIn(ExperimentalUuidApi::class)
fun compareSingleType(
    tableName: String,
    uid: String,
    sourceObj: StrapiContent?,
    targetObj: StrapiContent?,
    kind: StrapiContentTypeKind,
    fileMapping: Map<String, MergeRequestDocumentMapping>,
    contentMapping: MutableMap<String, MergeRequestDocumentMapping>
): ContentTypeComparisonResultWithRelationships {
    val resultKind: ContentTypeComparisonResultKind = when {
        sourceObj == null && targetObj == null -> ContentTypeComparisonResultKind.IDENTICAL
        sourceObj != null && targetObj == null -> ContentTypeComparisonResultKind.ONLY_IN_SOURCE
        sourceObj == null && targetObj != null -> ContentTypeComparisonResultKind.ONLY_IN_TARGET
        else -> {
            val sourceObjMap = sourceObj!!.cleanData.toMutableMap()
            sourceObjMap.remove("document_id")


            val sourceObjToCompare = JsonObject(sourceObjMap)
            val targetObjMap = targetObj!!.cleanData.toMutableMap()
            targetObjMap.remove("document_id")

            sourceObjMap["__links"]?.jsonObject?.entries?.map {  fieldValues ->
                    val mappedValues: List<JsonPrimitive> = fieldValues.value.jsonArray.map { value ->
                        val id = value.jsonPrimitive.content
                        JsonPrimitive(fileMapping[id]?.targetDocumentId ?: contentMapping[id]?.targetDocumentId ?: id)
                    }
                    fieldValues.key to JsonArray(mappedValues)

            }?.let { sourceObjMap["__links"] = JsonObject(it.toMap()) }


            val targetObjToCompare = JsonObject(targetObjMap)
            if (sourceObjToCompare == targetObjToCompare) ContentTypeComparisonResultKind.IDENTICAL else ContentTypeComparisonResultKind.DIFFERENT
        }
    }

    return ContentTypeComparisonResultWithRelationships(
        id = sourceObj?.metadata?.documentId ?: targetObj?.metadata?.documentId ?: Uuid.random().toString(),
        tableName = tableName,
        contentType = uid,
        sourceContent = sourceObj,
        targetContent = targetObj,
        compareState = resultKind,
        kind = kind,
    )
}

@OptIn(ExperimentalUuidApi::class)
fun compareCollectionType(
    tableName: String,
    uid: String,
    sourceList: List<StrapiContent>,
    targetList: List<StrapiContent>,
    kind: StrapiContentTypeKind,
    idMappings: MutableMap<String, MergeRequestDocumentMapping>,
    fileMapping: Map<String, MergeRequestDocumentMapping>,
): List<ContentTypeComparisonResultWithRelationships> {
//    val sourceByDoc = sourceList.associateBy { it.metadata.documentId }
    val sourceByUniqueId = sourceList.associateBy {
        if (idMappings.contains(it.metadata.documentId))
            it.metadata.documentId
        else
            it.metadata.uniqueKey
    }
//    val targetByDoc = targetList.associateBy { it.metadata.documentId }
    val targetByUniqueId = targetList.associateBy { t ->
        idMappings.filter { it.value.targetDocumentId == t.metadata.documentId }.map { it.key }.firstOrNull()
            ?: t.metadata.uniqueKey
    }

    val results = mutableListOf<ContentTypeComparisonResultWithRelationships>()
    val allKeys = sourceByUniqueId.keys union targetByUniqueId.keys
    for (k in allKeys) {
        val s = sourceByUniqueId[k]
        val t = targetByUniqueId[k]
        compareSingleType(tableName, uid, s, t, kind, fileMapping, idMappings).also { results.add(it) }
    }
    return results
}


// Download files and compute robust fingerprints (and size) with limited concurrency
suspend fun computeFingerprints(instance: StrapiInstance, files: List<StrapiImage>): List<StrapiImage> =
    coroutineScope {
        val logger = LoggerFactory.getLogger("SyncServiceDb")
        val client = StrapiClient(instance)
        val semaphore = Semaphore(6)
        files.map { img ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val tmp = client.downloadFile(img)
                        val bytes = tmp.readBytes()
                        tmp.delete()
                        val fp = FileFingerprintUtil.compute(bytes, img.metadata.mime, img.metadata.ext)
                        val newMeta = img.metadata.copy(
                            calculatedHash = fp.value,
                            calculatedSizeBytes = bytes.size.toLong()
                        )
                        img.copy(metadata = newMeta)
                    } catch (e: Exception) {
                        logger.warn("Fingerprint failed for file id=${img.metadata.id} on '${instance.name}': ${e.message}")
                        img
                    }
                }
            }
        }.awaitAll()
    }
