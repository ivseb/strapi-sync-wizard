package it.sebi.service

import it.sebi.database.dbQuery
import it.sebi.models.*
import it.sebi.service.identity.SyncIdentityService
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

internal fun compareFilesFromPrefetch(
    pre: ComparisonPrefetch,
    currentFileMapping: Map<String, MergeRequestDocumentMapping>
): List<ContentTypeFileComparisonResult> {
    val sourceWithFp = pre.sourceFiles
    val targetWithFp = pre.targetFiles

    // Deterministic, conservative pairing (see compareFilesFromPrefetch tiers below).
    val targetByDocKey: Map<String, StrapiImage> = targetWithFp.associateBy { keyOfTarget(it.metadata) }
    val usedTargetIds = mutableSetOf<Int>()
    val pairedSourceIds = mutableSetOf<Int>()

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
    fun pairUp(s: StrapiImage, t: StrapiImage) {
        results.add(ContentTypeFileComparisonResult(sourceImage = s, targetImage = t, compareState = compareStateOf(s, t)))
        usedTargetIds.add(t.metadata.id)
        pairedSourceIds.add(s.metadata.id)
    }
    fun remainingSources(): List<StrapiImage> = sourceWithFp.filter { it.metadata.id !in pairedSourceIds }
    fun remainingTargets(): List<StrapiImage> = targetWithFp.filter { it.metadata.id !in usedTargetIds }
    // Folder NAME path (e.g. "/illustrations") is stable across instances; folderPath (numeric
    // folder ids like "/4") is environment-specific, so never key on it.
    fun nameFolderKey(m: StrapiImageMetadata): String =
        "${m.name.trim().lowercase()}|${m.ext.lowercase()}|${(m.folder ?: "/").lowercase()}|${m.locale ?: ""}"
    fun nameKey(m: StrapiImageMetadata): String =
        "${m.name.trim().lowercase()}|${m.ext.lowercase()}|${m.locale ?: ""}"

    // Conservative, deterministic tiers (no greedy dHash — it mis-pairs duplicates and steals
    // true twins). Ambiguous groups (duplicate names / colliding bytes) are left unpaired for
    // human review rather than auto-matched. Durable identity is the shared syncId (Tier 1),
    // written by reconciliation; dHash is only used inside compareStateOf to classify a pair.

    // Tier 0 — explicit, human-confirmed mappings by documentId.
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
    // Tier 2 — exact same bytes (sha256). Copies of identical bytes are interchangeable, so a group
    // is paired even when it isn't 1:1 (the target often holds the same asset uploaded twice):
    // positionally, same-named copies first. Leaving such groups unpaired made the asset look new
    // (re-uploaded) and every referrer DIFFERENT. Surplus copies are attached below (duplicates).
    run {
        val srcBySha = remainingSources().filter { !it.metadata.calculatedSha.isNullOrBlank() }.groupBy { it.metadata.calculatedSha!! }
        val tgtBySha = remainingTargets().filter { !it.metadata.calculatedSha.isNullOrBlank() }.groupBy { it.metadata.calculatedSha!! }
        val preference = compareBy<StrapiImage>({ it.metadata.name.lowercase() }, { it.metadata.syncId.isNullOrBlank() }, { it.metadata.id })
        for ((sha, sList) in srcBySha) {
            val tList = tgtBySha[sha] ?: continue
            sList.sortedWith(preference).zip(tList.sortedWith(preference)).forEach { (s, t) -> pairUp(s, t) }
        }
    }
    // Tier 3 — same name+ext+folder NAME(+locale), only when unique 1:1 (re-encoded asset, same folder).
    run {
        val srcByName = remainingSources().groupBy { nameFolderKey(it.metadata) }
        val tgtByName = remainingTargets().groupBy { nameFolderKey(it.metadata) }
        for ((k, sList) in srcByName) {
            val tList = tgtByName[k] ?: continue
            if (sList.size == 1 && tList.size == 1) pairUp(sList.first(), tList.first())
        }
    }
    // Tier 4 — same name+ext(+locale) ignoring folder, only when unique 1:1 (same asset moved to a
    // different folder; folder ids differ across instances). Still safe: requires global 1:1.
    run {
        val srcByName = remainingSources().groupBy { nameKey(it.metadata) }
        val tgtByName = remainingTargets().groupBy { nameKey(it.metadata) }
        for ((k, sList) in srcByName) {
            val tList = tgtByName[k] ?: continue
            if (sList.size == 1 && tList.size == 1) pairUp(sList.first(), tList.first())
        }
    }
    // Same picture: identical fingerprint, or a dHash within the tolerance compareStateOf uses. Only
    // ever applied inside a same-name group (a bare dHash match across all files mis-pairs simple
    // images such as logos on white).
    fun nearDistance(a: StrapiImage, b: StrapiImage): Int? {
        val x = a.metadata.calculatedHash?.takeIf { it.isNotBlank() } ?: return null
        val y = b.metadata.calculatedHash?.takeIf { it.isNotBlank() } ?: return null
        if (x == y) return 0
        return it.sebi.utils.FileFingerprintUtil.hammingDistance(x, y).takeIf { it <= 12 }
    }
    // Tier 4b — same name+ext(+locale) groups that are NOT 1:1: the same asset uploaded more than
    // once and re-encoded, so the bytes differ. Each source takes its visually nearest target copy,
    // closest pairs first; the other copies are attached below as duplicates.
    run {
        val tgtByName = remainingTargets().groupBy { nameKey(it.metadata) }
        val candidates = remainingSources().flatMap { s ->
            tgtByName[nameKey(s.metadata)].orEmpty().mapNotNull { t -> nearDistance(s, t)?.let { Triple(s, t, it) } }
        }
        for ((s, t, _) in candidates.sortedWith(compareBy({ it.third }, { it.first.metadata.id }, { it.second.metadata.id }))) {
            if (s.metadata.id in pairedSourceIds || t.metadata.id in usedTargetIds) continue
            pairUp(s, t)
        }
    }
    // Duplicates — a leftover that is a COPY of an asset already accounted for is not a new or a
    // removed asset: either its exact bytes exist on the other side, or it is a copy (same bytes, or
    // same name and same picture) of a file of its own side that is already paired. Report it as an
    // IDENTICAL pair with that counterpart (after the real pairs, so link resolution keeps those).
    // Otherwise the sync would upload a duplicate or offer to delete a copy the target still uses.
    run {
        val pairs = results.filter { it.sourceImage != null && it.targetImage != null }
        val pairedBySrcSha = pairs.groupBy { it.sourceImage!!.metadata.calculatedSha }
        val pairedByTgtSha = pairs.groupBy { it.targetImage!!.metadata.calculatedSha }
        val srcBySha = sourceWithFp.filter { !it.metadata.calculatedSha.isNullOrBlank() }.groupBy { it.metadata.calculatedSha!! }
        val tgtBySha = targetWithFp.filter { !it.metadata.calculatedSha.isNullOrBlank() }.groupBy { it.metadata.calculatedSha!! }
        // The paired file of [side] that [f] is a same-side copy of, with its pair.
        fun sameSideCopy(f: StrapiImage, side: (ContentTypeFileComparisonResult) -> StrapiImage?): ContentTypeFileComparisonResult? {
            val sha = f.metadata.calculatedSha?.takeIf { it.isNotBlank() }
            pairs.firstOrNull { sha != null && side(it)!!.metadata.calculatedSha == sha }?.let { return it }
            return pairs.filter { nameKey(side(it)!!.metadata) == nameKey(f.metadata) }
                .mapNotNull { p -> nearDistance(f, side(p)!!)?.let { p to it } }
                .minByOrNull { it.second }?.first
        }
        for (s in remainingSources()) {
            val sha = s.metadata.calculatedSha?.takeIf { it.isNotBlank() }
            val t = sha?.let { pairedByTgtSha[it]?.firstOrNull()?.targetImage ?: tgtBySha[it]?.firstOrNull() }
                ?: sameSideCopy(s) { it.sourceImage }?.targetImage
                ?: continue
            // a copy of what the other side already has: never a change to sync
            results.add(ContentTypeFileComparisonResult(sourceImage = s, targetImage = t, compareState = ContentTypeComparisonResultKind.IDENTICAL))
            pairedSourceIds.add(s.metadata.id)
        }
        for (t in remainingTargets()) {
            val sha = t.metadata.calculatedSha?.takeIf { it.isNotBlank() }
            val s = sha?.let { pairedBySrcSha[it]?.firstOrNull()?.sourceImage ?: srcBySha[it]?.firstOrNull() }
                ?: sameSideCopy(t) { it.targetImage }?.sourceImage
                ?: continue
            results.add(ContentTypeFileComparisonResult(sourceImage = s, targetImage = t, compareState = ContentTypeComparisonResultKind.IDENTICAL))
            usedTargetIds.add(t.metadata.id)
        }
    }
    // Tier 5 — leftovers: genuinely one-sided OR ambiguous (left for review). compareStateOf keeps
    // exclusion awareness.
    for (s in remainingSources())
        results.add(ContentTypeFileComparisonResult(sourceImage = s, targetImage = null, compareState = compareStateOf(s, null)))
    for (t in remainingTargets())
        results.add(ContentTypeFileComparisonResult(sourceImage = null, targetImage = t, compareState = compareStateOf(null, t)))
    return results
}

