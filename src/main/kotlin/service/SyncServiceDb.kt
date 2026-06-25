package it.sebi.service

import it.sebi.client.StrapiClient
import it.sebi.database.dbQuery
import it.sebi.models.*
import it.sebi.service.identity.SyncIdentityService
import it.sebi.utils.FileFingerprintUtil
import it.sebi.tables.FileAnalysisCacheTable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import utils.shortenTableName
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * DB-based schema compatibility check for Strapi instances.
 * Reads latest JSON from strapi_database_schema.schema in each instance DB and compares structures.
 */


/** documentId -> sync_id for every entry in an instance's sync_identity sidecar (files + content).
 *  Used to normalize relation references to a cross-instance identity during comparison. */
suspend fun fetchSyncIdMap(instance: StrapiInstance): Map<String, String> = try {
    dbQuery(instance.database) {
        val m = HashMap<String, String>()
        exec("SELECT document_id, sync_id FROM sync_identity", explicitStatementType = StatementType.SELECT) { rs ->
            while (rs.next()) {
                val d = rs.getString("document_id"); val s = rs.getString("sync_id")
                if (d != null && s != null) m[d] = s
            }
        }
        m as Map<String, String>
    } ?: emptyMap()
} catch (e: Exception) {
    LoggerFactory.getLogger("SyncServiceDb").warn("fetchSyncIdMap('${instance.name}') failed: ${e.message}")
    emptyMap()
}

