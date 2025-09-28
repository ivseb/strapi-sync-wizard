package it.sebi.service.merge

import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import it.sebi.client.StrapiClient
import it.sebi.database.dbQuery
import it.sebi.models.*
import it.sebi.repository.MergeRequestSelectionsRepository
import it.sebi.service.MergeRequestService
import it.sebi.service.merge.ContentJsonUtils.normalizeKeyName
import it.sebi.service.resolveComponentTableName
import it.sebi.tables.MergeRequestDocumentMappingTable
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory

class ContentMergeProcessor(private val mergeRequestSelectionsRepository: MergeRequestSelectionsRepository) {
    private val logger = LoggerFactory.getLogger(ContentMergeProcessor::class.java)

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
            val fieldNameResolver = ContentJsonUtils.buildFieldNameResolver(schemaTable)

            // Build payload including ONLY circular links: exclude all non-circular
            val circularLinkSet: Set<StrapiLinkRef> = itemCircular.map { it.viaLink }.toSet()
            val excludeLinks: Set<StrapiLinkRef> =
                sourceContent.links.filterNot { circularLinkSet.contains(it) }.toSet()
            val repeatableByKey: Map<String, Boolean> = (schemaTable.metadata?.columns ?: emptyList())
                .associate { ContentJsonUtils.normalizeKeyName(it.name) to it.repeatable }
            val relationsOnly = buildRelationsDataFromLinks(
                sourceContent,
                schemaTable,
                schema,
                comparisonDataMap,
                fieldNameResolver,
                excludeLinks,
                allTargetFileIdByDoc,
                contentTypeMappingByTable,
                repeatableByKey,
                mappingMap
            ) ?: continue

