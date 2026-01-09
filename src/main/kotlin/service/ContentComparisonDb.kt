package it.sebi.service

import it.sebi.database.dbQuery
import it.sebi.models.*
import it.sebi.tables.MergeRequestDocumentMappingTable
import it.sebi.tables.MergeRequestExclusionTable
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.slf4j.LoggerFactory
import it.sebi.client.StrapiClient


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
    val collectedRelationships: Set<ContentRelationship>,
    val exclusions: List<MergeRequestExclusion> = emptyList()
)

private fun keyOf(documentId: String, locale: String?): String {
    val loc = locale ?: ""
    return "$documentId|$loc"
}

private fun keyOfTarget(m: StrapiImageMetadata): String = keyOf(m.documentId, m.locale)

private suspend fun compareFilesFromPrefetch(
    mergeRequest: MergeRequestWithInstancesDTO,
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
        if (s != null) {
            val isExcluded = pre.exclusions.any { it.contentType == STRAPI_FILE_CONTENT_TYPE_NAME && it.documentId == s.metadata.documentId }
            if (isExcluded) return ContentTypeComparisonResultKind.EXCLUDED
        }
        if (t != null) {
            val isExcluded = pre.exclusions.any { it.contentType == STRAPI_FILE_CONTENT_TYPE_NAME && it.documentId == t.metadata.documentId }
            if (isExcluded) return ContentTypeComparisonResultKind.EXCLUDED
        }

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
                        val dist = it.sebi.utils.FileFingerprintUtil.hammingDistance(sourceFp, targetFp)
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
                    // Robust check: if fingerprints differ but they are mapped, or if we want to check other metadata
                    val sourceSize = sm.calculatedSizeBytes ?: sm.size.toLong()
                    val targetSize = tm.calculatedSizeBytes ?: tm.size.toLong()
                    
                    // If fingerprints are both present and they are still considered different after Hamming check
                    if (!sourceFp.isNullOrBlank() && !targetFp.isNullOrBlank()) {
                        ContentTypeComparisonResultKind.DIFFERENT
                    } else {
                        // Fallback to metadata if fingerprints are missing
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
                    // Safety check before pairing: fingerprints match, but do metadata and size also match within reason?
                    // This prevents auto-pairing very different images that happen to have same dHash (like black logos).
                    val sourceSize = sm.calculatedSizeBytes ?: sm.size.toLong()
                    
                    val filteredCandidates = candidates.filter { cand ->
                        if (usedTargetIds.contains(cand.metadata.id)) return@filter false
                        
                        val targetSize = cand.metadata.calculatedSizeBytes ?: cand.metadata.size.toLong()
                        val sizeDiff = Math.abs(sourceSize - targetSize).toDouble()
                        val maxSize = Math.max(sourceSize, targetSize).toDouble()
                        val sizeToleranceOk = if (maxSize > 0) (sizeDiff / maxSize) < 0.25 else true
                        
                        val nameSimilarityOk = sm.name.equals(cand.metadata.name, ignoreCase = true) || 
                                              sm.name.contains(cand.metadata.name, ignoreCase = true) || 
                                              cand.metadata.name.contains(sm.name, ignoreCase = true)
                        
                        // Accept if either size is similar OR name is similar. 
                        // If both are very different, it's likely a collision.
                        sizeToleranceOk || nameSimilarityOk
                    }

                    val preferred = filteredCandidates.firstOrNull { it.metadata.locale == sm.locale }
                    val anyOther = preferred ?: filteredCandidates.firstOrNull()
                    
                    if (anyOther != null) {
                        t = anyOther
                        usedTargetIds.add(anyOther.metadata.id)
                        dbQuery {
                            MergeRequestDocumentMappingTable.insert {
                                it[MergeRequestDocumentMappingTable.sourceStrapiId] = mergeRequest.sourceInstance.id
                                it[MergeRequestDocumentMappingTable.targetStrapiId] = mergeRequest.targetInstance.id
                                it[MergeRequestDocumentMappingTable.contentType] = STRAPI_FILE_CONTENT_TYPE_NAME
                                it[MergeRequestDocumentMappingTable.sourceId] = s.metadata.id
                                it[MergeRequestDocumentMappingTable.sourceDocumentId] = s.metadata.documentId
                                it[MergeRequestDocumentMappingTable.targetId] = t.metadata.id
                                it[MergeRequestDocumentMappingTable.targetDocumentId] = t.metadata.documentId
                                it[MergeRequestDocumentMappingTable.locale] = t.metadata.locale
                            }
                        }
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

    val exclusionsDef = async { MergeRequestExclusionTable.fetchExclusions(mergeRequest) }

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
    val sourceExclusions = exclusionsDef.await()

    // Collect relationships during source fetch to avoid duplicate traversals
    val relationshipsCollected = mutableSetOf<ContentRelationship>()

    // Build source/target rows by uid using prefetched caches
    val sourceRowsByUid = mutableMapOf<String, List<StrapiContent>>()
    val targetRowsByUid = mutableMapOf<String, List<StrapiContent>>()

    for (table in dbSchema.tables) {
        val meta = table.metadata ?: continue
        val uid = meta.apiUid
        if (!uid.startsWith("api::")) continue

        // Prepare excluded fields for this UID
        val excludedFields = sourceExclusions
            .filter { it.contentType == uid && it.documentId == null && it.fieldPath != null }
            .mapNotNull { it.fieldPath }
            .toSet()

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
            ).map { (obj, links) -> toStrapiContent(obj, links, table.metadata, excludedFields) }
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
            ).map { (obj, links) -> toStrapiContent(obj, links, table.metadata, excludedFields) }
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
        collectedRelationships = relationshipsCollected,
        exclusions = sourceExclusions
    )
}

