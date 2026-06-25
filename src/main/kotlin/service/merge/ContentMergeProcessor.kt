package it.sebi.service.merge

import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import it.sebi.client.StrapiClient
import it.sebi.database.dbQuery
import it.sebi.models.*
import it.sebi.repository.MergeRequestSelectionsRepository
import it.sebi.service.MergeRequestService
import it.sebi.service.identity.SyncIdentityService
import it.sebi.service.merge.ContentJsonUtils.normalizeKeyName
import it.sebi.service.resolveComponentTableName
import it.sebi.tables.MergeRequestDocumentMappingTable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

class ContentMergeProcessor(private val mergeRequestSelectionsRepository: MergeRequestSelectionsRepository) {
    private val logger = LoggerFactory.getLogger(ContentMergeProcessor::class.java)

    companion object {
        // Max concurrent items processed within a single dependency batch.
        private const val MAX_PARALLEL_CONTENT = 8
    }

    // Target content-type attributes (by collectionName), used to auto-drop source-only fields so a
    // COMPATIBLE-but-not-identical schema can still merge (target rejects unknown fields otherwise).
    private var targetAttrsByTable: Map<String, Set<String>> = emptyMap()
    private var targetAttrsNormByTable: Map<String, Set<String>> = emptyMap()

    private suspend fun loadTargetAttrs(targetClient: StrapiClient) {
        targetAttrsByTable = try {
            targetClient.getContentTypes().associate { it.schema.collectionName to it.schema.attributes.keys }
        } catch (e: Exception) {
            logger.warn("Could not load target content types for field filtering: ${e.message}")
            emptyMap()
        }
        targetAttrsNormByTable = targetAttrsByTable.mapValues { (_, v) -> v.map { normalizeKeyName(it) }.toSet() }
    }

    /** Remove fields the source has but the target schema doesn't, so writes aren't rejected. */
    private fun dropForeignFields(tableName: String, data: JsonObject): JsonObject {
        val allowed = targetAttrsByTable[tableName] ?: return data
        val allowedNorm = targetAttrsNormByTable[tableName] ?: emptySet()
        val dropped = mutableListOf<String>()
        val kept = data.filterKeys { k ->
            if (k.startsWith("__") || allowed.contains(k) || allowedNorm.contains(normalizeKeyName(k))) true
            else { dropped.add(k); false }
        }
        if (dropped.isNotEmpty()) logger.info("[schema-compat] '$tableName': dropping source-only fields absent in target: $dropped")
        return JsonObject(kept)
    }

    /**
     * Second pass: update only circular dependency relations for items whose dependencies succeeded.
     */
    suspend fun processCircularSecondPass(
        targetStrapiInstance: StrapiInstance,
        targetClient: StrapiClient,
        items: List<MergeRequestService.SyncOrderItem>,
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships,
        schema: DbSchema,
        circularEdges: List<MergeRequestService.CircularDependencyEdge>,
        allTargetFileIdByDoc: Map<String, Int> = emptyMap(),
        selectionStatusByKey: Map<Pair<String, String>, Boolean> = emptyMap(),
        mappingMap: MutableMap<String, MutableMap<String, MergeRequestDocumentMapping>> = mutableMapOf()
    ) {
        val contentTypeMappingByTable: Map<String, DbTable> = schema.tables.associateBy { it.name }
        if (targetAttrsByTable.isEmpty()) loadTargetAttrs(targetClient)

        // Group circular edges by source item (from)
        val circularByFrom: Map<Pair<String, String>, List<MergeRequestService.CircularDependencyEdge>> =
            circularEdges.groupBy { it.fromTable to it.fromDocumentId }

        for (item in items) {
            val selection = item.selection
            val entry = item.entry
            val key = selection.tableName to selection.documentId
            val itemCircular = circularByFrom[key] ?: continue

            // Ensure the item itself was synced successfully
            val selfOk = selectionStatusByKey[key] == true
            if (!selfOk) continue

            // Ensure all circular dependency targets were synced successfully
            val depsOk = itemCircular.all { edge -> selectionStatusByKey[edge.toTable to edge.toDocumentId] == true }
            if (!depsOk) continue

            val schemaTable = contentTypeMappingByTable[selection.tableName] ?: continue
            val sourceContent = entry.sourceContent ?: continue




            try {
                val linkToProcess: List<StrapiLinkRef> = sourceContent.links
                val dataToCreate = ContentJsonUtils.processJsonElementNew(
                    comparisonDataMap,
                    sourceContent.rawData,
                    schemaTable,
                    schema,
                    contentTypeMappingByTable,
                    linkToProcess,
                    allTargetFileIdByDoc,
                    mappingMap
                ).jsonObject
                // Use upsert by documentId mapped to TARGET.
                // mappingMap is keyed by the content-type apiUid (not the table name).
                val srcDoc = sourceContent.metadata.documentId
                val apiUid = schemaTable.metadata?.apiUid
                val targetDocumentId = apiUid?.let { mappingMap[it]?.get(srcDoc)?.targetDocumentId } ?: srcDoc
                targetClient.upsertContentEntry(
                    schemaTable.metadata!!.queryName,
                    dropForeignFields(schemaTable.name, dataToCreate),
                    entry.kind,
                    sourceContent.metadata.documentId,
                    targetDocumentId
                )
                // No status change on success; already marked earlier
            } catch (e: Exception) {
                logger.error("Error second-pass updating circular relations for ${selection.tableName}:${selection.documentId}: ${e.message}")
                // Mark selection as failed since full sync couldn't be completed
                mergeRequestSelectionsRepository.updateSyncStatus(
                    selection.id,
                    false,
                    e.message ?: "Second pass failed"
                )
            }
        }
    }