suspend fun fetchFilesFromDb(instance: StrapiInstance): List<StrapiImage> = withContext(Dispatchers.IO) {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    try {

        val sql = """
            WITH RECURSIVE folder_hierarchy AS (
                -- Caso base: cartelle radice (quelle senza parent nella tabella di link)
                SELECT
                    uf.id,
                    uf.path,
                    uf.name,
                    ('/' || uf.name)::text as full_path
                FROM upload_folders uf
                LEFT JOIN upload_folders_parent_lnk l ON uf.id = l.folder_id
                WHERE l.inv_folder_id IS NULL

                UNION ALL

                -- Caso ricorsivo: cartelle che hanno un parent
                SELECT
                    uf.id,
                    uf.path,
                    uf.name,
                    (fh.full_path || '/' || uf.name)::text as full_path
                FROM upload_folders uf
                JOIN upload_folders_parent_lnk l ON uf.id = l.folder_id
                JOIN folder_hierarchy fh ON l.inv_folder_id = fh.id
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
                COALESCE(fh.full_path, '/') as folder_name,
                si.sync_id as sync_id
            FROM files f
                     LEFT JOIN folder_hierarchy fh ON fh.path = f.folder_path
                     LEFT JOIN sync_identity si
                        ON si.document_id = f.document_id
                        AND COALESCE(si.locale, '') = COALESCE(f.locale, '')
                        AND si.uid = 'plugin::upload.file'
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
                    val folderName = rs.getString("folder_name") ?: "/"
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
                        folder = folderName,
                        locale = rs.getString("locale"),
                        updatedAt = updatedAt ?: OffsetDateTime.now(),
                        syncId = rs.getString("sync_id")
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
                        put("folder", folderName)
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
        // Identity layer (Phase 1): attach the shared sync_id as "__sync_id" here too, since this
        // prefetched TableMap is preferred over fetchPublishedRowsAsJson's own query.
        val syncIdJoin = if (table.columns.any { it.name == "locale" })
            "LEFT JOIN ${SyncIdentityService.TABLE} si ON si.document_id = t.document_id AND si.locale IS NOT DISTINCT FROM t.locale"
        else
            "LEFT JOIN ${SyncIdentityService.TABLE} si ON si.document_id = t.document_id AND si.locale IS NULL"
        """SELECT '${table.name}' as table_n,
                    t.document_id,
                    to_jsonb(t) || jsonb_build_object('__sync_id', si.sync_id) AS obj
                    FROM "${table.name}" t
                    $syncIdJoin
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

    // 0) Make sure the sync_identity sidecar exists on both instances so the file fetch
    //    can LEFT JOIN it for durable identity (no-op if already present).
    if (!mergeRequest.sourceInstance.isVirtual) it.sebi.service.identity.SyncIdentityService.ensureTable(mergeRequest.sourceInstance)
    if (!mergeRequest.targetInstance.isVirtual) it.sebi.service.identity.SyncIdentityService.ensureTable(mergeRequest.targetInstance)

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

    // 4) Deterministic, conservative pairing state. Tiers: explicit mapping ->
    //    shared syncId -> exact bytes (sha256, unique 1:1) -> name+ext+folderPath (unique 1:1).
    //    No greedy dHash matching (it mis-pairs duplicates and steals true twins); ambiguous
    //    groups are left unpaired so a human can confirm them.
    val targetByDocKey: Map<String, StrapiImage> = targetWithFp.associateBy { keyOfTarget(it.metadata) }
    val usedTargetIds = mutableSetOf<Int>()
    val pairedSourceIds = mutableSetOf<Int>()
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
                
                val isIdentical = if (!sourceFp.isNullOrBlank() && !targetFp.isNullOrBlank()) {
                    if (sourceFp == targetFp) {
                        true
                    } else {
                        // Check Hamming distance for dHash (256 bits). 
                        // A distance of 10-15 bits is usually safe for "almost identical" images.
                        val dist = FileFingerprintUtil.hammingDistance(sourceFp, targetFp)
                        dist <= 12 // approx 4.7% of 256 bits
                    }
                } else false

                if (isIdentical) {
                    // Even if the fingerprint is "identical", if metadata is very different, mark as DIFFERENT
                    // This prevents collisions on very simple images like black logos on white background.
                    val sourceSize = sm.calculatedSizeBytes ?: sm.size.toLong()
                    val targetSize = tm.calculatedSizeBytes ?: tm.size.toLong()
                    val sizeDiff = Math.abs(sourceSize - targetSize).toDouble()
                    val maxSize = Math.max(sourceSize, targetSize).toDouble()
                    val sizeToleranceOk = if (maxSize > 0) (sizeDiff / maxSize) < 0.25 else true

                    val nameSimilarityOk = sm.name.equals(tm.name, ignoreCase = true) ||
                            sm.name.contains(tm.name, ignoreCase = true) ||
                            tm.name.contains(sm.name, ignoreCase = true)

                    if (!sizeToleranceOk && !nameSimilarityOk) {
                        ContentTypeComparisonResultKind.DIFFERENT
                    } else {
                        ContentTypeComparisonResultKind.IDENTICAL
                    }
                } else {
                    val sourceSize = sm.calculatedSizeBytes ?: sm.size.toLong()
                    val targetSize = tm.calculatedSizeBytes ?: tm.size.toLong()

                    if (!sourceFp.isNullOrBlank() && !targetFp.isNullOrBlank()) {
                        ContentTypeComparisonResultKind.DIFFERENT
                    } else {
                        val sourceSizeKB = (sourceSize + 512) / 1024
                        val targetSizeKB = (targetSize + 512) / 1024

                        // Introduce a tolerance for size comparison (e.g. 20%)
                        val sizeDiff = Math.abs(sourceSize - targetSize).toDouble()
                        val maxSize = Math.max(sourceSize, targetSize).toDouble()
                        val sizeToleranceOk = if (maxSize > 0) (sizeDiff / maxSize) < 0.20 else true

                        val isDifferent = when {
                            (!sizeToleranceOk) -> true
                            (sm.folderPath != tm.folderPath) -> true
                            (sm.name != tm.name) -> true
                            (sm.alternativeText != tm.alternativeText) -> true
                            (sm.caption != tm.caption) -> true
                            else -> false
                        }
                        if (isDifferent) ContentTypeComparisonResultKind.DIFFERENT else ContentTypeComparisonResultKind.IDENTICAL
                    }
                }
            }

            else -> ContentTypeComparisonResultKind.IDENTICAL
        }
    }

    fun pairUp(s: StrapiImage, t: StrapiImage) {
        results.add(ContentTypeFileComparisonResult(sourceImage = s, targetImage = t, compareState = compareStateOf(s, t)))
        usedTargetIds.add(t.metadata.id)
        pairedSourceIds.add(s.metadata.id)
    }
    fun remainingSources(): List<StrapiImage> = sourceWithFp.filter { it.metadata.id !in pairedSourceIds }
    fun remainingTargets(): List<StrapiImage> = targetWithFp.filter { it.metadata.id !in usedTargetIds }
    fun nameKey(m: StrapiImageMetadata): String =
        "${m.name.trim().lowercase()}|${m.ext.lowercase()}|${m.folderPath}|${m.locale ?: ""}"

    // Tier 0 — explicit manual mappings (human-confirmed) by documentId.
    for (s in sourceWithFp) {
        val mapped = currentFileMapping[s.metadata.documentId]?.targetDocumentId ?: continue
        val t = targetByDocKey[keyOf(mapped, s.metadata.locale)] ?: continue
        if (t.metadata.id in usedTargetIds) continue
        pairUp(s, t)
    }

    // Tier 1 — durable shared syncId (exact identity join).
    run {
        val tgtBySync = remainingTargets().filter { !it.metadata.syncId.isNullOrBlank() }.groupBy { it.metadata.syncId!! }
        for (s in remainingSources()) {
            val sid = s.metadata.syncId?.takeIf { it.isNotBlank() } ?: continue
            val t = tgtBySync[sid]?.firstOrNull { it.metadata.id !in usedTargetIds } ?: continue
            pairUp(s, t)
        }
    }

    // Tier 2 — exact same bytes (sha256), only when unique 1:1 on both sides.
    run {
        val srcBySha = remainingSources().filter { !it.metadata.calculatedSha.isNullOrBlank() }.groupBy { it.metadata.calculatedSha!! }
        val tgtBySha = remainingTargets().filter { !it.metadata.calculatedSha.isNullOrBlank() }.groupBy { it.metadata.calculatedSha!! }
        for ((sha, sList) in srcBySha) {
            val tList = tgtBySha[sha] ?: continue
            if (sList.size == 1 && tList.size == 1) pairUp(sList.first(), tList.first())
        }
    }

    // Tier 3 — same name+ext+folderPath (+locale), only when unique 1:1 on both sides
    //          (handles re-encoded same asset whose bytes/dHash changed).
    run {
        val srcByName = remainingSources().groupBy { nameKey(it.metadata) }
        val tgtByName = remainingTargets().groupBy { nameKey(it.metadata) }
        for ((k, sList) in srcByName) {
            val tList = tgtByName[k] ?: continue
            if (sList.size == 1 && tList.size == 1) pairUp(sList.first(), tList.first())
        }
    }

    // Tier 4 — leftovers: genuinely one-sided OR ambiguous (duplicates / collisions) left for review.
    for (s in remainingSources())
        results.add(ContentTypeFileComparisonResult(sourceImage = s, targetImage = null, compareState = ContentTypeComparisonResultKind.ONLY_IN_SOURCE))
    for (t in remainingTargets())
        results.add(ContentTypeFileComparisonResult(sourceImage = null, targetImage = t, compareState = ContentTypeComparisonResultKind.ONLY_IN_TARGET))

    results
}


