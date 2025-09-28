package it.sebi.service

import it.sebi.database.dbQuery
import it.sebi.models.*
import it.sebi.tables.MergeRequestDocumentMappingTable
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.slf4j.LoggerFactory


typealias CmpsMap = MutableMap<String, MutableMap<Int, MutableMap<String, MutableList<CmpRow>>>>
typealias TableMap = MutableMap<String, MutableMap<String, JsonObject>>

// Data produced by the Strapi/DB prefetch step, reusable for repeated comparisons without re-fetching
data class ComparisonPrefetch(
    val sourceFiles: List<StrapiImage>,
    val targetFiles: List<StrapiImage>,
    val sourceFileRelations: List<FilesRelatedMph>,
    val targetFileRelations: List<FilesRelatedMph>,
    val sourceCMPSMap: CmpsMap,
    val targetCMPSMap: CmpsMap,
    val sourceTableMap: TableMap,
    val targetTableMap: TableMap,
    val srcCompDef: MutableMap<String, MutableMap<Int, JsonObject>>,
    val tgtCompDef: MutableMap<String, MutableMap<Int, JsonObject>>,
    val sourceRowsByUid: Map<String, List<StrapiContent>>, // apiUid -> rows
    val targetRowsByUid: Map<String, List<StrapiContent>>, // apiUid -> rows
    val collectedRelationships: Set<ContentRelationship>
)

private fun keyOf(documentId: String, locale: String?): String {
    val loc = locale ?: ""
    return "$documentId|$loc"
}

private fun keyOfTarget(m: StrapiImageMetadata): String = keyOf(m.documentId, m.locale)