            try {
                // Use upsert by documentId mapped to TARGET
                val srcDoc = sourceContent.metadata.documentId
                val targetDocumentId = mappingMap[selection.tableName]?.get(srcDoc)?.targetDocumentId ?: srcDoc
                targetClient.upsertContentEntry(
                    schemaTable.metadata!!.queryName,
                    relationsOnly,
                    entry.kind,
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

        // Build the set of all selected keys (table, documentId) to detect intra-selection dependencies
        val selectedKeys: Set<Pair<String, String>> = batches
            .flatten()
            .map { it.selection.tableName to it.selection.documentId }
            .toSet()

        // Track success/failure of processed items
        val processedStatus = mutableMapOf<Pair<String, String>, Boolean>()

        for (batch in batches) {
            for (item in batch) {
                val selection = item.selection
                val entry = item.entry
                val tableName = selection.tableName
                val key = tableName to selection.documentId

                val schemaTable: DbTable = contentTypeMappingByTable[tableName] ?: continue

                // Determine if any dependency inside the selection previously failed
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
                    continue
                }

                when (entry.kind) {
                    StrapiContentTypeKind.SingleType -> {
                        val ok = processSingleType(
                            tableName,
                            schemaTable,
                            schema,
                            selection,
                            entry,
                            sourceStrapiInstance,
                            targetStrapiInstance,
                            targetClient,
                            comparisonDataMap,
                            // Exclude circular dependency links for this item
                            contentTypeMappingByTable,
                            circularEdges.filter { it.fromTable == tableName && it.fromDocumentId == selection.documentId }
                                .map { it.viaLink }
                                .toSet(),
                            allTargetFileIdByDoc,
                            mappingMap
                        )
                        processedStatus[key] = ok
                    }

                    StrapiContentTypeKind.CollectionType -> {
                        val ok = processCollectionType(
                            tableName,
                            schemaTable,
                            schema,
                            selection,
                            entry,
                            sourceStrapiInstance,
                            targetStrapiInstance,
                            targetClient,
                            comparisonDataMap,
                            contentTypeMappingByTable,
                            circularEdges.filter { it.fromTable == tableName && it.fromDocumentId == selection.documentId }
                                .map { it.viaLink }
                                .toSet(),
                            allTargetFileIdByDoc,
                            mappingMap
                        )
                        processedStatus[key] = ok
                    }

                    StrapiContentTypeKind.Files, StrapiContentTypeKind.Component -> {
                        // Files are handled separately in MergeRequestService; skip here.
                        continue
                    }
                }
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
                    val targetId = targetEntry?.metadata?.id
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

                    val targetId = entry?.targetContent?.metadata?.id
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
                val sourceData = sourceEntry.cleanData
                val fieldNameResolver = ContentJsonUtils.buildFieldNameResolver(contentTypeSchema)
                var dataToCreate = ContentJsonUtils.prepareDataForCreation(
                    sourceData,
                    contentTypeSchema,
                    dbSchema,
                    contentTypeMappingByTable
                )
                // Merge relations (including files) into the payload, excluding circular dependency links
                run {
                    val repeatableByKey: Map<String, Boolean> = (contentTypeSchema.metadata?.columns ?: emptyList())
                        .associate { ContentJsonUtils.normalizeKeyName(it.name) to it.repeatable }
                    buildRelationsDataFromLinks(
                        sourceEntry,
                        contentTypeSchema,
                        dbSchema,
                        comparisonDataMap,
                        fieldNameResolver,
                        excludeLinks,
                        allTargetFileIdByDoc,
                        contentTypeMappingByTable,
                        repeatableByKey,
                        mappingMap
                    )?.let { rel ->
                        dataToCreate = ContentJsonUtils.deepMergeJsonObjects(dataToCreate, rel)
                    }
                }
                try {
                    val response = targetClient.upsertContentEntry(
                        contentTypeSchema.metadata!!.queryName,
                        dataToCreate,
                        StrapiContentTypeKind.SingleType,
                        comparisonResult.targetContent?.metadata?.documentId
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
                val sourceData = sourceContent.cleanData
                val fieldNameResolver = ContentJsonUtils.buildFieldNameResolver(contentTypeSchema)
                var dataToCreate = ContentJsonUtils.prepareDataForCreation(
                    sourceData,
                    contentTypeSchema,
                    dbSchema,
                    contentTypeMappingByTable
                )
                // Merge relations (including files) into the payload, excluding circular dependency links
                run {
                    val repeatableByKey: Map<String, Boolean> = (contentTypeSchema.metadata?.columns ?: emptyList())
                        .associate { ContentJsonUtils.normalizeKeyName(it.name) to it.repeatable }
                    buildRelationsDataFromLinks(
                        sourceContent,
                        contentTypeSchema,
                        dbSchema,
                        comparisonDataMap,
                        fieldNameResolver,
                        excludeLinks,
                        allTargetFileIdByDoc,
                        contentTypeMappingByTable,
                        repeatableByKey
                    )?.let { rel ->
                        dataToCreate = ContentJsonUtils.deepMergeJsonObjects(dataToCreate, rel)
                    }
                }
                try {
                    val response = targetClient.upsertContentEntry(
                        contentTypeSchema.metadata!!.queryName,
                        dataToCreate,
                        StrapiContentTypeKind.CollectionType,
                        entry.targetContent?.metadata?.documentId
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

    private fun buildRelationsDataFromLinks(
        base: StrapiContent?,
        baseContentTypeSchema: DbTable,
        dbSchema: DbSchema,
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships,
        fieldNameResolver: Map<String, String>,
        excludeLinks: Set<StrapiLinkRef> = emptySet(),
        allTargetFileIdByDoc: Map<String, Int> = emptyMap(),
        contentTypeMappingByTable: Map<String, DbTable>,
        repeatableByKey: Map<String, Boolean> = emptyMap(),
        mappingMap: Map<String, Map<String, MergeRequestDocumentMapping>> = emptyMap()
    ): JsonObject? {
        if (base == null) return null
        if (base.links.isEmpty()) return null
        val effectiveLinks = base.links.filterNot { excludeLinks.contains(it) }
        if (effectiveLinks.isEmpty()) return null

        // Helper to resolve target value (documentId or numeric for files)
        fun resolveTargetValue(link: StrapiLinkRef): JsonElement? {
            return if (link.targetTable == "files") {

                val srcOrTgtDocId = resolveTargetDocIdForLink(
                    link.targetTable,
                    STRAPI_FILE_CONTENT_TYPE_NAME,
                    link.targetId,
                    comparisonDataMap,
                    mappingMap
                )
                val numericId = srcOrTgtDocId?.let { did ->
                    allTargetFileIdByDoc[did]
                        ?: comparisonDataMap.files.firstOrNull { f -> f.targetImage?.metadata?.documentId == did }?.targetImage?.metadata?.id
                }
                numericId?.let { JsonPrimitive(it) }
            } else {
                val table = link.targetTable
                contentTypeMappingByTable[table]?.metadata?.let { schemaLinkMeta ->
                    val targetDocId =
                        resolveTargetDocIdForLink(
                            link.targetTable,
                            schemaLinkMeta.apiUid,
                            link.targetId,
                            comparisonDataMap,
                            mappingMap
                        )
                    targetDocId?.let { JsonPrimitive(it) }
                }
            }
        }

        // Split links between repeatable root fields and non-repeatable
        val linksByFieldPath: Map<String, List<StrapiLinkRef>> = effectiveLinks.groupBy { it.field }
        val repeatableLinksByRoot: MutableMap<String, MutableList<StrapiLinkRef>> = mutableMapOf()
        val nonRepeatableFieldGroups: MutableMap<String, List<StrapiLinkRef>> = mutableMapOf()

        linksByFieldPath.forEach { (fieldPath, list) ->
            val rootKey = fieldPath.substringBefore('.')
            val isRepeatable = repeatableByKey[ContentJsonUtils.normalizeKeyName(rootKey)] == true
            if (isRepeatable) {
                repeatableLinksByRoot.getOrPut(rootKey) { mutableListOf() }.addAll(list)
            } else {
                nonRepeatableFieldGroups[fieldPath] = list
            }
        }

        var dataObj: JsonObject = buildJsonObject { }

        // 1) Handle repeatable component relations, preserving order and mapping by sourceId
        for ((rootKey, links) in repeatableLinksByRoot) {
            // Locate component schema for the repeatable rootKey
            val columnMeta = baseContentTypeSchema.metadata?.columns?.find { it.name == rootKey } ?: continue
            val componentName = columnMeta.component ?: continue
            val componentTableName = resolveComponentTableName(componentName, dbSchema) ?: continue
            val componentSchema = contentTypeMappingByTable[componentTableName] ?: continue
            val subFieldNameResolver = ContentJsonUtils.buildFieldNameResolver(componentSchema)

            // Build mapping from source component id -> index based on rawData (it contains component ids)
            val rawArray = base.rawData[rootKey] as? JsonArray ?: continue
            val idToIndex: Map<Int, Int> = rawArray.mapIndexedNotNull { index, el ->
                val id = (el as? JsonObject)?.get("id")?.toString()?.trim('"')?.toIntOrNull()
                if (id != null) id to index else null
            }.toMap()

            // Prepare a processed array starting from source clean data (so replacing the array won't lose fields)
            val processedItems: MutableList<JsonObject> = rawArray.map { el ->
                val obj = el as? JsonObject ?: buildJsonObject { }
                ContentJsonUtils.processJsonElement(obj, subFieldNameResolver) as JsonObject
            }.toMutableList()

            // Accumulate values per index and sub-field, preserving order by 'order' and then by 'id'
            data class OrderedVal(val ord: Double, val tie: Int, val value: JsonElement)

            val perIndex: MutableMap<Int, MutableMap<String, MutableList<OrderedVal>>> = mutableMapOf()

            links.forEach { link ->
                val subField = link.field.substringAfter('.', missingDelimiterValue = "")
                if (subField.isEmpty()) return@forEach
                val idx = idToIndex[link.sourceId] ?: return@forEach
                val value = resolveTargetValue(link) ?: return@forEach
                val resolvedSubFieldKey = subFieldNameResolver[ContentJsonUtils.normalizeKeyName(subField)]
                    ?: ContentJsonUtils.convertToCamelCase(subField)
                val ord = link.order ?: Double.MAX_VALUE
                val tie = link.id ?: Int.MAX_VALUE
                val m1 = perIndex.getOrPut(idx) { mutableMapOf() }
                val list = m1.getOrPut(resolvedSubFieldKey) { mutableListOf() }
                list.add(OrderedVal(ord, tie, value))
            }

            // Inject relation sets into the processed items
            perIndex.forEach { (idx, subMap) ->
                val baseObj = processedItems[idx].toMutableMap()
                subMap.forEach { (subKey, ovalues) ->
                    fun resolveKey(raw: String): String {
                        val normalized = normalizeKeyName(raw)
                        val normalizedPlural = normalized + "s"

                        return subFieldNameResolver[normalized] ?: subFieldNameResolver[normalizedPlural]
                        ?: fieldNameResolver[normalized] ?: fieldNameResolver[normalizedPlural]
                        ?: error("Cannot resolve key $raw")
                    }

                    val realKey = resolveKey(subKey)
                    val sortedValues = ovalues.sortedWith(compareBy<OrderedVal> { it.ord }.thenBy { it.tie })
                        .map { it.value }
                    if (sortedValues.isNotEmpty()) {
                        baseObj[realKey] = buildJsonObject { put("set", JsonArray(sortedValues)) }
                    }
                }
                processedItems[idx] = JsonObject(baseObj)
            }

            // Now build the nested object to merge at rootKey
            val resolvedRootKey = fieldNameResolver[ContentJsonUtils.normalizeKeyName(rootKey)] ?: rootKey
            val nested = buildJsonObject {
                val cleanObj = processedItems.map { o ->
                    buildJsonObject {
                        o.entries.filterNot { it.key == "id" }.forEach { (k, v) -> put(k, v) }
                    }
                }
                put(resolvedRootKey, JsonArray(cleanObj))
            }
            dataObj = ContentJsonUtils.deepMergeJsonObjects(dataObj, nested)
        }

        // 2) Handle non-repeatable fields as before
        for ((fieldPath, list) in nonRepeatableFieldGroups) {
            val componentName =
                baseContentTypeSchema.metadata?.columns?.find { fieldPath.startsWith(it.name) }?.component
            val component = resolveComponentTableName(componentName, dbSchema)
            val componentSchema = component?.let { contentTypeMappingByTable[it] }
            val subFieldNameResolver = componentSchema?.let { ContentJsonUtils.buildFieldNameResolver(it) }

            val values: List<JsonElement> = list
                .sortedWith(compareBy<StrapiLinkRef> { it.order ?: Double.MAX_VALUE }.thenBy { it.id ?: Int.MAX_VALUE })
                .mapNotNull { link -> resolveTargetValue(link) }

            if (values.isNotEmpty()) {
                val fieldObj = buildJsonObject { put("set", JsonArray(values)) }
                val nested = ContentJsonUtils.buildNestedObjectFromPath(
                    fieldPath,
                    fieldObj,
                    repeatableByKey,
                    fieldNameResolver,
                    subFieldNameResolver
                )
                dataObj = ContentJsonUtils.deepMergeJsonObjects(dataObj, nested)
            }
        }
        dataObj

        return if (dataObj.isEmpty()) null else dataObj
    }

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