// Export source-only prefetch cache (for async/offline mode)
@Suppress("RedundantSuspendModifier")
suspend fun exportSourcePrefetch(
    instance: StrapiInstance,
    dbSchema: DbSchema
): ComparisonPrefetchCache = coroutineScope {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    logger.info("Exporting source-only prefetch for instance '${instance.name}'")

    // Files and relations/components/tables/components definitions for source only
    val filesDef: Deferred<List<StrapiImage>> = async { fetchFilesFromDb(instance) }
    val relDef: Deferred<List<FilesRelatedMph>> = async { fetchFilesRelatedMph(instance) }
    val cmpsDef: Deferred<CmpsMap> = async { fetchCMPS(instance, dbSchema) }
    val tablesDef: Deferred<TableMap> = async { fetchTables(instance, dbSchema) }
    val compDef: Deferred<MutableMap<String, MutableMap<Int, JsonObject>>> = async { fetchComponents(instance, dbSchema) }
    // New: folders snapshot from source Strapi (via HTTP API)
    val foldersDef: Deferred<List<StrapiFolder>> = async {
        try {
            val client = StrapiClient(instance)
            client.getFolders()
        } catch (e: Exception) {
            logger.warn("Could not fetch folders from source '${instance.name}': ${'$'}{e.message}. Proceeding with empty list.")
            emptyList()
        }
    }

    val files = filesDef.await()
    val filesWithFp = computeFingerprints(instance, files)
    val fileRelations = relDef.await()
    val cmpsMap = cmpsDef.await()
    val tableMap = tablesDef.await()
    val compMap = compDef.await()
    val sourceFolders = foldersDef.await()

    val relationshipsCollected = mutableSetOf<ContentRelationship>()
    val sourceRowsByUid = mutableMapOf<String, List<StrapiContent>>()

    for (table in dbSchema.tables) {
        val meta = table.metadata ?: continue
        val uid = meta.apiUid
        if (!uid.startsWith("api::")) continue
        val rows = fetchPublishedRowsAsJson(
            instance,
            table.name,
            dbSchema,
            relationshipsCollector = relationshipsCollected,
            currentSourceUid = uid,
            fileRelations = fileRelations,
            cmpsMap = cmpsMap,
            componentTableCache = compMap,
            tableMap = tableMap,
            fileCache = filesWithFp.associate { it.metadata.id to it.metadata.documentId }
        ).map { (obj, links) -> toStrapiContent(obj, links, table.metadata) }
        sourceRowsByUid[uid] = rows
    }

    ComparisonPrefetchCache(
        sourceFiles = filesWithFp,
        targetFiles = emptyList(),
        sourceFolders = sourceFolders,
        targetFolders = emptyList(),
        sourceRowsByUid = sourceRowsByUid,
        targetRowsByUid = emptyMap(),
        collectedRelationships = relationshipsCollected.toList()
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



        idMappingsList = idMappingsList.map { map ->
                val validSource = map.sourceDocumentId != null && map.sourceId != null &&   sourceData.contains(map.sourceDocumentId to map.sourceId)
                val validTarget = map.targetDocumentId != null && map.targetId != null && targetData.contains(map.targetDocumentId to map.targetId)
                if (validSource && validTarget) {
                  map
                } else {
                    // Se non è valido, non lo cancelliamo fisicamente dal DB ora,
                    // perché potrebbe essere un'entità non ancora sincronizzata in questa sessione
                    // o filtrata per altri motivi (es. non pubblicata).
                    // Invece lo teniamo ma lo loggeremo se vogliamo fare cleanup.
                    // Per ora, lo carichiamo comunque per permettere l'associazione se ricompare.
                    map
                }
            }

        // idMappingsList already contains all mappings. 
        // No physical deletion for now to be safe.

        // Rebuild idMappings map to reflect deletions
        idMappingsList.groupBy { it.contentType }
            .mapValues { (_, v) -> v.associateBy { it.sourceDocumentId!! } }
    }

    val fileMapping: Map<String, MergeRequestDocumentMapping> = idMappings[STRAPI_FILE_CONTENT_TYPE_NAME] ?: mapOf()

    val filesResult: List<ContentTypeFileComparisonResult> = compareFilesFromPrefetch(mergeRequest,prefetch, fileMapping)

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
                singleTypes[table.name] = compareSingleType(table.name, uid, source, target, kind, fileMapping, contentMapping, prefetch.exclusions)
            }
            StrapiContentTypeKind.CollectionType -> {
                collectionTypes[table.name] =
                    compareCollectionType(table.name, uid, sourceRows, targetRows, kind, contentMapping, fileMapping, prefetch.exclusions)
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