suspend fun prefetchComparisonData(
    mergeRequest: MergeRequestWithInstancesDTO,
    dbSchema: DbSchema
): ComparisonPrefetch = coroutineScope {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    logger.info("Prefetching Strapi data for MR ${mergeRequest.id} between '${mergeRequest.sourceInstance.name}' and '${mergeRequest.targetInstance.name}'")

    // Identity layer (Phase 1): ensure the sync_identity sidecar exists on both instances before
    // any read JOINs it. Idempotent and cheap. Virtual/unconfigured instances are skipped inside.
    if (!mergeRequest.sourceInstance.isVirtual) SyncIdentityService.ensureTable(mergeRequest.sourceInstance)
    if (!mergeRequest.targetInstance.isVirtual) SyncIdentityService.ensureTable(mergeRequest.targetInstance)

    // Files and relations/components/tables/components definitions
    val srcFilesDef: Deferred<List<StrapiImage>> = async { fetchFilesFromDb(mergeRequest.sourceInstance) }
    val tgtFilesDef: Deferred<List<StrapiImage>> = async { fetchFilesFromDb(mergeRequest.targetInstance) }

    val srcRelDef: Deferred<List<FilesRelatedMph>> = async { fetchFilesRelatedMph(mergeRequest.sourceInstance) }
    val tgtRelDef: Deferred<List<FilesRelatedMph>> = async { fetchFilesRelatedMph(mergeRequest.targetInstance) }
    val srcCMPSDef: Deferred<CmpsMap> = async { fetchCMPS(mergeRequest.sourceInstance, dbSchema) }
    val tgtCMPSDef: Deferred<CmpsMap> = async { fetchCMPS(mergeRequest.targetInstance, dbSchema) }
    val scrTablesDef: Deferred<TableMap> = async { fetchTables(mergeRequest.sourceInstance, dbSchema, mergeRequest.includeDrafts) }
    val tgtTablesDef: Deferred<TableMap> = async { fetchTables(mergeRequest.targetInstance, dbSchema, mergeRequest.includeDrafts) }
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
                fileCache = sourceFiles.associate { it.metadata.id to it.metadata.documentId },
                includeDrafts = mergeRequest.includeDrafts
            ).let { rowsToContent(it, table.metadata, excludedFields, mergeRequest.includeDrafts) }
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
                fileCache = targetFiles.associate { it.metadata.id to it.metadata.documentId },
                includeDrafts = mergeRequest.includeDrafts
            ).let { rowsToContent(it, table.metadata, excludedFields, mergeRequest.includeDrafts) }
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
    dbSchema: DbSchema,
    includeDrafts: Boolean = false
): ComparisonPrefetchCache = coroutineScope {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    logger.info("Exporting source-only prefetch for instance '${instance.name}'")

    // Identity layer (Phase 1): ensure the sync_identity sidecar exists before reads JOIN it,
    // so the exported rows carry their shared sync_id across the air-gap.
    if (!instance.isVirtual) SyncIdentityService.ensureTable(instance)

    // Files and relations/components/tables/components definitions for source only
    val filesDef: Deferred<List<StrapiImage>> = async { fetchFilesFromDb(instance) }
    val relDef: Deferred<List<FilesRelatedMph>> = async { fetchFilesRelatedMph(instance) }
    val cmpsDef: Deferred<CmpsMap> = async { fetchCMPS(instance, dbSchema) }
    val tablesDef: Deferred<TableMap> = async { fetchTables(instance, dbSchema, includeDrafts) }
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
            fileCache = filesWithFp.associate { it.metadata.id to it.metadata.documentId },
            includeDrafts = includeDrafts
        ).let { rowsToContent(it, table.metadata, includeDrafts = includeDrafts) }
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

    // Mapping hygiene: a mapping whose source or target documentId no longer exists on its instance
    // is stale — it can never pair anything correctly again, it can only poison pairing (false
    // ONLY_IN_SOURCE/ONLY_IN_TARGET) and the apply flow (POST /upload?id=<deleted> -> 404). Stale
    // rows are deleted automatically here, on every compare.
    //
    // The existence check reads the full document_id sets straight from each instance DB (so
    // draft-only entries filtered out of the prefetch don't count as missing). Virtual instances
    // have no DB: their prefetch/export IS the complete truth. If a direct read fails the side is
    // treated as unverifiable and nothing is deleted for it.
    val idMappings = run {
        val uidToTable = dbSchema.tables.mapNotNull { t -> t.metadata?.apiUid?.let { it to t.name } }.toMap()
        fun tableFor(uid: String): String? =
            if (uid == STRAPI_FILE_CONTENT_TYPE_NAME) "files" else uidToTable[uid]

        val neededTables = idMappingsList.mapNotNull { tableFor(it.contentType) }.toSet()

        suspend fun fetchDocIdsByTable(instance: StrapiInstance): Map<String, Set<String>>? {
            if (instance.isVirtual || neededTables.isEmpty()) return null
            return try {
                dbQuery(instance.database) {
                    neededTables.associateWith { table ->
                        val docs = mutableSetOf<String>()
                        exec("SELECT document_id FROM \"$table\"") { rs ->
                            while (rs.next()) rs.getString("document_id")?.let { docs.add(it) }
                        }
                        docs as Set<String>
                    }
                }
            } catch (e: Exception) {
                logger.warn("Mapping hygiene: cannot read document ids from '${instance.name}' (${e.message}); skipping stale-mapping cleanup for this side")
                null
            }
        }

        fun prefetchDocIds(rowsByUid: Map<String, List<StrapiContent>>, files: List<StrapiImage>): Map<String, Set<String>> {
            val byTable = mutableMapOf<String, MutableSet<String>>()
            rowsByUid.forEach { (uid, rows) ->
                val table = uidToTable[uid] ?: return@forEach
                byTable.getOrPut(table) { mutableSetOf() }.addAll(rows.map { it.metadata.documentId })
            }
            byTable.getOrPut("files") { mutableSetOf() }.addAll(files.map { it.metadata.documentId })
            return byTable
        }

        val srcDocs = fetchDocIdsByTable(mergeRequest.sourceInstance)
            ?: if (mergeRequest.sourceInstance.isVirtual) prefetchDocIds(prefetch.sourceRowsByUid, prefetch.sourceFiles) else null
        val tgtDocs = fetchDocIdsByTable(mergeRequest.targetInstance)
            ?: if (mergeRequest.targetInstance.isVirtual) prefetchDocIds(prefetch.targetRowsByUid, prefetch.targetFiles) else null

        val stale = mutableListOf<MergeRequestDocumentMapping>()
        val kept = mutableListOf<MergeRequestDocumentMapping>()
        for (map in idMappingsList) {
            val table = tableFor(map.contentType)
            if (table == null) { kept.add(map); continue } // unknown content type: don't judge
            val srcOk = map.sourceDocumentId == null || srcDocs == null ||
                    (srcDocs[table]?.contains(map.sourceDocumentId) ?: true)
            val tgtOk = map.targetDocumentId == null || tgtDocs == null ||
                    (tgtDocs[table]?.contains(map.targetDocumentId) ?: true)
            if (srcOk && tgtOk) kept.add(map) else stale.add(map)
        }
        if (stale.isNotEmpty()) {
            val staleIds = stale.map { it.id }
            dbQuery {
                MergeRequestDocumentMappingTable.deleteWhere { MergeRequestDocumentMappingTable.id inList staleIds }
            }
            logger.info(
                "Mapping hygiene MR ${mergeRequest.id}: removed ${stale.size} stale mapping(s) whose endpoints no longer exist: " +
                        stale.take(20).joinToString { "${it.contentType}[${it.sourceDocumentId}->${it.targetDocumentId}]" } +
                        (if (stale.size > 20) " …" else "")
            )
            idMappingsList = kept
        }

        idMappingsList.groupBy { it.contentType }
            .mapValues { (_, v) -> v.associateBy { it.sourceDocumentId!! } }
    }

    val fileMapping: Map<String, MergeRequestDocumentMapping> = idMappings[STRAPI_FILE_CONTENT_TYPE_NAME] ?: mapOf()

    val filesResult: List<ContentTypeFileComparisonResult> = compareFilesFromPrefetch(prefetch, fileMapping)

    val contentMapping: MutableMap<String, MergeRequestDocumentMapping> =
        idMappings.filterKeys { it != STRAPI_FILE_CONTENT_TYPE_NAME }.values.fold(mutableMapOf<String, MergeRequestDocumentMapping>()) { acc, v ->
            acc.putAll(v); acc
        }

    // documentId -> sync_id per instance (raw identity from the sidecar).
    val srcSyncByDoc = fetchSyncIdMap(mergeRequest.sourceInstance)
    val tgtSyncByDoc = fetchSyncIdMap(mergeRequest.targetInstance)

    // Cross-instance pair overlay: both members of an established pair share one identity token, so
    // a reference to "the same logical entity" normalizes identically on both sides even before any
    // reconcile has written durable syncIds. Seeded with the file pairing computed above; extended
    // with the content pairing after the first compare pass.
    val srcPairOverlay = mutableMapOf<String, String>()
    val tgtPairOverlay = mutableMapOf<String, String>()
    // Pairs come first in filesResult, duplicate copies after: a copy inherits its counterpart's
    // token, so references to any copy of the same asset normalize identically.
    filesResult.forEach { f ->
        val s = f.sourceImage?.metadata?.documentId ?: return@forEach
        val t = f.targetImage?.metadata?.documentId ?: return@forEach
        val token = tgtPairOverlay[t] ?: srcPairOverlay[s] ?: "pair:f:$s"
        srcPairOverlay.putIfAbsent(s, token)
        tgtPairOverlay.putIfAbsent(t, token)
    }

    // One compare pass over all content types with the given identity maps.
    fun runPass(
        srcIdentity: Map<String, String>,
        tgtIdentity: Map<String, String>,
        mapping: MutableMap<String, MergeRequestDocumentMapping>
    ): Pair<MutableMap<String, ContentTypeComparisonResultWithRelationships>, MutableMap<String, List<ContentTypeComparisonResultWithRelationships>>> {
        val srcResolver = buildRefResolver(prefetch.sourceRowsByUid, prefetch.sourceFiles, srcIdentity)
        val tgtResolver = buildRefResolver(prefetch.targetRowsByUid, prefetch.targetFiles, tgtIdentity)
        val singles = mutableMapOf<String, ContentTypeComparisonResultWithRelationships>()
        val collections = mutableMapOf<String, List<ContentTypeComparisonResultWithRelationships>>()
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
                    singles[table.name] = compareSingleType(table.name, uid, source, target, kind, fileMapping, mapping, prefetch.exclusions, srcIdentity, tgtIdentity, srcResolver, tgtResolver)
                }
                StrapiContentTypeKind.CollectionType -> {
                    collections[table.name] =
                        compareCollectionType(table.name, uid, sourceRows, targetRows, kind, mapping, fileMapping, prefetch.exclusions, srcIdentity, tgtIdentity, srcResolver, tgtResolver)
                }
                StrapiContentTypeKind.Files, StrapiContentTypeKind.Component -> {
                    // ignore
                }
            }
        }
        return singles to collections
    }

    // PASS 1: identities from syncId / file pairing / content fingerprints. Its job is to establish
    // the content pairing (which entries are "the same entity" on both sides).
    val srcIdentity1 = buildIdentityMap(prefetch.sourceRowsByUid, prefetch.sourceFiles, srcSyncByDoc, srcPairOverlay)
    val tgtIdentity1 = buildIdentityMap(prefetch.targetRowsByUid, prefetch.targetFiles, tgtSyncByDoc, tgtPairOverlay)
    val (singles1, collections1) = runPass(srcIdentity1, tgtIdentity1, HashMap(contentMapping))

    // Extend the overlay with every content pair found in pass 1, then rebuild identities: now a
    // reference to a paired-but-not-yet-reconciled entity (or a paired file) matches across
    // instances, so referrers are no longer reported DIFFERENT for relation reasons alone.
    (singles1.values + collections1.values.flatten()).forEach { e ->
        val s = e.sourceContent?.metadata?.documentId ?: return@forEach
        val t = e.targetContent?.metadata?.documentId ?: return@forEach
        val token = "pair:c:$s"
        srcPairOverlay[s] = token
        tgtPairOverlay[t] = token
    }

    // PASS 2: final classification with pair-aware identities.
    val srcIdentity = buildIdentityMap(prefetch.sourceRowsByUid, prefetch.sourceFiles, srcSyncByDoc, srcPairOverlay)
    val tgtIdentity = buildIdentityMap(prefetch.targetRowsByUid, prefetch.targetFiles, tgtSyncByDoc, tgtPairOverlay)
    val (singleTypes, collectionTypes) = runPass(srcIdentity, tgtIdentity, contentMapping)

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