val TECHNICAL_FIELDS = setOf(
    "id", "created_by_id", "updated_by_id", "created_at", "updated_at", "published_at"
)
private val IGNORE_COMPARE_FIELDS = TECHNICAL_FIELDS + setOf("locale", "__sync_id")

private fun parseJsonObject(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

fun cleanObject(o: JsonObject, excludedFields: Set<String> = emptySet(), currentPath: String = ""): JsonObject {
    val sortedMap = o.entries
        .filter { (key, _) ->
            val fullPath = if (currentPath.isEmpty()) key else "$currentPath.$key"
            !IGNORE_COMPARE_FIELDS.contains(key) && !excludedFields.contains(fullPath)
        }
        .sortedBy { it.key }
        .associate { (key, value) ->
            val fullPath = if (currentPath.isEmpty()) key else "$currentPath.$key"
            key to cleanValue(value, excludedFields, fullPath)
        }

    // Usa LinkedHashMap per preservare l'ordine
    return JsonObject(LinkedHashMap(sortedMap))

}

private fun cleanArray(arr: JsonArray, excludedFields: Set<String> = emptySet(), currentPath: String = ""): JsonArray = JsonArray(
    arr.map { cleanValue(it, excludedFields, currentPath) }
        .let { items ->
            if (items.any { it is JsonObject && it.containsKey("__order") }) {
                items.sortedBy { (it as? JsonObject)?.get("__order")?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }.map {
                    val o = it.jsonObject.toMutableMap()
                    val order = o["__order"]?.jsonPrimitive
                    o["__order"] = JsonPrimitive(order?.intOrNull ?: order?.doubleOrNull?.toInt())
                    JsonObject(o)
                }
            } else {
                items
            }
        }
)

private fun cleanValue(element: JsonElement, excludedFields: Set<String> = emptySet(), currentPath: String = ""): JsonElement = when (element) {
    is JsonObject -> cleanObject(element, excludedFields, currentPath)
    is JsonArray -> cleanArray(element, excludedFields, currentPath)
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
        // Identity layer: attach the shared sync_id (Phase 1) into each row JSON as "__sync_id".
        // Locale-aware join so non-localized content types (no locale column) don't break the SQL.
        // sync_identity is ensured to exist by the prefetch/export entry points before this runs.
        val hasLocaleColumn = dbSchema.tables.firstOrNull { it.name == table }?.columns?.any { it.name == "locale" } == true
        val syncIdJoin = if (hasLocaleColumn) {
            "LEFT JOIN ${SyncIdentityService.TABLE} si ON si.document_id = t.document_id AND si.locale IS NOT DISTINCT FROM t.locale"
        } else {
            "LEFT JOIN ${SyncIdentityService.TABLE} si ON si.document_id = t.document_id AND si.locale IS NULL"
        }
        val baseRows = tableMap[table]?.values?.toList() ?: dbQuery(instance.database) {
            exec(
                """
                    SELECT
                           to_jsonb(t) || jsonb_build_object('__sync_id', si.sync_id) AS obj
                    FROM "$table" t
                    $syncIdJoin
                    WHERE t.document_id IS NOT NULL AND t.published_at IS NOT NULL
                    ORDER BY t.document_id, t.published_at DESC, t.id DESC
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

    if(best != null) return best

    val candidateA = "components_${norm}"


    val shortNameA = shortenTableName(candidateA)
    val shortNameB = shortenTableName(candidate)
    val hashA = shortNameA.takeLast(5)
    val hashB = shortNameB.takeLast(5)
    val best2 = all.filter { it.startsWith("components_") && (it.endsWith(hashA) || it.endsWith(hashB)) }
        .maxByOrNull { it.length }
    return best2
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
                        val compTable2 = resolveComponentTableName(r.componentType, dbSchema)
                        println(compTable2)
//                        error("Unable to resolve component table for ${r.componentType}")
                        val placeholder = buildJsonObject {
                            put("cmp_id", r.cmpId)
                            if (r.componentType != null) put(
                                "component_type",
                                r.componentType
                            ) else put("component_type", JsonNull)
                            if (r.order != null) put("order", r.order) else put("order", JsonNull)
                        }
                        resultList.add(placeholder)
                    }
                    else {
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


    val relatedLnkTables = dbSchema.tables.filter { t ->
                t.name.endsWith("_lnk") && t.foreignKeys.firstOrNull()?.referencedTable == tableName
    }

//    val relatedLnkTablesUndirect = dbSchema.tables.filter { t ->
//        t.name.endsWith("_lnk") && t.foreignKeys.lastOrNull()?.referencedTable == tableName
//    }



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
                    val parentFk = lnk.foreignKeys.find { it.referencedTable == tableName } ?: continue
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


fun toStrapiContent(
    o: JsonObject,
    links: List<StrapiLinkRef> = emptyList(),
    metadata: DbTableMetadata,
    excludedFields: Set<String> = emptySet()
): StrapiContent {
    val id = o["id"]?.jsonPrimitive?.intOrNull
    val documentId = o["document_id"]?.jsonPrimitive?.content ?: ""
    // contentOrNull (not content): a JSON null locale must stay null, not become the string "null".
    val locale = o["locale"]?.jsonPrimitive?.contentOrNull
    val syncId = o["__sync_id"]?.jsonPrimitive?.contentOrNull
    // Strip the identity marker from rawData so it never leaks into payloads sent to Strapi
    // (it lives only in metadata.syncId). It is also excluded from comparison via IGNORE_COMPARE_FIELDS.
    val raw = if (o.containsKey("__sync_id")) JsonObject(o.filterKeys { it != "__sync_id" }) else o
    val uniqueId = metadata.columns.filter { it.unique }
        .mapNotNull { raw[it.name.camelToSnakeCase()]?.toString()?.removeSurrounding("\"") }.joinToString("_")
        .ifEmpty { documentId }
    val clean = cleanObject(raw, excludedFields)
    return StrapiContent(
        metadata = StrapiContentMetadata(id = id, documentId = documentId, uniqueId, locale, syncId = syncId),
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
// Identity/timestamp fields dropped when fingerprinting an entry's content for bootstrap matching.
private val FINGERPRINT_DROP = setOf(
    "id", "document_id", "documentId", "__sync_id", "locale",
    "createdAt", "updatedAt", "publishedAt", "created_at", "updated_at", "published_at",
    "created_by_id", "updated_by_id", "createdBy", "updatedBy",
)

/** Replace a __links object's per-field documentId lists with the referenced entities' IDENTITY
 *  (sync_id, else content/file hash via the passed map), SORTED. So the SAME logical references
 *  compare equal across instances regardless of per-instance documentId or order (duplicate targets
 *  referenced in a different order are not a change), while add/remove of a reference still shows. */
fun normalizeLinksBySyncId(objMap: MutableMap<String, JsonElement>, syncByDoc: Map<String, String>) {
    val links = objMap["__links"] as? JsonObject ?: return
    objMap["__links"] = JsonObject(links.entries.associate { (field, arr) ->
        val a = arr as? JsonArray ?: JsonArray(emptyList())
        val keys = a.map { v -> v.jsonPrimitive.contentOrNull?.let { id -> syncByDoc[id] ?: "doc:$id" } ?: "null" }.sorted()
        field to JsonArray(keys.map { JsonPrimitive(it) })
    })
}

/** Canonical, identity-independent fingerprint of an entry's content (relations normalized to sync_id),
 *  used to pair entries that have no stable key yet (bootstrap). */
fun contentFingerprint(c: StrapiContent, syncByDoc: Map<String, String>): String {
    fun canonLinks(v: JsonElement): JsonElement {
        val obj = v as? JsonObject ?: return v
        return JsonObject(obj.entries.sortedBy { it.key }.associate { (field, arr) ->
            val a = arr as? JsonArray ?: JsonArray(emptyList())
            field to JsonArray(a.map { x ->
                val id = x.jsonPrimitive.contentOrNull
                JsonPrimitive(if (id == null) null else (syncByDoc[id] ?: "doc:$id"))
            })
        })
    }
    fun canon(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(
            e.filterKeys { it !in FINGERPRINT_DROP }.toSortedMap()
                .mapValues { (k, v) -> if (k == "__links") canonLinks(v) else canon(v) }
        )
        is JsonArray -> JsonArray(e.map { canon(it) })
        else -> e
    }
    return canon(c.cleanData).toString()
}

private val LABEL_NAME_HINTS = listOf("title", "name", "label", "heading", "subject", "slug", "code")

private fun isLabelField(key: String): Boolean { val k = key.lowercase(); return LABEL_NAME_HINTS.any { k.contains(it) } }
private fun shortText(v: JsonElement?): String? =
    (v as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() && it.length in 1..80 }

/**
 * A short human label for a content entry. Looks (recursively) for a field whose NAME hints a label
 * (title/name/label/heading… as a substring, so title_main/needAssistanceSectionTitle match too),
 * then for any short text; falls back to the documentId only as a last resort.
 */
fun contentLabelOf(c: StrapiContent): String {
    fun search(e: JsonElement, labelFieldsOnly: Boolean): String? {
        when (e) {
            is JsonObject -> {
                for ((k, v) in e) {
                    if (k == "__links" || k in FINGERPRINT_DROP) continue
                    if (v is JsonPrimitive && (!labelFieldsOnly || isLabelField(k))) shortText(v)?.let { return it }
                }
                for ((k, v) in e) if (k != "__links" && k !in FINGERPRINT_DROP) search(v, labelFieldsOnly)?.let { return it }
            }
            is JsonArray -> for (x in e) search(x, labelFieldsOnly)?.let { return it }
            else -> {}
        }
        return null
    }
    return search(c.cleanData, true) ?: search(c.cleanData, false) ?: c.metadata.documentId
}

/**
 * documentId -> CONTENT IDENTITY for one instance. Same content yields the same key (even for
 * duplicate entities), so references are compared by what they point to, not by which copy/order.
 * Files: by exact bytes (sha) then syncId then name. Content: by canonical content fingerprint.
 */
fun buildIdentityMap(rowsByUid: Map<String, List<StrapiContent>>, files: List<StrapiImage>, syncByDoc: Map<String, String>): Map<String, String> {
    val m = HashMap<String, String>()
    // Prefer the stable identity (sync_id): a referenced entity's identity must NOT change when its
    // own content changes (otherwise the change would wrongly propagate to everything referencing it).
    // Fall back to a content/byte hash only when there is no sync_id yet, so identical un-linked
    // duplicates still collapse to one key.
    files.forEach { f ->
        m[f.metadata.documentId] = f.metadata.syncId?.let { "id:$it" } ?: ("file:" + (f.metadata.calculatedSha ?: f.metadata.name))
    }
    rowsByUid.values.flatten().forEach { c ->
        m[c.metadata.documentId] = c.metadata.syncId?.let { "id:$it" } ?: ("c:" + contentFingerprint(c, syncByDoc))
    }
    return m
}

/** Build documentId -> ResolvedRef for one instance (files + content), for resolving __links. */
fun buildRefResolver(rowsByUid: Map<String, List<StrapiContent>>, files: List<StrapiImage>, identityByDoc: Map<String, String> = emptyMap()): Map<String, ResolvedRef> {
    val m = HashMap<String, ResolvedRef>()
    files.forEach { f ->
        m[f.metadata.documentId] = ResolvedRef(
            documentId = f.metadata.documentId, label = f.metadata.name, syncId = f.metadata.syncId,
            isFile = true, fileId = f.metadata.id, mime = f.metadata.mime, contentHash = identityByDoc[f.metadata.documentId],
        )
    }
    rowsByUid.forEach { (uid, list) ->
        val short = uid.substringAfterLast('.')
        list.forEach { c ->
            m[c.metadata.documentId] = ResolvedRef(
                documentId = c.metadata.documentId, label = contentLabelOf(c), syncId = c.metadata.syncId, isFile = false,
                contentHash = identityByDoc[c.metadata.documentId], refType = short,
            )
        }
    }
    return m
}

/** Resolve an entry's __links (field -> [documentId]) into readable references. */
fun resolveRefs(c: StrapiContent?, resolver: Map<String, ResolvedRef>): Map<String, List<ResolvedRef>> {
    val links = c?.cleanData?.get("__links") as? JsonObject ?: return emptyMap()
    return links.entries.associate { (field, arr) ->
        field to ((arr as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.map { id -> resolver[id] ?: ResolvedRef(documentId = id, label = "doc:$id") } ?: emptyList())
    }
}

fun compareSingleType(
    tableName: String,
    uid: String,
    sourceObj: StrapiContent?,
    targetObj: StrapiContent?,
    kind: StrapiContentTypeKind,
    fileMapping: Map<String, MergeRequestDocumentMapping>,
    contentMapping: MutableMap<String, MergeRequestDocumentMapping>,
    exclusions: List<MergeRequestExclusion> = emptyList(),
    srcSyncByDoc: Map<String, String> = emptyMap(),
    tgtSyncByDoc: Map<String, String> = emptyMap(),
    srcResolver: Map<String, ResolvedRef> = emptyMap(),
    tgtResolver: Map<String, ResolvedRef> = emptyMap(),
): ContentTypeComparisonResultWithRelationships {
    val documentId = sourceObj?.metadata?.documentId ?: targetObj?.metadata?.documentId
    val isExcluded = exclusions.any { it.contentType == uid && (it.documentId == null || it.documentId == documentId) && it.fieldPath == null }

    val resultKind: ContentTypeComparisonResultKind = when {
        isExcluded -> ContentTypeComparisonResultKind.EXCLUDED
        sourceObj == null && targetObj == null -> ContentTypeComparisonResultKind.IDENTICAL
        sourceObj != null && targetObj == null -> ContentTypeComparisonResultKind.ONLY_IN_SOURCE
        sourceObj == null && targetObj != null -> ContentTypeComparisonResultKind.ONLY_IN_TARGET
        else -> {
            val excludedFields = exclusions
                .filter { it.contentType == uid && (it.documentId == null || it.documentId == documentId) && it.fieldPath != null }
                .mapNotNull { it.fieldPath }
                .toSet()

            val sourceObjMap = sourceObj!!.cleanData.toMutableMap()
            sourceObjMap.remove("document_id")

            val targetObjMap = cleanObject(targetObj!!.cleanData, excludedFields).toMutableMap()
            targetObjMap.remove("document_id")

            // Normalize relation/media references to a cross-instance identity (sync_id): the SAME
            // logical target (different per-instance documentId) compares equal, while a genuinely
            // different reference still surfaces as a change. Falls back to "doc:<id>" when the
            // referenced entity has no sync_id yet (so it stays comparable, just not cross-env).
            normalizeLinksBySyncId(sourceObjMap, srcSyncByDoc)
            normalizeLinksBySyncId(targetObjMap, tgtSyncByDoc)

            val sourceObjToCompare = cleanObject(JsonObject(sourceObjMap), excludedFields)
            val targetObjToCompare = JsonObject(targetObjMap)
            if (sourceObjToCompare == targetObjToCompare) ContentTypeComparisonResultKind.IDENTICAL else ContentTypeComparisonResultKind.DIFFERENT
        }
    }

    return ContentTypeComparisonResultWithRelationships(
        id = documentId ?: Uuid.random().toString(),
        tableName = tableName,
        contentType = uid,
        sourceContent = sourceObj,
        targetContent = targetObj,
        compareState = resultKind,
        kind = kind,
        sourceRefs = resolveRefs(sourceObj, srcResolver),
        targetRefs = resolveRefs(targetObj, tgtResolver),
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
    exclusions: List<MergeRequestExclusion> = emptyList(),
    srcSyncByDoc: Map<String, String> = emptyMap(),
    tgtSyncByDoc: Map<String, String> = emptyMap(),
    srcResolver: Map<String, ResolvedRef> = emptyMap(),
    tgtResolver: Map<String, ResolvedRef> = emptyMap(),
): List<ContentTypeComparisonResultWithRelationships> {
    val sourceByDoc = sourceList.associateBy { it.metadata.documentId }
    val targetByDoc = targetList.associateBy { it.metadata.documentId }

    val results = mutableListOf<ContentTypeComparisonResultWithRelationships>()
    val processedSourceDocs = mutableSetOf<String>()
    val processedTargetDocs = mutableSetOf<String>()

    val (existingSrcInst, existingTgtInst) = idMappings.values.firstOrNull()
        ?.let { it.sourceStrapiInstanceId to it.targetStrapiInstanceId } ?: (0 to 0)

    fun seed(s: StrapiContent, t: StrapiContent) {
        idMappings[s.metadata.documentId] = MergeRequestDocumentMapping(
            sourceStrapiInstanceId = existingSrcInst, targetStrapiInstanceId = existingTgtInst,
            contentType = uid, sourceId = s.metadata.id, sourceDocumentId = s.metadata.documentId,
            targetId = t.metadata.id, targetDocumentId = t.metadata.documentId,
        )
    }
    fun pair(s: StrapiContent, t: StrapiContent) {
        results.add(compareSingleType(tableName, uid, s, t, kind, fileMapping, idMappings, exclusions, srcSyncByDoc, tgtSyncByDoc, srcResolver, tgtResolver))
        processedSourceDocs.add(s.metadata.documentId)
        processedTargetDocs.add(t.metadata.documentId)
        seed(s, t) // so relations pointing at this entity resolve to the right target downstream
    }

    // 0. Shared syncId (exact identity) — highest priority.
    val targetBySyncId: Map<String, StrapiContent> = targetList
        .filter { !it.metadata.syncId.isNullOrBlank() }.associateBy { it.metadata.syncId!! }
    for (s in sourceList) {
        val sid = s.metadata.syncId
        if (sid.isNullOrBlank()) continue
        val t = targetBySyncId[sid] ?: continue
        if (t.metadata.documentId in processedTargetDocs) continue
        pair(s, t)
    }

    // 1. Explicit (manual / previously-mapped) document mappings for this uid.
    val mappedSourceToTarget = idMappings.filterValues { it.contentType == uid }.mapValues { it.value.targetDocumentId }
    for ((srcDoc, tgtDoc) in mappedSourceToTarget) {
        if (srcDoc in processedSourceDocs || (tgtDoc != null && tgtDoc in processedTargetDocs)) continue
        val s = sourceByDoc[srcDoc]
        val t = targetByDoc[tgtDoc]
        if (s != null && t != null) pair(s, t)
        else if (s != null || t != null) {
            results.add(compareSingleType(tableName, uid, s, t, kind, fileMapping, idMappings, exclusions, srcSyncByDoc, tgtSyncByDoc, srcResolver, tgtResolver))
            if (s != null) processedSourceDocs.add(srcDoc)
            if (t != null) processedTargetDocs.add(tgtDoc!!)
        }
    }

    // 2. CONTENT FINGERPRINT (identical content modulo ids/relations, relations normalized by syncId),
    //    only when unique 1:1 on both sides — safe bootstrap for entries without a stable key.
    run {
        val remS = sourceList.filter { it.metadata.documentId !in processedSourceDocs }
        val remT = targetList.filter { it.metadata.documentId !in processedTargetDocs }
        val sByFp = remS.groupBy { contentFingerprint(it, srcSyncByDoc) }
        val tByFp = remT.groupBy { contentFingerprint(it, tgtSyncByDoc) }
        for ((fp, sList) in sByFp) {
            val tList = tByFp[fp] ?: continue
            // Identical content modulo ids/relations. Pair 1:1, and also N:N — when the SAME content
            // appears the same number of times on both sides those are the same (duplicated) entities,
            // so pair them positionally. Unequal counts stay unmatched (ambiguous -> review).
            if (sList.size == tList.size) for (i in sList.indices) pair(sList[i], tList[i])
        }
    }

    // 3. Representative NATURAL KEY (title/name/slug/code…), unique 1:1 — pairs same-key entries
    //    whose content differs (they then show up as DIFFERENT with the real field diff).
    run {
        fun nk(c: StrapiContent): String? {
            val cd = c.cleanData
            for (f in listOf("title", "name", "slug", "code", "label", "uid", "key")) {
                val v = (cd[f] as? JsonPrimitive)?.contentOrNull
                if (!v.isNullOrBlank()) return "$f=$v"
            }
            return null
        }
        val remS = sourceList.filter { it.metadata.documentId !in processedSourceDocs }
        val remT = targetList.filter { it.metadata.documentId !in processedTargetDocs }
        val sByNk = remS.mapNotNull { c -> nk(c)?.let { it to c } }.groupBy({ it.first }, { it.second })
        val tByNk = remT.mapNotNull { c -> nk(c)?.let { it to c } }.groupBy({ it.first }, { it.second })
        for ((k, sList) in sByNk) {
            val tList = tByNk[k] ?: continue
            if (sList.size == 1 && tList.size == 1) pair(sList.first(), tList.first())
        }
    }

    // 3b. RELATION FOOTPRINT (the sorted set of referenced identities), unique 1:1 — pairs keyless
    //     entities that point to the same related entities/files but differ in scalar fields (so they
    //     show up as DIFFERENT with the real change, instead of new+removed).
    run {
        fun footprint(c: StrapiContent, idmap: Map<String, String>): String? {
            val links = c.cleanData["__links"] as? JsonObject ?: return null
            val ids = links.values.flatMap { a -> (a as? JsonArray ?: JsonArray(emptyList())).mapNotNull { it.jsonPrimitive.contentOrNull } }
            if (ids.isEmpty()) return null
            return ids.map { idmap[it] ?: "doc:$it" }.sorted().joinToString(",")
        }
        val remS = sourceList.filter { it.metadata.documentId !in processedSourceDocs }
        val remT = targetList.filter { it.metadata.documentId !in processedTargetDocs }
        val sByFp = remS.mapNotNull { c -> footprint(c, srcSyncByDoc)?.let { it to c } }.groupBy({ it.first }, { it.second })
        val tByFp = remT.mapNotNull { c -> footprint(c, tgtSyncByDoc)?.let { it to c } }.groupBy({ it.first }, { it.second })
        for ((k, sList) in sByFp) {
            val tList = tByFp[k] ?: continue
            if (sList.size == 1 && tList.size == 1) pair(sList.first(), tList.first())
        }
    }

    // 4. Fallback: legacy uniqueKey (scalar unique columns), positional within key. Also emits the
    //    remaining genuinely one-sided entries (create on source / delete on target).
    val remainingSource = sourceList.filter { it.metadata.documentId !in processedSourceDocs }
    val remainingTarget = targetList.filter { it.metadata.documentId !in processedTargetDocs }
    val sourceByUniqueKey = remainingSource.groupBy { it.metadata.uniqueKey }
    val targetByUniqueKey = remainingTarget.groupBy { it.metadata.uniqueKey }
    val allUniqueKeys = sourceByUniqueKey.keys union targetByUniqueKey.keys
    for (key in allUniqueKeys) {
        val sList = sourceByUniqueKey[key] ?: emptyList()
        val tList = targetByUniqueKey[key] ?: emptyList()
        val maxLen = maxOf(sList.size, tList.size)
        for (i in 0 until maxLen) {
            val s = sList.getOrNull(i)
            val t = tList.getOrNull(i)
            results.add(compareSingleType(tableName, uid, s, t, kind, fileMapping, idMappings, exclusions, srcSyncByDoc, tgtSyncByDoc, srcResolver, tgtResolver))
            if (s != null) processedSourceDocs.add(s.metadata.documentId)
            if (t != null) processedTargetDocs.add(t.metadata.documentId)
        }
    }

    return results
}


// Download files and compute robust fingerprints (and size) with limited concurrency
suspend fun computeFingerprints(instance: StrapiInstance, files: List<StrapiImage>): List<StrapiImage> =
    coroutineScope {
        val logger = LoggerFactory.getLogger("SyncServiceDb")
        val client = StrapiClient(instance)
        val semaphore = Semaphore(6)

        // 1. Fetch existing cache entries for this instance
        data class CacheEntry(val hash: String, val size: Long, val sha: String?, val updatedAt: String?)
        val cacheMap = dbQuery {
            FileAnalysisCacheTable.selectAll()
                .where { FileAnalysisCacheTable.instanceId eq instance.id }
                .associate {
                    it[FileAnalysisCacheTable.documentId] to CacheEntry(
                        hash = it[FileAnalysisCacheTable.calculatedHash],
                        size = it[FileAnalysisCacheTable.calculatedSizeBytes],
                        sha = it[FileAnalysisCacheTable.calculatedSha],
                        updatedAt = it[FileAnalysisCacheTable.updatedAtStr],
                    )
                }
        }

        files.map { img ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    try {
                        val docId = img.metadata.documentId
                        val currentUpdatedAt = img.rawData["updated_at"]?.jsonPrimitive?.contentOrNull
                            ?: img.rawData["updatedAt"]?.jsonPrimitive?.contentOrNull

                        // 2. Check if we have a valid cache entry (require sha present so older
                        //    cache rows are recomputed once to backfill the exact-bytes digest).
                        val cached = cacheMap[docId]
                        if (cached != null && cached.sha != null) {
                            val cachedHash = cached.hash
                            val cachedSize = cached.size
                            val cachedUpdatedAt = cached.updatedAt
                            if (currentUpdatedAt == cachedUpdatedAt) {
                                // Update last used timestamp in background
                                launch {
                                    dbQuery {
                                        FileAnalysisCacheTable.update({ (FileAnalysisCacheTable.instanceId eq instance.id) and (FileAnalysisCacheTable.documentId eq docId) }) {
                                            it[lastUsedAt] = OffsetDateTime.now()
                                        }
                                    }
                                }
                                return@withPermit img.copy(
                                    metadata = img.metadata.copy(
                                        calculatedHash = cachedHash,
                                        calculatedSizeBytes = cachedSize,
                                        calculatedSha = cached.sha
                                    )
                                )
                            }
                        }

                        // 3. Not in cache or changed -> download and analyze
                        val tmp = client.downloadFile(img)
                        val bytes = tmp.readBytes()
                        tmp.delete()
                        val fp = FileFingerprintUtil.compute(bytes, img.metadata.mime, img.metadata.ext)
                        val calculatedHash = fp.value
                        val calculatedSize = bytes.size.toLong()
                        val calculatedSha = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(bytes).joinToString("") { "%02x".format(it) }

                        // 4. Update cache
                        dbQuery {
                            val existing = FileAnalysisCacheTable.selectAll()
                                .where { (FileAnalysisCacheTable.instanceId eq instance.id) and (FileAnalysisCacheTable.documentId eq docId) }
                                .singleOrNull()

                            if (existing == null) {
                                FileAnalysisCacheTable.insert {
                                    it[instanceId] = instance.id
                                    it[documentId] = docId
                                    it[updatedAtStr] = currentUpdatedAt
                                    it[FileAnalysisCacheTable.calculatedHash] = calculatedHash
                                    it[calculatedSizeBytes] = calculatedSize
                                    it[FileAnalysisCacheTable.calculatedSha] = calculatedSha
                                }
                            } else {
                                FileAnalysisCacheTable.update({ (FileAnalysisCacheTable.instanceId eq instance.id) and (FileAnalysisCacheTable.documentId eq docId) }) {
                                    it[updatedAtStr] = currentUpdatedAt
                                    it[FileAnalysisCacheTable.calculatedHash] = calculatedHash
                                    it[calculatedSizeBytes] = calculatedSize
                                    it[FileAnalysisCacheTable.calculatedSha] = calculatedSha
                                    it[lastUsedAt] = OffsetDateTime.now()
                                }
                            }
                        }

                        val newMeta = img.metadata.copy(
                            calculatedHash = calculatedHash,
                            calculatedSizeBytes = calculatedSize,
                            calculatedSha = calculatedSha
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