    /**
     * Process ordered batches of items computed by MergeRequestService.computeContentSyncOrder
     * Each batch is processed sequentially, and each item is handled individually.
     */
    suspend fun processBatches(
        sourceStrapiInstance: StrapiInstance,
        targetStrapiInstance: StrapiInstance,
        targetClient: StrapiClient,
        batches: List<List<MergeRequestService.SyncOrderItem>>,
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships,
        schema: DbSchema,
        circularEdges: List<MergeRequestService.CircularDependencyEdge> = emptyList(),
        allTargetFileIdByDoc: Map<String, Int> = emptyMap(),
        mappingMap: MutableMap<String, MutableMap<String, MergeRequestDocumentMapping>> = mutableMapOf()
    ) {
        val contentTypeMappingByTable: Map<String, DbTable> = schema.tables.associateBy { it.name }
        loadTargetAttrs(targetClient)

        // Build the set of all selected keys (table, documentId) to detect intra-selection dependencies
        val selectedKeys: Set<Pair<String, String>> = batches
            .flatten()
            .map { it.selection.tableName to it.selection.documentId }
            .toSet()

        // Track success/failure of processed items (concurrent: items in a batch run in parallel).
        val processedStatus = ConcurrentHashMap<Pair<String, String>, Boolean>()

        // Pre-create a thread-safe mapping bucket per content type that may be written this run, so
        // parallel items never race on bucket creation and writes/reads are concurrency-safe.
        batches.flatten()
            .mapNotNull { contentTypeMappingByTable[it.selection.tableName]?.metadata?.apiUid }
            .distinct()
            .forEach { uid ->
                val existing = mappingMap[uid]
                mappingMap[uid] = if (existing is ConcurrentHashMap) existing else ConcurrentHashMap(existing ?: emptyMap())
            }

        // Items in the same batch are independent by construction (dependencies live in earlier
        // batches, already complete), so we process them concurrently. Each batch is a barrier.
        val semaphore = Semaphore(MAX_PARALLEL_CONTENT)

        for (batch in batches) {
            coroutineScope {
                batch.map { item ->
                    async {
                        semaphore.withPermit {
                            val selection = item.selection
                            val entry = item.entry
                            val tableName = selection.tableName
                            val key = tableName to selection.documentId
                            try {
                                val schemaTable: DbTable = contentTypeMappingByTable[tableName] ?: return@withPermit

                                // Skip if any dependency inside the selection previously failed
                                val depsInsideSelection: List<Pair<String, String>> = (entry.sourceContent?.links ?: emptyList())
                                    .filter { it.targetTable != "files" } // files are processed separately
                                    .mapNotNull { link ->
                                        val table = link.targetTable
                                        contentTypeMappingByTable[table]?.metadata?.let { schemaLinkMeta ->
                                            val docId = resolveTargetDocIdForLink(
                                                table,
                                                schemaLinkMeta.apiUid,
                                                link.targetId,
                                                comparisonDataMap,
                                                mappingMap
                                            )
                                            if (docId != null) link.targetTable to docId else null
                                        }
                                    }
                                    .filter { selectedKeys.contains(it) }

                                val failedDeps = depsInsideSelection.filter { processedStatus[it] == false }
                                if (failedDeps.isNotEmpty()) {
                                    val reason =
                                        "Skipped due to failed dependency: ${failedDeps.joinToString { it.first + ":" + it.second }}"
                                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, false, reason)
                                    processedStatus[key] = false
                                    return@withPermit
                                }

                                when (entry.kind) {
                                    StrapiContentTypeKind.SingleType -> {
                                        processedStatus[key] = processSingleType(
                                            tableName, schemaTable, schema, selection, entry,
                                            sourceStrapiInstance, targetStrapiInstance, targetClient, comparisonDataMap,
                                            contentTypeMappingByTable,
                                            circularEdges.filter { it.fromTable == tableName && it.fromDocumentId == selection.documentId }
                                                .map { it.viaLink }.toSet(),
                                            allTargetFileIdByDoc, mappingMap
                                        )
                                    }

                                    StrapiContentTypeKind.CollectionType -> {
                                        processedStatus[key] = processCollectionType(
                                            tableName, schemaTable, schema, selection, entry,
                                            sourceStrapiInstance, targetStrapiInstance, targetClient, comparisonDataMap,
                                            contentTypeMappingByTable,
                                            circularEdges.filter { it.fromTable == tableName && it.fromDocumentId == selection.documentId }
                                                .map { it.viaLink }.toSet(),
                                            allTargetFileIdByDoc, mappingMap
                                        )
                                    }

                                    StrapiContentTypeKind.Files, StrapiContentTypeKind.Component -> {
                                        // Files are handled separately in MergeRequestService; skip here.
                                        return@withPermit
                                    }
                                }
                            } catch (e: Exception) {
                                // Failover: a single bad item (payload build, dependency resolution,
                                // dispatch) must not abort the whole merge. Record it and move on.
                                logger.error("Unexpected error processing $tableName:${selection.documentId}: ${e.message}", e)
                                try {
                                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, false, e.message ?: "Unexpected error")
                                } catch (_: Exception) { /* ignore */ }
                                processedStatus[key] = false
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    suspend fun processDeletions(
        targetStrapiInstance: StrapiInstance,
        targetClient: StrapiClient,
        deletions: List<MergeRequestSelection>,
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships,
        schema: DbSchema
    ) {
        val contentTypeMappingByTable: Map<String, DbTable> = schema.tables.associateBy { it.name }

        for (selection in deletions) {
            val tableName = selection.tableName
            val schemaTable = contentTypeMappingByTable[tableName] ?: continue
            val queryName = schemaTable.metadata!!.queryName
            try {
                if (comparisonDataMap.singleTypes.containsKey(tableName)) {
                    val cmp = comparisonDataMap.singleTypes[tableName]!!
                    val targetEntry = cmp.targetContent
                    val targetId = targetEntry?.metadata?.documentId
                    if (targetId != null) {
                        targetClient.deleteContentEntry(
                            queryName,
                            targetId.toString(),
                            "singleType"
                        )
                    }
                    // If there's no target entry, treat as already deleted
                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, true, null)
                } else if (comparisonDataMap.collectionTypes.containsKey(tableName)) {
                    val list = comparisonDataMap.collectionTypes[tableName]!!
                    // Prefer ONLY_IN_TARGET match, fallback to any target match by documentId
                    val entry = list.find {
                        it.compareState == ContentTypeComparisonResultKind.ONLY_IN_TARGET &&
                                it.targetContent?.metadata?.documentId == selection.documentId
                    } ?: list.find { it.targetContent?.metadata?.documentId == selection.documentId }

                    val targetId = entry?.targetContent?.metadata?.documentId
                    if (targetId != null) {
                        targetClient.deleteContentEntry(
                            queryName,
                            targetId.toString(),
                            "collectionType"
                        )
                        mergeRequestSelectionsRepository.updateSyncStatus(selection.id, true, null)
                    } else {
                        mergeRequestSelectionsRepository.updateSyncStatus(
                            selection.id,
                            false,
                            "Target entry not found for deletion"
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Error deleting entry for $tableName: ${e.message}")
                mergeRequestSelectionsRepository.updateSyncStatus(selection.id, false, e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Draft & Publish fidelity (Strapi v5), run AFTER the primary published write succeeds:
     *  - MODIFIED (source has a divergent draft overlay): write the draft body to the same target
     *    document with `?status=draft`, so the target reproduces the "modified" state (published v1
     *    + pending draft v2) without re-publishing.
     *  - DRAFT_ONLY (source has no published row): the primary write already went to the draft channel
     *    (status=draft); here we additionally unpublish the target if it still has a published row
     *    (e.g. it was previously published), so it ends up draft-only like the source.
     * No-op when includeDrafts is off (no overlay, not draft-only).
     */
    private suspend fun applyDraftFidelity(
        sourceEntry: it.sebi.models.StrapiContent,
        targetContent: it.sebi.models.StrapiContent?,
        targetDocumentId: String,
        contentTypeSchema: DbTable,
        dbSchema: DbSchema,
        kind: StrapiContentTypeKind,
        targetClient: StrapiClient,
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships,
        contentTypeMappingByTable: Map<String, DbTable>,
        excludeLinks: Set<StrapiLinkRef>,
        allTargetFileIdByDoc: Map<String, Int>,
        mappingMap: MutableMap<String, MutableMap<String, MergeRequestDocumentMapping>>
    ) {
        if (targetDocumentId.isBlank()) return
        val apiUid = contentTypeSchema.metadata!!.apiUid
        if (sourceEntry.metadata.isDraftOnly) {
            // Source is draft-only: make sure the target has no lingering published row.
            if (targetContent != null && !targetContent.metadata.isDraftOnly) {
                try {
                    targetClient.unpublishEntry(apiUid, targetDocumentId, kind)
                } catch (e: Exception) {
                    logger.error("Draft fidelity: failed to unpublish $apiUid/$targetDocumentId: ${e.message}", e)
                    throw e
                }
            }
            return
        }
        // MODIFIED: apply the divergent draft overlay on top of the just-published version.
        val overlay = sourceEntry.draft ?: return
        val draftLinks = overlay.links.filterNot { excludeLinks.contains(it) }
        val draftPayload = ContentJsonUtils.processJsonElementNew(
            comparisonDataMap,
            overlay.rawData,
            contentTypeSchema,
            dbSchema,
            contentTypeMappingByTable,
            draftLinks,
            allTargetFileIdByDoc,
            mappingMap
        ).jsonObject
        targetClient.upsertContentEntry(
            contentTypeSchema.metadata!!.queryName,
            dropForeignFields(contentTypeSchema.name, draftPayload),
            kind,
            sourceEntry.metadata.documentId,
            targetDocumentId,
            status = "draft"
        )
    }

    private suspend fun processSingleType(
        contentTypeUid: String,
        contentTypeSchema: DbTable,
        dbSchema: DbSchema,
        selection: MergeRequestSelection,
        comparisonResult: ContentTypeComparisonResultWithRelationships,
        sourceStrapiInstance: StrapiInstance,
        targetStrapiInstance: StrapiInstance,
        targetClient: StrapiClient,
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships,
        contentTypeMappingByTable: Map<String, DbTable>,
        excludeLinks: Set<StrapiLinkRef> = emptySet(),
        allTargetFileIdByDoc: Map<String, Int> = emptyMap(),
        mappingMap: MutableMap<String, MutableMap<String, MergeRequestDocumentMapping>> = mutableMapOf()
    ): Boolean {
        return when (selection.direction) {
            Direction.TO_CREATE, Direction.TO_UPDATE -> {

                val sourceEntry = comparisonResult.sourceContent ?: return false
                val linkToProcess: List<StrapiLinkRef> = sourceEntry.links.filterNot { excludeLinks.contains(it) }
                val dataToCreate = ContentJsonUtils.processJsonElementNew(
                    comparisonDataMap,
                    sourceEntry.rawData,
                    contentTypeSchema,
                    dbSchema,
                    contentTypeMappingByTable,
                    linkToProcess,
                    allTargetFileIdByDoc,
                    mappingMap
                ).jsonObject
                try {
                    // Idempotent re-run: fall back to the previously-created target (mapping) so a
                    // re-run updates in place instead of duplicating / hitting unique-field errors.
                    val targetDocForUpsert = comparisonResult.targetContent?.metadata?.documentId
                        ?: mappingMap[contentTypeSchema.metadata!!.apiUid]?.get(sourceEntry.metadata.documentId)?.targetDocumentId
                    // Draft & Publish fidelity (v5): a draft-only source writes to the draft channel and
                    // is NOT published; everything else writes+publishes the published body as usual.
                    val primaryStatus = if (sourceEntry.metadata.isDraftOnly) "draft" else null
                    val response = targetClient.upsertContentEntry(
                        contentTypeSchema.metadata!!.queryName,
                        dropForeignFields(contentTypeSchema.name, dataToCreate),
                        StrapiContentTypeKind.SingleType,
                        sourceEntry.metadata.documentId,
                        targetDocForUpsert,
                        status = primaryStatus
                    )
                    val data = response["data"]?.jsonObject
                    val targetDocumentId = data?.get("documentId")?.jsonPrimitive?.contentOrNull ?: ""
                    val targetId = data?.get("id")?.jsonPrimitive?.intOrNull
                    // Save mapping between source and target instead of updating target DB document_id
                    dbQuery {
                        val sourceDocId = sourceEntry.metadata.documentId
                        val sourceId = sourceEntry.metadata.id
                        val contentTypeUid = contentTypeSchema.metadata!!.apiUid
                        val locale = sourceEntry.metadata.locale
                        var predicate: Op<Boolean> =
                            (MergeRequestDocumentMappingTable.sourceStrapiId eq sourceStrapiInstance.id) and
                                    (MergeRequestDocumentMappingTable.targetStrapiId eq targetStrapiInstance.id) and
                                    (MergeRequestDocumentMappingTable.contentType eq contentTypeUid) and
                                    (MergeRequestDocumentMappingTable.sourceDocumentId eq sourceDocId)
                        predicate =
                            if (locale != null) predicate and (MergeRequestDocumentMappingTable.locale eq locale) else predicate and MergeRequestDocumentMappingTable.locale.isNull()
                        val existing = MergeRequestDocumentMappingTable.selectAll().where { predicate }.toList()
                        if (existing.isEmpty()) {
                             MergeRequestDocumentMappingTable.insert {
                                it[MergeRequestDocumentMappingTable.sourceStrapiId] = sourceStrapiInstance.id
                                it[MergeRequestDocumentMappingTable.targetStrapiId] = targetStrapiInstance.id
                                it[MergeRequestDocumentMappingTable.contentType] = contentTypeUid
                                it[MergeRequestDocumentMappingTable.sourceId] = sourceId
                                it[MergeRequestDocumentMappingTable.sourceDocumentId] = sourceDocId
                                it[MergeRequestDocumentMappingTable.targetId] = targetId
                                it[MergeRequestDocumentMappingTable.targetDocumentId] = targetDocumentId
                                it[MergeRequestDocumentMappingTable.locale] = locale
                            }

                        } else {
                            val exId = existing.first()[MergeRequestDocumentMappingTable.id].value
                            MergeRequestDocumentMappingTable.update({ MergeRequestDocumentMappingTable.id eq exId }) {
                                it[MergeRequestDocumentMappingTable.targetId] = targetId
                                it[MergeRequestDocumentMappingTable.targetDocumentId] = targetDocumentId
                                it[MergeRequestDocumentMappingTable.locale] = locale
                            }
                        }
                        // Update in-memory map as well for downstream dependencies in this run
                        val perType = mappingMap.getOrPut(contentTypeUid) { mutableMapOf() }
                        perType[sourceDocId] = MergeRequestDocumentMapping(
                            id = 0,
                            sourceStrapiInstanceId = sourceStrapiInstance.id,
                            targetStrapiInstanceId = targetStrapiInstance.id,
                            contentType = contentTypeUid,
                            sourceId = sourceId,
                            sourceDocumentId = sourceDocId,
                            targetId = targetId,
                            targetDocumentId = targetDocumentId
                        )
                    }
                    // Anti-drift (Phase 1): propagate the SOURCE syncId onto the target sidecar so
                    // identity stays shared after apply (prevents phantom diffs on re-compare).
                    val srcSyncId = sourceEntry.metadata.syncId
                    if (!srcSyncId.isNullOrBlank() && targetDocumentId.isNotBlank()) {
                        SyncIdentityService.upsertIdentity(
                            targetStrapiInstance,
                            contentTypeSchema.metadata!!.apiUid,
                            targetDocumentId,
                            sourceEntry.metadata.locale,
                            srcSyncId
                        )
                    }
                    // Draft & Publish fidelity: write the divergent draft overlay (modified) or ensure
                    // the target is unpublished (draft-only). No-op when includeDrafts is off.
                    applyDraftFidelity(
                        sourceEntry, comparisonResult.targetContent, targetDocumentId,
                        contentTypeSchema, dbSchema, StrapiContentTypeKind.SingleType, targetClient,
                        comparisonDataMap, contentTypeMappingByTable, excludeLinks, allTargetFileIdByDoc, mappingMap
                    )
                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, true, null)
                    true
                } catch (clex: ClientRequestException) {
                    val respBody = clex.response.bodyAsText()
                    val msg = "${clex.localizedMessage}: $respBody"
                    logger.error("Error creating entry for merge request $contentTypeUid: $msg", clex)
                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, false, msg)
                    false
                } catch (e: Exception) {
                    logger.error("Error creating single type $contentTypeUid: ${e.message}", e)
                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, false, e.message ?: "Unknown error")
                    false
                }
            }

            Direction.TO_DELETE -> {
                val targetEntry = comparisonResult.targetContent ?: return false
                try {
                    targetClient.deleteContentEntry(
                        contentTypeSchema.metadata!!.queryName,
                        targetEntry.metadata.id.toString(),
                        "singleType"
                    )
                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, true, null)
                    true
                } catch (e: Exception) {
                    logger.error("Error deleting single type $contentTypeUid: ${e.message}")
                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, false, e.message ?: "Unknown error")
                    false
                }
            }
        }
    }

    private suspend fun processCollectionType(
        contentType: String,
        contentTypeSchema: DbTable,
        dbSchema: DbSchema,
        selection: MergeRequestSelection,
        entry: ContentTypeComparisonResultWithRelationships,
        sourceStrapiInstance: StrapiInstance,
        targetStrapiInstance: StrapiInstance,
        targetClient: StrapiClient,
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships,
        contentTypeMappingByTable: Map<String, DbTable>,
        excludeLinks: Set<StrapiLinkRef> = emptySet(),
        allTargetFileIdByDoc: Map<String, Int> = emptyMap(),
        mappingMap: MutableMap<String, MutableMap<String, MergeRequestDocumentMapping>> = mutableMapOf()
    ): Boolean {
        return when (selection.direction) {
            Direction.TO_CREATE, Direction.TO_UPDATE -> {
                val sourceContent = entry.sourceContent ?: return false
                val fieldNameResolver = ContentJsonUtils.buildFieldNameResolver(contentTypeSchema)
                val linkToProcess: List<StrapiLinkRef> = sourceContent.links.filterNot { excludeLinks.contains(it) }
                val dataToCreate = ContentJsonUtils.processJsonElementNew(
                    comparisonDataMap,
                    sourceContent.rawData,
                    contentTypeSchema,
                    dbSchema,
                    contentTypeMappingByTable,
                    linkToProcess,
                    allTargetFileIdByDoc,
                    mappingMap
                ).jsonObject
                // Merge relations (including files) into the payload, excluding circular dependency links
//                run {
//                    val repeatableByKey: Map<String, Boolean> = (contentTypeSchema.metadata?.columns ?: emptyList())
//                        .associate { normalizeKeyName(it.name) to it.repeatable }
//                    buildRelationsDataFromLinks(
//                        sourceContent,
//                        contentTypeSchema,
//                        dbSchema,
//                        comparisonDataMap,
//                        fieldNameResolver,
//                        excludeLinks,
//                        allTargetFileIdByDoc,
//                        contentTypeMappingByTable,
//                        repeatableByKey
//                    )?.let { rel ->
//                        dataToCreate = ContentJsonUtils.deepMergeJsonObjects(dataToCreate, rel)
//                    }
//                }
                try {
                    // Idempotent re-run: if this source entry was already created in a previous run
                    // (mapping exists), upsert against that target documentId instead of creating a
                    // duplicate (or hitting a unique-field ValidationError).
                    val targetDocForUpsert = entry.targetContent?.metadata?.documentId
                        ?: mappingMap[contentTypeSchema.metadata!!.apiUid]?.get(sourceContent.metadata.documentId)?.targetDocumentId
                    // Draft & Publish fidelity (v5): a draft-only source writes to the draft channel and
                    // is NOT published; everything else writes+publishes the published body as usual.
                    val primaryStatus = if (sourceContent.metadata.isDraftOnly) "draft" else null
                    val response = targetClient.upsertContentEntry(
                        contentTypeSchema.metadata!!.queryName,
                        dropForeignFields(contentTypeSchema.name, dataToCreate),
                        StrapiContentTypeKind.CollectionType,
                        sourceContent.metadata.documentId,
                        targetDocForUpsert,
                        status = primaryStatus
                    )
                    val data = response["data"]?.jsonObject
                    val targetDocumentId = data?.get("documentId")?.jsonPrimitive?.contentOrNull ?: ""
                    val targetId = data?.get("id")?.jsonPrimitive?.intOrNull
                    // Save mapping between source and target instead of updating target DB document_id
                    dbQuery {
                        val sourceDocId = sourceContent.metadata.documentId
                        val sourceId = sourceContent.metadata.id
                        val contentTypeUid = contentTypeSchema.metadata!!.apiUid
                        val locale = sourceContent.metadata.locale
                        var predicate: Op<Boolean> =
                            (MergeRequestDocumentMappingTable.sourceStrapiId eq sourceStrapiInstance.id) and
                                    (MergeRequestDocumentMappingTable.targetStrapiId eq targetStrapiInstance.id) and
                                    (MergeRequestDocumentMappingTable.contentType eq contentTypeUid) and
                                    (MergeRequestDocumentMappingTable.sourceDocumentId eq sourceDocId)
                        predicate =
                            if (locale != null) predicate and (MergeRequestDocumentMappingTable.locale eq locale) else predicate and MergeRequestDocumentMappingTable.locale.isNull()
                        val existing = MergeRequestDocumentMappingTable.selectAll().where { predicate }.toList()
                        if (existing.isEmpty()) {
                            MergeRequestDocumentMappingTable.insert {
                                it[MergeRequestDocumentMappingTable.sourceStrapiId] = sourceStrapiInstance.id
                                it[MergeRequestDocumentMappingTable.targetStrapiId] = targetStrapiInstance.id
                                it[MergeRequestDocumentMappingTable.contentType] = contentTypeUid
                                it[MergeRequestDocumentMappingTable.sourceId] = sourceId
                                it[MergeRequestDocumentMappingTable.sourceDocumentId] = sourceDocId
                                it[MergeRequestDocumentMappingTable.targetId] = targetId
                                it[MergeRequestDocumentMappingTable.targetDocumentId] = targetDocumentId
                                it[MergeRequestDocumentMappingTable.locale] = locale
                            }
                        } else {
                            val exId = existing.first()[MergeRequestDocumentMappingTable.id].value
                            MergeRequestDocumentMappingTable.update({ MergeRequestDocumentMappingTable.id eq exId }) {
                                it[MergeRequestDocumentMappingTable.targetId] = targetId
                                it[MergeRequestDocumentMappingTable.targetDocumentId] = targetDocumentId
                                it[MergeRequestDocumentMappingTable.locale] = locale
                            }
                        }
                        // Update in-memory map as well for downstream dependencies in this run
                        val perType = mappingMap.getOrPut(contentTypeUid) { mutableMapOf() }
                        perType[sourceDocId] = MergeRequestDocumentMapping(
                            id = 0,
                            sourceStrapiInstanceId = sourceStrapiInstance.id,
                            targetStrapiInstanceId = targetStrapiInstance.id,
                            contentType = contentTypeUid,
                            sourceId = sourceId,
                            sourceDocumentId = sourceDocId,
                            targetId = targetId,
                            targetDocumentId = targetDocumentId
                        )
                    }
                    // Anti-drift (Phase 1): propagate the SOURCE syncId onto the target sidecar so
                    // identity stays shared after apply (prevents phantom diffs on re-compare).
                    val srcSyncId = sourceContent.metadata.syncId
                    if (!srcSyncId.isNullOrBlank() && targetDocumentId.isNotBlank()) {
                        SyncIdentityService.upsertIdentity(
                            targetStrapiInstance,
                            contentTypeSchema.metadata!!.apiUid,
                            targetDocumentId,
                            sourceContent.metadata.locale,
                            srcSyncId
                        )
                    }
                    // Draft & Publish fidelity: write the divergent draft overlay (modified) or ensure
                    // the target is unpublished (draft-only). No-op when includeDrafts is off.
                    applyDraftFidelity(
                        sourceContent, entry.targetContent, targetDocumentId,
                        contentTypeSchema, dbSchema, StrapiContentTypeKind.CollectionType, targetClient,
                        comparisonDataMap, contentTypeMappingByTable, excludeLinks, allTargetFileIdByDoc, mappingMap
                    )
                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, true, null)
                    true
                } catch (e: Exception) {
                    logger.error("Error creating collection type entry $contentType: ${e.message}")
                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, false, e.message ?: "Unknown error")
                    false
                }
            }

            Direction.TO_DELETE -> {
                val targetId = entry.targetContent?.metadata?.id ?: return false
                try {
                    val success = targetClient.deleteContentEntry(
                        contentTypeSchema.metadata!!.queryName,
                        targetId.toString(),
                        "collectionType"
                    )
                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, success, null)
                    success
                } catch (e: Exception) {
                    logger.error("Error deleting collection type entry $contentType: ${e.message}")
                    mergeRequestSelectionsRepository.updateSyncStatus(selection.id, false, e.message ?: "Unknown error")
                    false
                }
            }
        }
    }

    private fun resolveTargetDocIdForLink(
        table: String,
        targetCollectionUID: String,
        id: Int?,
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships,
        mappingMap: Map<String, Map<String, MergeRequestDocumentMapping>> = emptyMap()
    ): String? {
        if (id == null) return null
        // Step 1: find the SOURCE documentId for the provided numeric id
        var sourceDocId: String? = null
        when (table) {
            "files" -> {
                comparisonDataMap.files.forEach { cmp ->
                    val s = cmp.sourceImage?.metadata
                    if (s?.id == id) sourceDocId = s.documentId
                    val t = cmp.targetImage?.metadata
                    // if only target match is found, keep that as fallback
                    if (sourceDocId == null && t?.id == id) sourceDocId = t.documentId
                }
            }

            else -> {
                comparisonDataMap.singleTypes[table]?.let { st ->
                    if (st.sourceContent?.metadata?.id == id) sourceDocId = st.sourceContent.metadata.documentId
                    if (sourceDocId == null && st.targetContent?.metadata?.id == id) sourceDocId =
                        st.targetContent.metadata.documentId
                }
                if (sourceDocId == null) {
                    comparisonDataMap.collectionTypes[table]?.forEach { e ->
                        if (e.sourceContent?.metadata?.id == id) sourceDocId = e.sourceContent.metadata.documentId
                        if (sourceDocId == null && e.targetContent?.metadata?.id == id) sourceDocId =
                            e.targetContent.metadata.documentId
                    }
                }
            }
        }
        val src = sourceDocId ?: return null
        // Step 2: map SOURCE -> TARGET using mappingMap (if present)
        val mappingForType = mappingMap[targetCollectionUID]
        val targetDoc = mappingForType?.get(src)?.targetDocumentId
        return targetDoc ?: src
    }

//    private fun buildRelationsDataFromLinks(
//        base: StrapiContent?,
//        baseContentTypeSchema: DbTable,
//        dbSchema: DbSchema,
//        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships,
//        fieldNameResolver: Map<String, String>,
//        excludeLinks: Set<StrapiLinkRef> = emptySet(),
//        allTargetFileIdByDoc: Map<String, Int> = emptyMap(),
//        contentTypeMappingByTable: Map<String, DbTable>,
//        repeatableByKey: Map<String, Boolean> = emptyMap(),
//        mappingMap: Map<String, Map<String, MergeRequestDocumentMapping>> = emptyMap()
//    ): JsonObject? {
//        if (base == null) return null
//        if (base.links.isEmpty()) return null
//        val effectiveLinks = base.links.filterNot { excludeLinks.contains(it) }
//        if (effectiveLinks.isEmpty()) return null
//
//        // Helper to resolve target value (documentId or numeric for files)
//        fun resolveTargetValue(link: StrapiLinkRef): JsonElement? {
//            return if (link.targetTable == "files") {
//
//                val srcOrTgtDocId = resolveTargetDocIdForLink(
//                    link.targetTable,
//                    STRAPI_FILE_CONTENT_TYPE_NAME,
//                    link.targetId,
//                    comparisonDataMap,
//                    mappingMap
//                )
//                val numericId = srcOrTgtDocId?.let { did ->
//                    allTargetFileIdByDoc[did]
//                        ?: comparisonDataMap.files.firstOrNull { f -> f.targetImage?.metadata?.documentId == did }?.targetImage?.metadata?.id
//                }
//                numericId?.let { JsonPrimitive(it) }
//            } else {
//                val table = link.targetTable
//                contentTypeMappingByTable[table]?.metadata?.let { schemaLinkMeta ->
//                    val targetDocId =
//                        resolveTargetDocIdForLink(
//                            link.targetTable,
//                            schemaLinkMeta.apiUid,
//                            link.targetId,
//                            comparisonDataMap,
//                            mappingMap
//                        )
//                    targetDocId?.let { JsonPrimitive(it) }
//                }
//            }
//        }
//
//        // Split links between repeatable root fields and non-repeatable
//        val linksByFieldPath: Map<String, List<StrapiLinkRef>> = effectiveLinks.groupBy { it.field }
//        val repeatableLinksByRoot: MutableMap<String, MutableList<StrapiLinkRef>> = mutableMapOf()
//        val nonRepeatableFieldGroups: MutableMap<String, List<StrapiLinkRef>> = mutableMapOf()
//
//        linksByFieldPath.forEach { (fieldPath, list) ->
//            val rootKey = fieldPath.substringBefore('.')
//            val isRepeatable = repeatableByKey[normalizeKeyName(rootKey)] == true
//            if (isRepeatable) {
//                repeatableLinksByRoot.getOrPut(rootKey) { mutableListOf() }.addAll(list)
//            } else {
//                nonRepeatableFieldGroups[fieldPath] = list
//            }
//        }
//
//        var dataObj: JsonObject = buildJsonObject { }
//
//        // 1) Handle repeatable component relations, preserving order and mapping by sourceId
//        for ((rootKey, links) in repeatableLinksByRoot) {
//            // Locate component schema for the repeatable rootKey
//            val columnMeta = baseContentTypeSchema.metadata?.columns?.find { it.name == rootKey } ?: continue
//            val componentName = columnMeta.component ?: continue
//            val componentTableName = resolveComponentTableName(componentName, dbSchema) ?: continue
//            val componentSchema = contentTypeMappingByTable[componentTableName] ?: continue
//            val subFieldNameResolver = ContentJsonUtils.buildFieldNameResolver(componentSchema)
//
//            // Build mapping from source component id -> index based on rawData (it contains component ids)
//            val rawArray = base.rawData[rootKey] as? JsonArray ?: continue
//            val idToIndex: Map<Int, Int> = rawArray.mapIndexedNotNull { index, el ->
//                val id = (el as? JsonObject)?.get("id")?.toString()?.trim('"')?.toIntOrNull()
//                if (id != null) id to index else null
//            }.toMap()
//
//            // Prepare a processed array starting from source clean data (so replacing the array won't lose fields)
//            val processedItems: MutableList<JsonObject> = rawArray.map { el ->
//                val obj = el as? JsonObject ?: buildJsonObject { }
//                ContentJsonUtils.processJsonElement(
//                    element = obj,
//                    currentResolver = subFieldNameResolver,
//                    currentSchema = componentSchema,
//                    dbSchema = dbSchema,
//                    contentTypeMappingByTable = contentTypeMappingByTable
//                ) as JsonObject
//            }.toMutableList()
//
//            // Accumulate values per index and sub-field, preserving order by 'order' and then by 'id'
//            data class OrderedVal(val ord: Double, val tie: Int, val value: JsonElement)
//
//            val perIndex: MutableMap<Int, MutableMap<String, MutableList<OrderedVal>>> = mutableMapOf()
//
//            links.forEach { link ->
//                val subField = link.field.substringAfter('.', missingDelimiterValue = "")
//                if (subField.isEmpty()) return@forEach
//                val idx = idToIndex[link.sourceId] ?: return@forEach
//                val value = resolveTargetValue(link) ?: return@forEach
//                val subPath = subField
//                val ord = link.order ?: Double.MAX_VALUE
//                val tie = link.id ?: Int.MAX_VALUE
//                val m1 = perIndex.getOrPut(idx) { mutableMapOf() }
//                val list = m1.getOrPut(subPath) { mutableListOf() }
//                list.add(OrderedVal(ord, tie, value))
//            }
//
//            // Inject relation sets into the processed items
//            perIndex.forEach { (idx, subMap) ->
//                var currentObj: JsonObject = processedItems[idx]
//                subMap.forEach { (subPath, ovalues) ->
//                    val sortedValues = ovalues.sortedWith(compareBy<OrderedVal> { it.ord }.thenBy { it.tie })
//                        .map { it.value }
//                    if (sortedValues.isNotEmpty()) {
//                        val fieldObj = buildJsonObject { put("set", JsonArray(sortedValues)) }
//                        val nested = ContentJsonUtils.buildNestedObjectFromPathWithSchema(
//                            subPath,
//                            fieldObj,
//                            componentSchema,
//                            dbSchema,
//                            contentTypeMappingByTable
//                        )
//                        currentObj = ContentJsonUtils.deepMergeJsonObjects(currentObj, nested)
//                    }
//                }
//                processedItems[idx] = currentObj
//            }
//
//            // Now build the nested object to merge at rootKey
//            val resolvedRootKey = fieldNameResolver[normalizeKeyName(rootKey)] ?: rootKey
//            val nested = buildJsonObject {
//                val cleanObj = processedItems.map { o ->
//                    buildJsonObject {
//                        o.entries.filterNot { it.key == "id" }.forEach { (k, v) -> put(k, v) }
//                    }
//                }
//                put(resolvedRootKey, JsonArray(cleanObj))
//            }
//            dataObj = ContentJsonUtils.deepMergeJsonObjects(dataObj, nested)
//        }
//
//        // 2) Handle non-repeatable fields, but detect and process nested repeatable components recursively
//        // Helper: resolve mapped key from schema for a raw segment name
//        fun resolveMappedKey(schema: DbTable?, raw: String): String {
//            val normalized = normalizeKeyName(raw)
//            val col = schema?.metadata?.columns?.firstOrNull { normalizeKeyName(it.name) == normalized }
//            return col?.name ?: ContentJsonUtils.convertToCamelCase(raw)
//        }
//
//        // Helper: traverse rawData to the parent object of a given path (schema-aware)
//        fun traverseRawToParent(raw: JsonObject, parentPath: String, rootSchema: DbTable?): JsonObject? {
//            if (parentPath.isEmpty()) return raw
//            var currentObj: JsonObject = raw
//            var currentSchema: DbTable? = rootSchema
//            val parts = parentPath.split('.')
//            for (part in parts) {
//                val normalized = normalizeKeyName(part)
//                val col = currentSchema?.metadata?.columns?.firstOrNull { normalizeKeyName(it.name) == normalized }
//                val mapped = col?.name ?: ContentJsonUtils.convertToCamelCase(part)
//                val nextEl = currentObj[mapped] ?: currentObj[part] ?: currentObj[ContentJsonUtils.convertToCamelCase(part)]
//                val nextObj = nextEl as? JsonObject ?: return null
//                // switch schema if this level is a component
//                currentSchema = if (col?.component != null) {
//                    val compTable = resolveComponentTableName(col.component, dbSchema)
//                    if (compTable != null) contentTypeMappingByTable[compTable] else currentSchema
//                } else currentSchema
//                currentObj = nextObj
//            }
//            return currentObj
//        }
//
//        data class PathInfo(
//            val parentPath: String,
//            val repeatSegment: String,
//            val remainder: String,
//            val parentSchema: DbTable?,
//            val itemSchema: DbTable?
//        )
//
//        fun analyzePath(path: String, startSchema: DbTable?): PathInfo? {
//            val parts = path.split('.')
//            var currentSchema: DbTable? = startSchema
//            var parentSchema: DbTable? = startSchema
//            val parentParts = mutableListOf<String>()
//            for (i in parts.indices) {
//                val seg = parts[i]
//                val normalized = normalizeKeyName(seg)
//                val col = currentSchema?.metadata?.columns?.firstOrNull { normalizeKeyName(it.name) == normalized }
//                if (col != null && col.repeatable && col.component != null) {
//                    val compTable = resolveComponentTableName(col.component, dbSchema)
//                    val itemSchema = compTable?.let { contentTypeMappingByTable[it] }
//                    val parentPath = parentParts.joinToString(".")
//                    val remainder = if (i + 1 < parts.size) parts.subList(i + 1, parts.size).joinToString(".") else ""
//                    return PathInfo(parentPath, seg, remainder, parentSchema, itemSchema)
//                }
//                // advance
//                if (col?.component != null) {
//                    parentSchema = currentSchema
//                    val compTable = resolveComponentTableName(col.component, dbSchema)
//                    currentSchema = compTable?.let { contentTypeMappingByTable[it] }
//                }
//                parentParts.add(seg)
//            }
//            return null
//        }
//
//        // Group nonRepeatable fields by nested repeatable anchor if present
//        val anchorGroups: MutableMap<String, MutableList<Pair<String, List<StrapiLinkRef>>>> = mutableMapOf()
//        val anchorInfo: MutableMap<String, PathInfo> = mutableMapOf()
//        val simpleNonRepeatable: MutableMap<String, List<StrapiLinkRef>> = mutableMapOf()
//
//        nonRepeatableFieldGroups.forEach { (fieldPath, links) ->
//            val info = analyzePath(fieldPath, baseContentTypeSchema)
//            if (info != null) {
//                val anchor = if (info.parentPath.isEmpty()) info.repeatSegment else info.parentPath + "." + info.repeatSegment
//                anchorGroups.getOrPut(anchor) { mutableListOf() }.add(fieldPath to links)
//                anchorInfo.putIfAbsent(anchor, info)
//            } else {
//                simpleNonRepeatable[fieldPath] = links
//            }
//        }
//
//        // Process groups with nested repeatable components
//        for ((anchor, entries) in anchorGroups) {
//            val info = anchorInfo[anchor] ?: continue
//            val parentObj = traverseRawToParent(base.rawData, info.parentPath, baseContentTypeSchema) ?: continue
//            val repeatKey = resolveMappedKey(info.parentSchema, info.repeatSegment)
//            val rawArrayEl = parentObj[repeatKey] ?: parentObj[info.repeatSegment] ?: parentObj[ContentJsonUtils.convertToCamelCase(info.repeatSegment)]
//            val rawArray = rawArrayEl as? JsonArray ?: continue
//
//            // Map source component id -> index
//            val idToIndex: Map<Int, Int> = rawArray.mapIndexedNotNull { idx, el ->
//                val id = (el as? JsonObject)?.get("id")?.toString()?.trim('"')?.toIntOrNull()
//                if (id != null) id to idx else null
//            }.toMap()
//
//            // Prepare processed items starting from raw data
//            val itemSchema = info.itemSchema ?: continue
//            val itemResolver = ContentJsonUtils.buildFieldNameResolver(itemSchema)
//            val processedItems: MutableList<JsonObject> = rawArray.map { el ->
//                val obj = el as? JsonObject ?: buildJsonObject { }
//                ContentJsonUtils.processJsonElement(
//                    element = obj,
//                    currentResolver = itemResolver,
//                    currentSchema = itemSchema,
//                    dbSchema = dbSchema,
//                    contentTypeMappingByTable = contentTypeMappingByTable
//                ) as JsonObject
//            }.toMutableList()
//
//            data class OrderedVal2(val ord: Double, val tie: Int, val value: JsonElement)
//            val perIndex: MutableMap<Int, MutableMap<String, MutableList<OrderedVal2>>> = mutableMapOf()
//
//            entries.forEach { (fieldPath, links) ->
//                val pi = analyzePath(fieldPath, baseContentTypeSchema) ?: return@forEach
//                val subPath = pi.remainder
//                links.forEach { link ->
//                    val idx = idToIndex[link.sourceId] ?: return@forEach
//                    val value = resolveTargetValue(link) ?: return@forEach
//                    val ord = link.order ?: Double.MAX_VALUE
//                    val tie = link.id ?: Int.MAX_VALUE
//                    val m1 = perIndex.getOrPut(idx) { mutableMapOf() }
//                    val list = m1.getOrPut(subPath) { mutableListOf() }
//                    list.add(OrderedVal2(ord, tie, value))
//                }
//            }
//
//            // Inject into processed items
//            perIndex.forEach { (idx, subMap) ->
//                var currentObj: JsonObject = processedItems[idx]
//                subMap.forEach { (subPath, ovalues) ->
//                    val sortedValues = ovalues.sortedWith(compareBy<OrderedVal2> { it.ord }.thenBy { it.tie }).map { it.value }
//                    if (sortedValues.isNotEmpty()) {
//                        val fieldObj = buildJsonObject { put("set", JsonArray(sortedValues)) }
//                        val nested = ContentJsonUtils.buildNestedObjectFromPathWithSchema(
//                            subPath,
//                            fieldObj,
//                            itemSchema,
//                            dbSchema,
//                            contentTypeMappingByTable
//                        )
//                        currentObj = ContentJsonUtils.deepMergeJsonObjects(currentObj, nested)
//                    }
//                }
//                processedItems[idx] = currentObj
//            }
//
//            // Build object at parent level with the processed array
//            val cleanArray = processedItems.map { o ->
//                buildJsonObject { o.entries.filterNot { it.key == "id" }.forEach { (k, v) -> put(k, v) } }
//            }
//            val leafAtParent = buildJsonObject { put(repeatKey, JsonArray(cleanArray)) }
//            val nestedParent = if (info.parentPath.isEmpty()) leafAtParent else ContentJsonUtils.buildNestedObjectFromPathWithSchema(
//                info.parentPath,
//                leafAtParent,
//                baseContentTypeSchema,
//                dbSchema,
//                contentTypeMappingByTable
//            )
//            dataObj = ContentJsonUtils.deepMergeJsonObjects(dataObj, nestedParent)
//        }
//
//        // Finally, handle simple non-repeatable fields (no nested repeatables)
//        for ((fieldPath, list) in simpleNonRepeatable) {
//            val values: List<JsonElement> = list
//                .sortedWith(compareBy<StrapiLinkRef> { it.order ?: Double.MAX_VALUE }.thenBy { it.id ?: Int.MAX_VALUE })
//                .mapNotNull { link -> resolveTargetValue(link) }
//            if (values.isNotEmpty()) {
//                val fieldObj = buildJsonObject { put("set", JsonArray(values)) }
//                val nested = ContentJsonUtils.buildNestedObjectFromPathWithSchema(
//                    fieldPath,
//                    fieldObj,
//                    baseContentTypeSchema,
//                    dbSchema,
//                    contentTypeMappingByTable
//                )
//                dataObj = ContentJsonUtils.deepMergeJsonObjects(dataObj, nested)
//            }
//        }
//        dataObj
//
//        return if (dataObj.isEmpty()) null else dataObj
//    }

//    private suspend fun alignRelationsSecondPass(
//        selections: List<MergeRequestSelection>,
//        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships,
//        contentTypeMappingByTable: Map<String, DbTable>,
//        targetClient: StrapiClient
//    ) {
//        for (selection in selections) {
//            val schemaTable = contentTypeMappingByTable[selection.tableName] ?: continue
//            val queryName = schemaTable.metadata!!.queryName
//            val fieldNameResolver = ContentJsonUtils.buildFieldNameResolver(schemaTable)
//            val contentToSync = comparisonDataMap.singleTypes[selection.tableName]?.let { listOf(it) }
//                ?: comparisonDataMap.collectionTypes[selection.tableName]
//            contentToSync?.forEach { content ->
//                val link: JsonObject? = buildRelationsDataFromLinks(content.sourceContent, comparisonDataMap, fieldNameResolver)
//                if (link != null) try {
//                    targetClient.updateContentEntry(
//                        queryName,
//                        content.id,
//                        link,
//                        content.kind
//                    )
//                } catch (e: Exception) {
//                    logger.error("Error setting relations for ${content.kind} $queryName: ${e.message}", e)
//                    logger.error("Link data: $link")
//                }
//            }
//        }
//    }
}