private fun compareFilesFromPrefetch(
    pre: ComparisonPrefetch,
    currentFileMapping: Map<String, MergeRequestDocumentMapping>
): List<ContentTypeFileComparisonResult> {
    val sourceWithFp = pre.sourceFiles
    val targetWithFp = pre.targetFiles

    val targetByKey: MutableMap<String, StrapiImage> =
        targetWithFp.associateBy { keyOfTarget(it.metadata) }.toMutableMap()
    val targetByFp: MutableMap<String, MutableList<StrapiImage>> = mutableMapOf()
    for (t in targetWithFp) {
        val fp = t.metadata.calculatedHash
        if (!fp.isNullOrBlank()) targetByFp.getOrPut(fp) { mutableListOf() }.add(t)
    }
    val usedTargetIds = mutableSetOf<Int>()

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

    val results = mutableListOf<ContentTypeFileComparisonResult>()
    for (s in sourceWithFp) {
        val sm = s.metadata
        val mappedDoc = currentFileMapping[sm.documentId]?.targetDocumentId ?: sm.documentId
        val k = keyOf(mappedDoc, sm.locale)
        var t: StrapiImage? = targetByKey[k]?.takeIf { usedTargetIds.add(it.metadata.id) }
        if (t == null) {
            val fp = sm.calculatedHash
            if (!fp.isNullOrBlank()) {
                val candidates = targetByFp[fp]
                if (!candidates.isNullOrEmpty()) {
                    val preferred = candidates.firstOrNull { it.metadata.locale == sm.locale && !usedTargetIds.contains(it.metadata.id) }
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
    return results
}

suspend fun prefetchComparisonData(
    mergeRequest: MergeRequestWithInstancesDTO,
    dbSchema: DbSchema
): ComparisonPrefetch = coroutineScope {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    logger.info("Prefetching Strapi data for MR ${mergeRequest.id} between '${mergeRequest.sourceInstance.name}' and '${mergeRequest.targetInstance.name}'")

    // Files and relations/components/tables/components definitions
    val srcFilesDef: Deferred<List<StrapiImage>> = async { fetchFilesFromDb(mergeRequest.sourceInstance) }
    val tgtFilesDef: Deferred<List<StrapiImage>> = async { fetchFilesFromDb(mergeRequest.targetInstance) }

    val srcRelDef: Deferred<List<FilesRelatedMph>> = async { fetchFilesRelatedMph(mergeRequest.sourceInstance) }
    val tgtRelDef: Deferred<List<FilesRelatedMph>> = async { fetchFilesRelatedMph(mergeRequest.targetInstance) }
    val srcCMPSDef: Deferred<CmpsMap> = async { fetchCMPS(mergeRequest.sourceInstance, dbSchema) }
    val tgtCMPSDef: Deferred<CmpsMap> = async { fetchCMPS(mergeRequest.targetInstance, dbSchema) }
    val scrTablesDef: Deferred<TableMap> = async { fetchTables(mergeRequest.sourceInstance, dbSchema) }
    val tgtTablesDef: Deferred<TableMap> = async { fetchTables(mergeRequest.targetInstance, dbSchema) }
    val srcCompDef: Deferred<MutableMap<String, MutableMap<Int, JsonObject>>> =
        async { fetchComponents(mergeRequest.sourceInstance, dbSchema) }
    val tgtCompDef: Deferred<MutableMap<String, MutableMap<Int, JsonObject>>> =
        async { fetchComponents(mergeRequest.targetInstance, dbSchema) }

    // compute fingerprints for files (still part of prefetch, since it interacts with Strapi)
    val srcFiles = srcFilesDef.await()
    val tgtFiles = tgtFilesDef.await()
    val srcFilesFpDef: Deferred<List<StrapiImage>> = async { computeFingerprints(mergeRequest.sourceInstance, srcFiles) }
    val tgtFilesFpDef: Deferred<List<StrapiImage>> = async { computeFingerprints(mergeRequest.targetInstance, tgtFiles) }

    val sourceFiles = srcFilesFpDef.await()
    val targetFiles = tgtFilesFpDef.await()
    val sourceFileRelations = srcRelDef.await()
    val targetFileRelations = tgtRelDef.await()
    val sourceCMPSMap = srcCMPSDef.await()
    val targetCMPSMap = tgtCMPSDef.await()
    val sourceTableMap = scrTablesDef.await()
    val targetTableMap = tgtTablesDef.await()
    val srcCompMap = srcCompDef.await()
    val tgtCompMap = tgtCompDef.await()

    // Collect relationships during source fetch to avoid duplicate traversals
    val relationshipsCollected = mutableSetOf<ContentRelationship>()

    // Build source/target rows by uid using prefetched caches
    val sourceRowsByUid = mutableMapOf<String, List<StrapiContent>>()
    val targetRowsByUid = mutableMapOf<String, List<StrapiContent>>()

    for (table in dbSchema.tables) {
        val meta = table.metadata ?: continue
        val uid = meta.apiUid
        if (!uid.startsWith("api::")) continue

        val sourceRowsDef = async(Dispatchers.IO) {
            fetchPublishedRowsAsJson(
                mergeRequest.sourceInstance,
                table.name,
                dbSchema,
                relationshipsCollector = relationshipsCollected,
                currentSourceUid = uid,
                fileRelations = sourceFileRelations,
                cmpsMap = sourceCMPSMap,
                componentTableCache = srcCompMap,
                tableMap = sourceTableMap,
                fileCache = sourceFiles.associate { it.metadata.id to it.metadata.documentId }
            ).map { (obj, links) -> toStrapiContent(obj, links, table.metadata) }
        }

        val targetRowsDef = async(Dispatchers.IO) {
            fetchPublishedRowsAsJson(
                mergeRequest.targetInstance,
                table.name,
                dbSchema,
                fileRelations = targetFileRelations,
                cmpsMap = targetCMPSMap,
                componentTableCache = tgtCompMap,
                tableMap = targetTableMap,
                fileCache = targetFiles.associate { it.metadata.id to it.metadata.documentId }
            ).map { (obj, links) -> toStrapiContent(obj, links, table.metadata) }
        }

        val (sourceRows, targetRows) = awaitAll(sourceRowsDef, targetRowsDef)
        sourceRowsByUid[uid] = sourceRows
        targetRowsByUid[uid] = targetRows
    }

    ComparisonPrefetch(
        sourceFiles = sourceFiles,
        targetFiles = targetFiles,
        sourceFileRelations = sourceFileRelations,
        targetFileRelations = targetFileRelations,
        sourceCMPSMap = sourceCMPSMap,
        targetCMPSMap = targetCMPSMap,
        sourceTableMap = sourceTableMap,
        targetTableMap = targetTableMap,
        srcCompDef = srcCompMap,
        tgtCompDef = tgtCompMap,
        sourceRowsByUid = sourceRowsByUid,
        targetRowsByUid = targetRowsByUid,
        collectedRelationships = relationshipsCollected
    )
}

suspend fun computeComparisonFromPrefetch(
    mergeRequest: MergeRequestWithInstancesDTO,
    dbSchema: DbSchema,
    prefetch: ComparisonPrefetch
): ContentTypeComparisonResultMapWithRelationships {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    logger.info("Computing comparison from prefetched data for MR ${mergeRequest.id}")

    // Load existing id mappings (from our DB). This is cheap and not Strapi-related.
    var idMappingsList: List<MergeRequestDocumentMapping>  = MergeRequestDocumentMappingTable.fetchMappingList(mergeRequest)

    // Sanitize mappings: ensure both source/target documentId and id exist in current prefetch data.
    val idMappings =  run {
        // Build sets of valid ids/documentIds for source and target (content + files)
        val sourceData = mutableSetOf<Pair<String,Int>>()
        val targetData = mutableSetOf<Pair<String,Int>>()
      

        // Content rows
        prefetch.sourceRowsByUid.values.flatten().forEach { c ->
            c.metadata.id?.let { sourceData.add(c.metadata.documentId to it) }
        }
        prefetch.targetRowsByUid.values.flatten().forEach { c ->
            c.metadata.id?.let {
                targetData.add(c.metadata.documentId to it) }
          
        }
        // Files
        prefetch.sourceFiles.forEach { f ->
            sourceData.add(f.metadata.documentId to f.metadata.id)
        }
        prefetch.targetFiles.forEach { f ->
          targetData.add(f.metadata.documentId to f.metadata.id)
        }

        // Collect invalid mapping ids
        val invalidIds = mutableListOf<Int>()



        idMappingsList = idMappingsList.mapNotNull { map ->
                val validSource = map.sourceDocumentId != null && map.sourceId != null &&   sourceData.contains(map.sourceDocumentId to map.sourceId)
                val validTarget = map.targetDocumentId != null && map.targetId != null && targetData.contains(map.targetDocumentId to map.targetId)
                if (validSource && validTarget) {
                  map
                } else {
                    invalidIds.add(map.id)
                    null
                }
            }

        if (invalidIds.isNotEmpty()) {
            logger.info("Found ${invalidIds.size} invalid mappings for MR ${mergeRequest.id}; deleting from DB…")
            // Physically delete rows one-by-one to avoid dialect issues with id column
            dbQuery {
                invalidIds.forEach { mid ->
                    MergeRequestDocumentMappingTable.deleteWhere { MergeRequestDocumentMappingTable.id eq mid }
                }
            }
        }

        // Rebuild idMappings map to reflect deletions
        idMappingsList.groupBy { it.contentType }
            .mapValues { (_, v) -> v.associateBy { it.sourceDocumentId!! } }
    }

    val fileMapping: Map<String, MergeRequestDocumentMapping> = idMappings[STRAPI_FILE_CONTENT_TYPE_NAME] ?: mapOf()

    val filesResult: List<ContentTypeFileComparisonResult> = compareFilesFromPrefetch(prefetch, fileMapping)

    val contentMapping: MutableMap<String, MergeRequestDocumentMapping> =
        idMappings.filterKeys { it != STRAPI_FILE_CONTENT_TYPE_NAME }.values.fold(mutableMapOf<String, MergeRequestDocumentMapping>()) { acc, v ->
            acc.putAll(v); acc
        }

    // Build maps for single and collection types
    val singleTypes = mutableMapOf<String, ContentTypeComparisonResultWithRelationships>()
    val collectionTypes = mutableMapOf<String, List<ContentTypeComparisonResultWithRelationships>>()

    for (table in dbSchema.tables) {
        val meta = table.metadata ?: continue
        val uid = meta.apiUid
        if (!uid.startsWith("api::")) continue
        val kind = meta.collectionType

        val sourceRows = prefetch.sourceRowsByUid[uid] ?: emptyList()
        val targetRows = prefetch.targetRowsByUid[uid] ?: emptyList()

        when (kind) {
            StrapiContentTypeKind.SingleType -> {
                val source = sourceRows.maxByOrNull { it.metadata.id ?: 0 }
                val target = targetRows.maxByOrNull { it.metadata.id ?: 0 }
                singleTypes[table.name] = compareSingleType(table.name, uid, source, target, kind)
            }
            StrapiContentTypeKind.CollectionType -> {
                collectionTypes[table.name] =
                    compareCollectionType(table.name, uid, sourceRows, targetRows, kind, contentMapping)
            }
            StrapiContentTypeKind.Files, StrapiContentTypeKind.Component -> {
                // ignore
            }
        }
    }

    // Post-process collected relationships: mark bidirectional and deduplicate
    val collectedList = prefetch.collectedRelationships.toList()
    val bidirProcessed = collectedList.map { r ->
        val reverse =
            collectedList.find { it.sourceContentType == r.targetContentType && it.targetContentType == r.sourceContentType }
        if (reverse != null) {
            r.copy(isBidirectional = true, targetField = reverse.sourceField)
        } else r
    }
    val contentTypeRelationships =
        bidirProcessed.distinctBy { "${it.sourceContentType}|${it.sourceField}|${it.targetContentType}|${it.targetField}|${it.relationType}" }

    return ContentTypeComparisonResultMapWithRelationships(
        files = filesResult,
        singleTypes = singleTypes,
        collectionTypes = collectionTypes,
        contentTypeRelationships = contentTypeRelationships
    )
}

suspend fun compareContentWithRelationshipsDb(
    mergeRequest: MergeRequestWithInstancesDTO,
    dbSchema: DbSchema
): ContentTypeComparisonResultMapWithRelationships {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    logger.info("Starting DB content comparison (2-step) for MR ${mergeRequest.id} between '${mergeRequest.sourceInstance.name}' and '${mergeRequest.targetInstance.name}'")

    // New flow: prefetch all Strapi/DB data once, then compute purely in-memory/DB
    val prefetch = prefetchComparisonData(mergeRequest, dbSchema)
    return computeComparisonFromPrefetch(mergeRequest, dbSchema, prefetch)
}
