package it.sebi.service

import io.ktor.server.config.*
import it.sebi.client.client
import it.sebi.database.dbQuery
import it.sebi.models.*
import it.sebi.repository.MergeRequestRepository
import it.sebi.repository.MergeRequestSelectionsRepository
import it.sebi.service.merge.ContentMergeProcessor
import it.sebi.service.merge.FileMergeProcessor
import it.sebi.tables.MergeRequestDocumentMappingTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import service.merge.MergeRequestServiceFileUtils

/**
 * Service for handling merge request operations
 */
enum class CompareMode { Full, Compare, Cache }

@Serializable
data class ComparisonPrefetchCache(
    val sourceFiles: List<StrapiImage>,
    val targetFiles: List<StrapiImage>,
    val sourceRowsByUid: Map<String, List<StrapiContent>>,
    val targetRowsByUid: Map<String, List<StrapiContent>>,
    val collectedRelationships: List<ContentRelationship>
)


class MergeRequestService(
    applicationConfig: ApplicationConfig,
    private val mergeRequestRepository: MergeRequestRepository,
    private val syncService: SyncService,
    private val mergeRequestSelectionsRepository: MergeRequestSelectionsRepository
) {
    private val fileMergeProcessor = FileMergeProcessor(mergeRequestSelectionsRepository)
    private val contentMergeProcessor = ContentMergeProcessor(mergeRequestSelectionsRepository)

    private val dataFileUtils = MergeRequestServiceFileUtils(applicationConfig)

    // Manual mappings list with optional filter by contentType UID, enriched with JSON for compare
    suspend fun getManualMappingsList(mergeRequestId: Int, contentTypeUid: String?): ManualMappingsListResponseDTO {
        val mr = mergeRequestRepository.getMergeRequestWithInstances(mergeRequestId)
            ?: throw IllegalArgumentException("Merge request not found")
        val rows = dbQuery {
            var predicate: Op<Boolean> = (MergeRequestDocumentMappingTable.sourceStrapiId eq mr.sourceInstance.id) and
                    (MergeRequestDocumentMappingTable.targetStrapiId eq mr.targetInstance.id)
            if (!contentTypeUid.isNullOrBlank()) {
                predicate = predicate and (MergeRequestDocumentMappingTable.contentType eq contentTypeUid)
            }
            MergeRequestDocumentMappingTable.selectAll().where { predicate }.toList()
        }
        val comparison = dataFileUtils.getContentComparisonFile(mergeRequestId)
        fun findJson(uid: String, docId: String?): JsonObject? {
            if (docId.isNullOrBlank() || comparison == null) return null
            if (uid == "files") {
                val f =
                    comparison.files.find { it.sourceImage?.metadata?.documentId == docId || it.targetImage?.metadata?.documentId == docId }
                val img = if (f?.sourceImage?.metadata?.documentId == docId) f?.sourceImage else f?.targetImage
                return img?.rawData
            }
            // singles
            comparison.singleTypes.values.forEach { e ->
                if (e.contentType == uid) {
                    if (e.sourceContent?.metadata?.documentId == docId) return e.sourceContent.cleanData
                    if (e.targetContent?.metadata?.documentId == docId) return e.targetContent.cleanData
                }
            }
            // collections
            comparison.collectionTypes.values.forEach { list ->
                list.forEach { e ->
                    if (e.contentType == uid) {
                        if (e.sourceContent?.metadata?.documentId == docId) return e.sourceContent.cleanData
                        if (e.targetContent?.metadata?.documentId == docId) return e.targetContent.cleanData
                    }
                }
            }
            return null
        }

        val data = rows.map { r ->
            val id = r[MergeRequestDocumentMappingTable.id].value
            val uid = r[MergeRequestDocumentMappingTable.contentType]
            val srcDoc = r[MergeRequestDocumentMappingTable.sourceDocumentId]
            val tgtDoc = r[MergeRequestDocumentMappingTable.targetDocumentId]
            val loc = r[MergeRequestDocumentMappingTable.locale]
            ManualMappingWithContentDTO(
                id = id,
                contentType = uid,
                sourceDocumentId = srcDoc,
                targetDocumentId = tgtDoc,
                locale = loc,
                sourceJson = findJson(uid, srcDoc),
                targetJson = findJson(uid, tgtDoc)
            )
        }
        return ManualMappingsListResponseDTO(success = true, data = data)
    }

    // Delete a single manual mapping and recompute comparison
    suspend fun deleteManualMapping(mergeRequestId: Int, mappingId: Int): MergeRequestData? {
        val mr = mergeRequestRepository.getMergeRequestWithInstances(mergeRequestId)
            ?: throw IllegalArgumentException("Merge request not found")
        // Verify the mapping belongs to this MR instances
        val belongs = dbQuery {
            val rows = MergeRequestDocumentMappingTable.selectAll().where {
                (MergeRequestDocumentMappingTable.id eq mappingId) and
                        (MergeRequestDocumentMappingTable.sourceStrapiId eq mr.sourceInstance.id) and
                        (MergeRequestDocumentMappingTable.targetStrapiId eq mr.targetInstance.id)
            }.toList()
            if (rows.isEmpty()) false else {
                // delete it
                MergeRequestDocumentMappingTable.deleteWhere { MergeRequestDocumentMappingTable.id eq mappingId } > 0
            }
        }
        if (!belongs) throw IllegalArgumentException("Mapping not found or does not belong to this merge request instances")
        // Recompute comparison (reuse prefetch cache if present)
        compareContent(mr, CompareMode.Compare)
        return getAllMergeRequestData(mergeRequestId, false)
    }

    // Manual mapping upsert for document associations (bulk)
    suspend fun upsertManualMappings(mergeRequestId: Int, items: List<ManualMappingItemDTO>): MergeRequestData? {
        val mr = mergeRequestRepository.getMergeRequestWithInstances(mergeRequestId)
            ?: throw IllegalArgumentException("Merge request not found")
        dbQuery {
            items.forEach { item ->
                val contentTypeUid = item.contentType
                val srcDoc = item.sourceDocumentId
                val srcId = item.sourceId
                val tgtDoc = item.targetDocumentId
                val tgtId = item.targetId
                val locale = item.locale
                if (srcDoc.isBlank() || tgtDoc.isBlank()) return@forEach
                // Build predicate: by src doc if provided, otherwise by target doc
                var predicate: Op<Boolean> =
                    (MergeRequestDocumentMappingTable.sourceStrapiId eq mr.sourceInstance.id) and
                            (MergeRequestDocumentMappingTable.targetStrapiId eq mr.targetInstance.id) and
                            (MergeRequestDocumentMappingTable.contentType eq contentTypeUid) and
                            (MergeRequestDocumentMappingTable.sourceDocumentId eq srcDoc) and
                            (MergeRequestDocumentMappingTable.targetDocumentId eq tgtDoc)



                predicate =
                    if (locale != null) predicate and (MergeRequestDocumentMappingTable.locale eq locale) else predicate and MergeRequestDocumentMappingTable.locale.isNull()
                val existing = MergeRequestDocumentMappingTable.selectAll().where { predicate }.toList()
                if (existing.isEmpty()) {
                    MergeRequestDocumentMappingTable.insert { st ->
                        st[MergeRequestDocumentMappingTable.sourceStrapiId] = mr.sourceInstance.id
                        st[MergeRequestDocumentMappingTable.targetStrapiId] = mr.targetInstance.id
                        st[MergeRequestDocumentMappingTable.contentType] = contentTypeUid
                        st[MergeRequestDocumentMappingTable.sourceDocumentId] = srcDoc
                        st[MergeRequestDocumentMappingTable.targetDocumentId] = tgtDoc
                        st[MergeRequestDocumentMappingTable.sourceId] = srcId
                        st[MergeRequestDocumentMappingTable.targetId] = tgtId
                        st[MergeRequestDocumentMappingTable.locale] = locale
                    }
                }
            }
        }
        // Recompute comparison (reuse prefetch cache if present)
        compareContent(mr, CompareMode.Compare)
        return getAllMergeRequestData(mergeRequestId, false)
    }

    // Load application configuration


    /**
     * Create a new merge request
     */
    suspend fun createMergeRequest(mergeRequestDTO: MergeRequestDTO): MergeRequest {
        return mergeRequestRepository.createMergeRequest(mergeRequestDTO)
    }


    /**
     * Get all merge requests with instance details
     * @param completed If true, return only completed merge requests. If false, return only incomplete merge requests. If null, return all merge requests.
     * @param sortBy Field to sort by (updatedAt, createdAt, name, status)
     * @param sortOrder Sort order (ASC or DESC)
     * @param page Page number (1-based)
     * @param pageSize Number of items per page
     * @return List of merge requests with instance details
     */
    suspend fun getMergeRequestsWithInstances(
        completed: Boolean? = null,
        sortBy: String = "updatedAt",
        sortOrder: String = "DESC",
        page: Int = 1,
        pageSize: Int = 10
    ): List<MergeRequestWithInstancesDTO> {
        return mergeRequestRepository.getMergeRequestsWithInstances(
            completed = completed,
            sortBy = sortBy,
            sortOrder = sortOrder,
            page = page,
            pageSize = pageSize
        )
    }


    /**
     * Get a specific merge request with instance details by ID
     */
    suspend fun getMergeRequestDetail(id: Int): MergeRequestDetail {
        val mergeRequest = mergeRequestRepository.getMergeRequestWithInstances(id)
            ?: throw IllegalArgumentException("Merge request not found")
        val mergeData = getAllMergeRequestDataIfCompared(id, false)

        return MergeRequestDetail(mergeRequest, mergeData)
    }

    /**
     * Update a merge request
     */
    suspend fun updateMergeRequest(id: Int, mergeRequestDTO: MergeRequestDTO): Boolean {
        val mr = mergeRequestRepository.getMergeRequest(id)
            ?: throw IllegalArgumentException("Merge request not found")
        if (mr.status == MergeRequestStatus.IN_PROGRESS || mr.status == MergeRequestStatus.COMPLETED || mr.status == MergeRequestStatus.FAILED) {
            throw IllegalStateException("Cannot update merge request after merge has been started or completed")
        }
        return mergeRequestRepository.updateMergeRequest(id, mergeRequestDTO)
    }

    /**
     * Delete a merge request and all its dependencies
     * @param id Merge request ID
     * @return true if the merge request was deleted successfully, false otherwise
     * @throws IllegalStateException if the merge request is completed
     */
    suspend fun deleteMergeRequest(id: Int): Boolean {
        // Check if merge request exists and is not completed
        val mergeRequest = mergeRequestRepository.getMergeRequest(id)
            ?: throw IllegalArgumentException("Merge request not found")

        if (mergeRequest.status == MergeRequestStatus.COMPLETED) {
            throw IllegalStateException("Cannot delete a completed merge request")
        }

        // Delete all selections for this merge request
        mergeRequestSelectionsRepository.deleteSelectionsForMergeRequest(id)

        // Note: Document mappings are associated with Strapi instances, not directly with merge requests
        // They are shared across merge requests, so we don't delete them here

        // Delete any files stored on disk for this merge request using IO dispatcher
        dataFileUtils.deleteFilesOfMergeRequest(id)

        // Finally, delete the merge request itself
        return mergeRequestRepository.deleteMergeRequest(id)
    }

    /**
     * Get selections for a merge request
     */
    suspend fun getMergeRequestSelections(id: Int): MergeRequestSelectionDataDTO {
        // Check if merge request exists
        mergeRequestRepository.getMergeRequest(id)
            ?: throw IllegalArgumentException("Merge request not found")

        return MergeRequestSelectionDataDTO(mergeRequestSelectionsRepository.getSelectionsGroupedByTableName(id))
    }


    data class Resolved(val contentTypeUid: String, val documentId: String, val direction: Direction)


    fun resolveDeps(
        comparison: ContentTypeComparisonResultMapWithRelationships,
        linkRef: StrapiLinkRef,
        visited: MutableSet<Pair<String, String>> = mutableSetOf()
    ): List<Resolved> {
        val deps =
            comparison.singleTypes[linkRef.targetTable]?.let { listOf(it) }
                ?: comparison.collectionTypes[linkRef.targetTable]
                ?: comparison.files.map { it.asContent }
        return deps.filter { it.sourceContent?.metadata?.id?.toString() == (linkRef.targetId?.toString() ?: "") }
            .flatMap { d ->
                resolveFromEntry(comparison, d, visited)
            }
    }

    fun resolveFromEntry(
        comparison: ContentTypeComparisonResultMapWithRelationships,
        e: ContentTypeComparisonResultWithRelationships,
        visited: MutableSet<Pair<String, String>> = mutableSetOf()
    ): List<Resolved> {
        var checkLinks = false
        val dir: Direction = when (e.compareState) {
            ContentTypeComparisonResultKind.ONLY_IN_SOURCE -> {
                checkLinks = true
                Direction.TO_CREATE
            }

            ContentTypeComparisonResultKind.ONLY_IN_TARGET -> Direction.TO_DELETE
            ContentTypeComparisonResultKind.DIFFERENT -> {
                checkLinks = true
                Direction.TO_UPDATE
            }

            ContentTypeComparisonResultKind.IDENTICAL -> return emptyList()
        }
        val doc = when (e.compareState) {
            ContentTypeComparisonResultKind.ONLY_IN_SOURCE -> e.sourceContent?.metadata?.documentId
            ContentTypeComparisonResultKind.ONLY_IN_TARGET -> e.targetContent?.metadata?.documentId
            ContentTypeComparisonResultKind.DIFFERENT -> e.sourceContent?.metadata?.documentId
            ContentTypeComparisonResultKind.IDENTICAL -> null
        } ?: return emptyList()

        val key = e.tableName to doc
        if (visited.contains(key)) {
            return emptyList()
        }
        visited.add(key)

        if (checkLinks) {
            val links = e.sourceContent?.links ?: emptyList()

            return listOf(Resolved(e.tableName, doc, dir)) + links.flatMap { l ->
                resolveDeps(comparison, l, visited)
            }
        }

        return listOf(Resolved(e.tableName, doc, dir))
    }

    /**
     * Unified selection processing: supports single, list, and all entries using comparison ids
     */
    suspend fun processUnifiedSelection(id: Int, req: UnifiedSelectionDTO): SelectionUpdateResponseDTO {
        // Check if merge request exists and is not locked
        val mr = mergeRequestRepository.getMergeRequest(id)
            ?: throw IllegalArgumentException("Merge request not found")
        if (mr.status == MergeRequestStatus.IN_PROGRESS || mr.status == MergeRequestStatus.COMPLETED || mr.status == MergeRequestStatus.FAILED) {
            throw IllegalStateException("Cannot modify selections after merge has been started or completed")
        }

        val comparison: ContentTypeComparisonResultMapWithRelationships = dataFileUtils.getContentComparisonFile(id)
            ?: throw IllegalStateException("Comparison not available. Run compare before updating selections.")

        val allEntries: List<ContentTypeComparisonResultWithRelationships> = when (req.kind) {
            StrapiContentTypeKind.SingleType -> if (req.tableName == null) {
                comparison.singleTypes.values.toList()
            } else {
                listOfNotNull(comparison.singleTypes[req.tableName])
            }

            StrapiContentTypeKind.CollectionType -> if (req.tableName == null) {
                comparison.collectionTypes.values.flatten()

            } else {
                comparison.collectionTypes[req.tableName] ?: emptyList()
            }

            StrapiContentTypeKind.Files -> {
                comparison.files.map { it.asContent }

            }

            StrapiContentTypeKind.Component -> {
                listOf()
            }
        }


        val targets: List<Resolved> = when {
            req.selectAllKind != null -> allEntries.filter { it.compareState == req.selectAllKind }
                .flatMap { resolveFromEntry(comparison, it) }

            !req.ids.isNullOrEmpty() -> {
                val idsSet = req.ids.toSet()
                allEntries.filter { idsSet.contains(it.id) }.flatMap { resolveFromEntry(comparison, it) }
            }

            else -> emptyList()
        }


        if (req.isSelected) {
            val upserts = targets.map { Triple(it.contentTypeUid, it.documentId, it.direction) }
            mergeRequestSelectionsRepository.bulkUpsertAndDelete(id, upserts, emptyList())

        } else {
            val deletes = targets.map { Triple(it.contentTypeUid, it.documentId, it.direction) }
            mergeRequestSelectionsRepository.bulkUpsertAndDelete(id, emptyList(), deletes)
        }

        return SelectionUpdateResponseDTO(
            success = true,
        )
    }


    // ----------------------
    // Dependency planning for content sync
    // ----------------------

    private data class NodeKey(val table: String, val documentId: String)

    data class MissingDependency(
        val fromTable: String,
        val fromDocumentId: String,
        val link: StrapiLinkRef,
        val reason: String
    )

    data class CircularDependencyEdge(
        val fromTable: String,
        val fromDocumentId: String,
        val toTable: String,
        val toDocumentId: String,
        val viaLink: StrapiLinkRef
    )

    data class DependencyEdge(
        val fromTable: String,
        val fromDocumentId: String,
        val toTable: String,
        val toDocumentId: String,
        val viaLink: StrapiLinkRef
    )

    data class SyncOrderItem(
        val selection: MergeRequestSelection,
        val entry: ContentTypeComparisonResultWithRelationships
    )

    data class SyncOrderResult(
        val batches: List<List<SyncOrderItem>>, // layered order with selections
        val missingDependencies: List<MissingDependency>,
        val circularEdges: List<CircularDependencyEdge>,
        val edges: List<DependencyEdge>
    )

    private fun indexByTableAndDoc(
        comparison: ContentTypeComparisonResultMapWithRelationships
    ): Map<NodeKey, ContentTypeComparisonResultWithRelationships> {
        val map = mutableMapOf<NodeKey, ContentTypeComparisonResultWithRelationships>()
        comparison.singleTypes.values.forEach { e ->
            e.sourceContent?.metadata?.documentId?.let { map[NodeKey(e.tableName, it)] = e }
            e.targetContent?.metadata?.documentId?.let { map.putIfAbsent(NodeKey(e.tableName, it), e) }
        }
        comparison.collectionTypes.forEach { (_, list) ->
            list.forEach { e ->
                e.sourceContent?.metadata?.documentId?.let { map[NodeKey(e.tableName, it)] = e }
                e.targetContent?.metadata?.documentId?.let { map.putIfAbsent(NodeKey(e.tableName, it), e) }
            }
        }
        // also index files as content
        comparison.files.forEach { f ->
            f.sourceImage?.metadata?.documentId?.let {
                map[NodeKey("files", it)] = f.asContent
            }
            f.targetImage?.metadata?.documentId?.let {
                map.putIfAbsent(NodeKey("files", it), f.asContent)
            }
        }
        return map
    }

    private fun resolveDocIdForLinkInternal(
        table: String,
        id: Int?,
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships
    ): String? {
        if (id == null) return null
        return when (table) {
            "files" -> {
                comparisonDataMap.files.forEach { cmp ->
                    val s = cmp.sourceImage?.metadata
                    if (s?.id == id) return s.documentId
                    val t = cmp.targetImage?.metadata
                    if (t?.id == id) return t.documentId
                }
                null
            }

            else -> {
                comparisonDataMap.singleTypes[table]?.let { st ->
                    if (st.sourceContent?.metadata?.id == id) return st.sourceContent.metadata.documentId
                    if (st.targetContent?.metadata?.id == id) return st.targetContent.metadata.documentId
                }
                comparisonDataMap.collectionTypes[table]?.forEach { e ->
                    if (e.sourceContent?.metadata?.id == id) return e.sourceContent.metadata.documentId
                    if (e.targetContent?.metadata?.id == id) return e.targetContent.metadata.documentId
                }
                null
            }
        }
    }

    /**
     * Calcola l'ordine di sincronizzazione dei contenuti selezionati in base alle dipendenze tra le entità.
     * - Prima i contenuti senza dipendenze non soddisfatte
     * - Poi quelli cuì tutte le dipendenze risultano risolte progressivamente
     * Considera inoltre:
     * - File già sincronizzati/presenti (tramite allTargetMap)
     * - Entità target già presenti dedotte dalla comparison
     * - Segnala dipendenze mancanti e dipendenze circolari
     */
    fun computeContentSyncOrder(
        contentSelections: List<MergeRequestSelection>,
        comparison: ContentTypeComparisonResultMapWithRelationships,
        allTargetMap: Map<String, Int>
    ): SyncOrderResult {
        // Build index for quick lookup
        val index = indexByTableAndDoc(comparison)

        // Selected nodes (ignore deletions for dependency ordering)
        val selectedNodes: Map<NodeKey, Pair<ContentTypeComparisonResultWithRelationships, MergeRequestSelection>> =
            contentSelections
                .filter { it.direction != Direction.TO_DELETE }
                .mapNotNull { sel ->
                    val key = NodeKey(sel.tableName, sel.documentId)
                    val entry = index[key]
                    if (entry != null) key to (entry to sel) else null
                }
                .toMap()

        // Helper to check if a dependency is already satisfied by target existing data
        fun isSatisfiedOutsideSelection(depKey: NodeKey): Boolean {
            if (depKey.table == "files") {
                if (allTargetMap.containsKey(depKey.documentId)) return true
                // also consider already present in target comparison
                return comparison.files.any { it.targetImage?.metadata?.documentId == depKey.documentId }
            }
            // For non-file content types, check presence in target
            val entry = index[depKey]
            return entry?.targetContent != null
        }

        // Build dependency graph among selected items
        val adjacency = mutableMapOf<NodeKey, MutableList<Pair<NodeKey, StrapiLinkRef>>>() // from -> list of (to, link)
        val inDegree = mutableMapOf<NodeKey, Int>().apply { selectedNodes.keys.forEach { this[it] = 0 } }
        val missing = mutableListOf<MissingDependency>()

        selectedNodes.forEach { (fromKey, pair) ->
            val (entry, _) = pair
            val sourceLinks = entry.sourceContent?.links ?: emptyList()
            for (link in sourceLinks) {
                val targetDocId = resolveDocIdForLinkInternal(link.targetTable, link.targetId, comparison)
                if (targetDocId == null) {
                    // cannot even resolve the referenced documentId
                    missing += MissingDependency(
                        fromKey.table,
                        fromKey.documentId,
                        link,
                        "Referenced entity not found in comparison"
                    )
                    continue
                }
                val depKey = NodeKey(link.targetTable, targetDocId)
                if (selectedNodes.containsKey(depKey)) {
                    // dependency inside selection -> create edge dep -> from
                    adjacency.getOrPut(depKey) { mutableListOf() }.add(fromKey to link)
                    inDegree[fromKey] = (inDegree[fromKey] ?: 0) + 1
                } else if (isSatisfiedOutsideSelection(depKey)) {
                    // dependency already satisfied -> ignore
                } else {
                    // missing dependency
                    missing += MissingDependency(
                        fromKey.table,
                        fromKey.documentId,
                        link,
                        "Dependency not selected and not present in target"
                    )
                }
            }
        }

        // Kahn's algorithm with layering (batches)
        val batches = mutableListOf<List<SyncOrderItem>>()
        val queue: ArrayDeque<NodeKey> = ArrayDeque(inDegree.filter { it.value == 0 }.keys)
        val processed = mutableSetOf<NodeKey>()

        while (queue.isNotEmpty()) {
            val layerSize = queue.size
            val layer = mutableListOf<SyncOrderItem>()
            repeat(layerSize) {
                val n = queue.removeFirst()
                if (!processed.add(n)) return@repeat
                selectedNodes[n]?.let { pair ->
                    val (entry, selection) = pair
                    layer += SyncOrderItem(selection = selection, entry = entry)
                }
                adjacency[n]?.forEach { (toKey, _) ->
                    inDegree[toKey] = (inDegree[toKey] ?: 0) - 1
                    if ((inDegree[toKey] ?: 0) == 0) {
                        queue.addLast(toKey)
                    }
                }
            }
            if (layer.isNotEmpty()) batches += layer
        }

        // Remaining nodes with inDegree > 0 are part of cycles
        val remaining = inDegree.filter { (k, _) -> !processed.contains(k) }.keys
        val circularEdges = mutableListOf<CircularDependencyEdge>()
        if (remaining.isNotEmpty()) {
            remaining.forEach { dep ->
                adjacency[dep]?.forEach { (toKey, link) ->
                    if (remaining.contains(toKey)) {
                        circularEdges += CircularDependencyEdge(
                            fromTable = dep.table,
                            fromDocumentId = dep.documentId,
                            toTable = toKey.table,
                            toDocumentId = toKey.documentId,
                            viaLink = link
                        )
                    }
                }
            }
        }

        // Build edge list for visualization (dependencies between selected items)
        val edges: List<DependencyEdge> = adjacency.flatMap { (fromKey, tos) ->
            tos.map { (toKey, link) ->
                DependencyEdge(
                    fromTable = fromKey.table,
                    fromDocumentId = fromKey.documentId,
                    toTable = toKey.table,
                    toDocumentId = toKey.documentId,
                    viaLink = link
                )
            }
        }

        return SyncOrderResult(
            batches = batches,
            missingDependencies = missing,
            circularEdges = circularEdges,
            edges = edges
        )
    }


    private val logger = LoggerFactory.getLogger(this::class.java)


    /**
     * Get the file path for storing content comparison results for a merge request
     */


    private val TECH_FIELDS_FOR_CLEAN = setOf(
        "id", "created_by_id", "updated_by_id", "created_at", "updated_at", "published_at"
    )

    private fun cleanValueForContent(v: JsonElement): JsonElement = when (v) {
        is JsonObject -> JsonObject(v.filterKeys { it !in TECH_FIELDS_FOR_CLEAN }
            .mapValues { (_, vv) -> cleanValueForContent(vv) })

        is JsonArray -> JsonArray(v.map { cleanValueForContent(it) })
        else -> v
    }


    private fun toCache(prefetch: ComparisonPrefetch): ComparisonPrefetchCache = ComparisonPrefetchCache(
        sourceFiles = prefetch.sourceFiles,
        targetFiles = prefetch.targetFiles,
        sourceRowsByUid = prefetch.sourceRowsByUid,
        targetRowsByUid = prefetch.targetRowsByUid,
        collectedRelationships = prefetch.collectedRelationships.toList()
    )

    private fun fromCache(cache: ComparisonPrefetchCache): ComparisonPrefetch = ComparisonPrefetch(
        sourceFiles = cache.sourceFiles,
        targetFiles = cache.targetFiles,
        sourceFileRelations = emptyList(),
        targetFileRelations = emptyList(),
        sourceCMPSMap = mutableMapOf(),
        targetCMPSMap = mutableMapOf(),
        sourceTableMap = mutableMapOf(),
        targetTableMap = mutableMapOf(),
        srcCompDef = mutableMapOf(),
        tgtCompDef = mutableMapOf(),
        sourceRowsByUid = cache.sourceRowsByUid,
        targetRowsByUid = cache.targetRowsByUid,
        collectedRelationships = cache.collectedRelationships.toSet()
    )


    suspend fun checkSchemaCompatibility(
        id: Int,
        force: Boolean = false
    ): SchemaDbCompatibilityResult {
        val mergeRequest = mergeRequestRepository.getMergeRequestWithInstances(id)
            ?: throw IllegalArgumentException("Merge request not found")
        if (mergeRequest.status == MergeRequestStatus.IN_PROGRESS || mergeRequest.status == MergeRequestStatus.COMPLETED || mergeRequest.status == MergeRequestStatus.FAILED) {
            throw IllegalStateException("Cannot check schema after merge has been started or completed")
        }
        return checkSchemaCompatibility(mergeRequest, force)
    }


    /**
     * Check schema compatibility for a merge request
     * @param force If true, force a new check even if results already exist
     * @return true if schemas are compatible, false otherwise
     */
    private suspend fun checkSchemaCompatibility(
        mergeRequest: MergeRequestWithInstancesDTO,
        force: Boolean = false
    ): SchemaDbCompatibilityResult {

        if (mergeRequest.status == MergeRequestStatus.IN_PROGRESS || mergeRequest.status == MergeRequestStatus.COMPLETED || mergeRequest.status == MergeRequestStatus.FAILED) {
            throw IllegalStateException("Cannot check schema after merge has been started or completed")
        }

        if (!force)
            dataFileUtils.getSchemaCompatibilityFile(mergeRequest.id)?.let { return it }
        val dbRes: SchemaDbCompatibilityResult = syncService.checkSchemaCompatibilityDb(
            sourceInstance = mergeRequest.sourceInstance,
            targetInstance = mergeRequest.targetInstance
        )
        dbRes


        // Update the merge request with the schema compatibility result and sourceContentTypes if compatible
        // This will reset the status to SCHEMA_CHECKED, effectively resetting all subsequent steps
        mergeRequestRepository.updateSchemaCompatibility(mergeRequest.id)

        // Save the result to a file
        dataFileUtils.saveSchemaCompatibilityFile(mergeRequest.id, dbRes)

        return dbRes
    }

    /**
     * Compare content for a merge request
     * @param id Merge request ID
     * @param force If true, force a new comparison even if results already exist
     * @return Map of content type UID to comparison results
     */
    suspend fun compareContent(
        id: Int,
        mode: CompareMode
    ): ContentTypeComparisonResultMapWithRelationships {
        val mergeRequest = mergeRequestRepository.getMergeRequestWithInstances(id)
            ?: throw IllegalArgumentException("Merge request not found")
        if (mergeRequest.status == MergeRequestStatus.IN_PROGRESS || mergeRequest.status == MergeRequestStatus.COMPLETED || mergeRequest.status == MergeRequestStatus.FAILED) {
            throw IllegalStateException("Cannot compare content after merge has been started or completed")
        }
        return compareContent(mergeRequest, mode)
    }

    /**
     * Compare content for a merge request
     * @param mergeRequest Merge request with instances
     * @param force If true, force a new comparison even if results already exist
     * @return Map of content type UID to comparison results
     */
    private suspend fun compareContent(
        mergeRequest: MergeRequestWithInstancesDTO,
        mode: CompareMode
    ): ContentTypeComparisonResultMapWithRelationships {
        if (mergeRequest.status == MergeRequestStatus.COMPLETED || mergeRequest.status == MergeRequestStatus.IN_PROGRESS || mergeRequest.status == MergeRequestStatus.FAILED) {
            throw IllegalStateException("Cannot compare content after merge has been started or completed")
        }

        val schemas = checkSchemaCompatibility(mergeRequest)

        if (!schemas.isCompatible) {
            throw IllegalStateException("Source and target instances are not compatible")
        }

        suspend fun computeAndPersist(prefetch: ComparisonPrefetch): ContentTypeComparisonResultMapWithRelationships {
            val comp = computeComparisonFromPrefetch(mergeRequest, schemas.extractedSchema!!, prefetch)
            mergeRequestRepository.updateComparisonData(mergeRequest.id)
            dataFileUtils.saveContentComparisonFile(mergeRequest.id, comp)
            return comp
        }

        when (mode) {
            CompareMode.Cache -> {
                // Return file result if present
                dataFileUtils.getContentComparisonFile(mergeRequest.id)?.let { return it }
                // Try compute from cached prefetch
                val cached = dataFileUtils.getPrefetchCache(mergeRequest.id)
                if (cached != null) {
                    val pre = fromCache(cached)
                    return computeAndPersist(pre)
                }
                // Fallback to Full
            }

            else -> {}
        }

        return when (mode) {
            CompareMode.Full -> {
                val prefetch = prefetchComparisonData(mergeRequest, schemas.extractedSchema!!)
                dataFileUtils.savePrefetchCache(mergeRequest.id, toCache(prefetch))
                computeAndPersist(prefetch)
            }

            CompareMode.Compare, CompareMode.Cache -> {
                val cached = dataFileUtils.getPrefetchCache(mergeRequest.id)
                val prefetch = if (cached != null) fromCache(cached) else prefetchComparisonData(
                    mergeRequest,
                    schemas.extractedSchema!!
                ).also {
                    dataFileUtils.savePrefetchCache(mergeRequest.id, toCache(it))
                }
                computeAndPersist(prefetch)
            }
        }
    }


    // The methods getMergeRequestFiles and getMergeRequestCollectionContentTypes have been removed
    // All data is now provided by the getAllMergeRequestData method

    /**
     * Get all data for a merge request after the compare step
     * This includes files, single types, collection types, and selections
     * @param id Merge request ID
     * @return MergeRequestData containing all the necessary information
     */
    suspend fun getAllMergeRequestData(id: Int, forceCompare: Boolean): MergeRequestData? {
        return getAllMergeRequestDataIfCompared(id, forceCompare)
    }

    private suspend fun getAllMergeRequestDataIfCompared(id: Int, forceCompare: Boolean): MergeRequestData? {
        // Use a single transaction for all database operations to prevent locks
        return dbQuery {
            val mergeRequest = mergeRequestRepository.getMergeRequestWithInstances(id)
                ?: throw IllegalArgumentException("Merge request not found")

            // Get comparison data from file if available, otherwise compute it
            var compareResult = dataFileUtils.getContentComparisonFile(id)
            if (compareResult == null && forceCompare)
                compareResult = compareContent(mergeRequest, CompareMode.Compare)

            // Get selections
            val selections = mergeRequestSelectionsRepository.getSelectionsGroupedByTableName(id)

            // Return all data in a single object
            if (compareResult == null) {
                return@dbQuery null
            }
            MergeRequestData(
                files = compareResult.files.map { cmp ->
                    val newSource = cmp.sourceImage?.let { img ->
                        img.copy(metadata = img.metadata.copy(url = img.downloadUrl(mergeRequest.sourceInstance.url)))
                    }
                    val newTarget = cmp.targetImage?.let { img ->
                        img.copy(metadata = img.metadata.copy(url = img.downloadUrl(mergeRequest.targetInstance.url)))
                    }
                    cmp.copy(sourceImage = newSource, targetImage = newTarget)
                },
                singleTypes = compareResult.singleTypes,
                collectionTypes = compareResult.collectionTypes,
                contentTypeRelationships = compareResult.contentTypeRelationships,
                selections = selections
            )
        }
    }

    // The methods getMergeRequestSingleContentType and getMergeRequestFiles have been removed
    // All data is now provided by the getAllMergeRequestData method


    /**
     * Complete a merge request
     * @param id Merge request ID
     * @return true if the merge request was completed successfully, false otherwise
     */
    suspend fun completeMergeRequest(id: Int): Boolean {
        // 1. Retrieve the merge request
        val mergeRequest = mergeRequestRepository.getMergeRequestWithInstances(id)
            ?: throw IllegalArgumentException("Merge request not found")

        val comparisonDataMap = dataFileUtils.getContentComparisonFile(id)
            ?: throw IllegalStateException("Comparison data not found")

        // Mark as started to lock further changes
        mergeRequestRepository.updateMergeRequestStatus(id, MergeRequestStatus.IN_PROGRESS)

        // Create clients for source and target instances
        val sourceClient = mergeRequest.sourceInstance.client()
        val targetClient = mergeRequest.targetInstance.client()

        // Set error logging context on clients for this merge request
        sourceClient.role = "source"
        targetClient.role = "target"
        sourceClient.currentMergeRequestId = id
        targetClient.currentMergeRequestId = id
        sourceClient.dataRootFolder = dataFileUtils.dataFolder
        targetClient.dataRootFolder = dataFileUtils.dataFolder

        val schema = dataFileUtils.getSchemaCompatibilityFile(mergeRequest.id)!!.extractedSchema!!

        val mergeRequestSelection = mergeRequestSelectionsRepository.getSelectionsForMergeRequest(id)
        val mappingMap: MutableMap<String, MutableMap<String, MergeRequestDocumentMapping>> =
            MergeRequestDocumentMappingTable.fetchMappingMap(mergeRequest)
                .mapValues { (_, v) -> v.toMutableMap() }
                .toMutableMap()

        // 2. Retrieve files related to the merge request from the table
        val (mergeRequestFiles, contentSelections) = mergeRequestSelection.partition { it.tableName == "files" }

        // Emit initial SSE event
        try {
            val totalItems = mergeRequestSelection.size
            it.sebi.SyncProgressService.sendProgressUpdate(
                it.sebi.SyncProgressUpdate(
                    mergeRequestId = id,
                    totalItems = totalItems,
                    processedItems = mergeRequestSelection.count { it.syncDate != null },
                    currentItem = "Starting synchronization",
                    currentItemType = "SYSTEM",
                    currentOperation = "START",
                    status = "INFO",
                    message = null
                )
            )
        } catch (_: Exception) { /* ignore */
        }

        try {
            // 3. Process files if there are any
            val targetFileMap: MutableMap<String, Int> =
                comparisonDataMap.files.mapNotNull { f -> f.targetImage?.metadata?.documentId?.let { it to f.targetImage.metadata.id } }
                    .toMap().toMutableMap()
            val processedFiles: MutableMap<String, Int> =
                mergeRequestFiles.fold(mutableMapOf<String, Int>()) { acc, file ->
                    // Process the file
                    val fileList = listOf(file)
                    acc.apply {
                        putAll(
                            fileMergeProcessor.processFiles(
                                sourceClient,
                                targetClient,
                                mergeRequest.sourceInstance,
                                mergeRequest.targetInstance,
                                fileList,
                                comparisonDataMap.files
                            )
                        )
                    }
                }

            val allTargetMap = targetFileMap + processedFiles

            // Calcola l'ordine di sincronizzazione dei contenuti in base alle dipendenze
            val syncOrderResult: SyncOrderResult = computeContentSyncOrder(
                contentSelections = contentSelections,
                comparison = comparisonDataMap,
                allTargetMap = allTargetMap
            )

            // 5. Process content in dependency-resolved batches (create/update only)
            if (contentSelections.isNotEmpty()) {
                // First, process batches computed from dependencies
                contentMergeProcessor.processBatches(
                    mergeRequest.sourceInstance,
                    mergeRequest.targetInstance,
                    targetClient,
                    syncOrderResult.batches,
                    comparisonDataMap,
                    schema,
                    syncOrderResult.circularEdges,
                    allTargetMap,
                    mappingMap
                )

                // Then, process deletions (not part of dependency graph)
                val deletionsOnly = contentSelections.filter { it.direction == Direction.TO_DELETE }
                if (deletionsOnly.isNotEmpty()) {
                    contentMergeProcessor.processDeletions(
                        mergeRequest.targetInstance,
                        targetClient,
                        deletionsOnly,
                        comparisonDataMap,
                        schema
                    )
                }
            }

            // 5b. Second pass: complete circular relations where dependencies succeeded
            if (syncOrderResult.circularEdges.isNotEmpty()) {
                val itemsWithCircular = syncOrderResult.batches.flatten().filter { item ->
                    syncOrderResult.circularEdges.any { it.fromTable == item.selection.tableName && it.fromDocumentId == item.selection.documentId }
                }
                if (itemsWithCircular.isNotEmpty()) {
                    val latestSelections = mergeRequestSelectionsRepository.getSelectionsForMergeRequest(id)
                    val statusByKey: Map<Pair<String, String>, Boolean> =
                        latestSelections.associate { (it.tableName to it.documentId) to (it.syncSuccess == true) }
                    contentMergeProcessor.processCircularSecondPass(
                        mergeRequest.targetInstance,
                        targetClient,
                        itemsWithCircular,
                        comparisonDataMap,
                        schema,
                        syncOrderResult.circularEdges,
                        allTargetMap,
                        statusByKey,
                        mappingMap
                    )
                }
            }

            // 6. Update the merge request status: COMPLETED if all succeeded, otherwise FAILED (remain locked)
            val latestSelections = mergeRequestSelectionsRepository.getSelectionsForMergeRequest(id)
            val allSucceeded = latestSelections.isEmpty() || latestSelections.all { it.syncSuccess == true }
            val result = if (allSucceeded) {
                mergeRequestRepository.updateMergeRequestStatus(id, MergeRequestStatus.COMPLETED)
            } else {
                mergeRequestRepository.updateMergeRequestStatus(id, MergeRequestStatus.FAILED)
            }

            // Emit final SSE event
            try {
                it.sebi.SyncProgressService.sendProgressUpdate(
                    it.sebi.SyncProgressUpdate(
                        mergeRequestId = id,
                        totalItems = latestSelections.size,
                        processedItems = latestSelections.count { it.syncDate != null },
                        currentItem = if (allSucceeded) "Synchronization completed" else "Content types processed",
                        currentItemType = "SYSTEM",
                        currentOperation = if (allSucceeded) "COMPLETED" else "PROCESSED",
                        status = if (allSucceeded) "SUCCESS" else "INFO",
                        message = null
                    )
                )
            } catch (_: Exception) { /* ignore */
            }

            return result
        } catch (e: Exception) {
            // Send final error update
            try {
                val currentSelections = mergeRequestSelectionsRepository.getSelectionsForMergeRequest(id)
                it.sebi.SyncProgressService.sendProgressUpdate(
                    it.sebi.SyncProgressUpdate(
                        mergeRequestId = id,
                        totalItems = currentSelections.size,
                        processedItems = currentSelections.count { it.syncDate != null },
                        currentItem = "Synchronization failed",
                        currentItemType = "SYSTEM",
                        currentOperation = "FAILED",
                        status = "ERROR",
                        message = e.message
                    )
                )
            } catch (_: Exception) { /* ignore */
            }
            // Mark as FAILED to keep it locked
            try {
                mergeRequestRepository.updateMergeRequestStatus(id, MergeRequestStatus.FAILED)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    // ----------------------
    // Sync Plan DTOs and API
    // ----------------------
    @Serializable
    data class SyncPlanItemDTO(
        val tableName: String,
        val documentId: String,
        val direction: Direction
    )

    @Serializable
    data class MissingDependencyDTO(
        val fromTable: String,
        val fromDocumentId: String,
        val linkField: String,
        val linkTargetTable: String,
        val reason: String
    )

    @Serializable
    data class CircularDependencyEdgeDTO(
        val fromTable: String,
        val fromDocumentId: String,
        val toTable: String,
        val toDocumentId: String,
        val viaField: String
    )

    @Serializable
    data class DependencyEdgeDTO(
        val fromTable: String,
        val fromDocumentId: String,
        val toTable: String,
        val toDocumentId: String,
        val viaField: String
    )

    @Serializable
    data class SyncPlanDTO(
        val batches: List<List<SyncPlanItemDTO>>,
        val missingDependencies: List<MissingDependencyDTO>,
        val circularEdges: List<CircularDependencyEdgeDTO>,
        val edges: List<DependencyEdgeDTO>
    )

    suspend fun getSyncPlan(id: Int): SyncPlanDTO {
        val mergeRequest = mergeRequestRepository.getMergeRequestWithInstances(id)
            ?: throw IllegalArgumentException("Merge request not found")
        val comparisonDataMap = dataFileUtils.getContentComparisonFile(id)
            ?: throw IllegalStateException("Comparison data not found. Run compare before requesting plan.")
        val selections = mergeRequestSelectionsRepository.getSelectionsForMergeRequest(id)
        // Split files from content selections
        val (mergeRequestFiles, contentSelections) = selections.partition { it.tableName == "files" }
        // Build map of files already in target
        val targetFileMap: Map<String, Int> =
            comparisonDataMap.files.mapNotNull { f ->
                f.targetImage?.metadata?.documentId?.let { it to (f.targetImage.metadata.id) }
            }.toMap() + comparisonDataMap.files.mapNotNull { f ->
                f.sourceImage?.metadata?.documentId?.let { it to (f.sourceImage.metadata.id) }
            }.toMap()
        // Consider also files selected to be created/updated as satisfied for planning purposes
        val pendingFileDocIds: Set<String> = mergeRequestFiles
            .filter { it.direction != Direction.TO_DELETE }
            .map { it.documentId }
            .toSet()
        val augmentedAllTargetMap: MutableMap<String, Int> = targetFileMap.toMutableMap()
        pendingFileDocIds.forEach { docId ->
            augmentedAllTargetMap.putIfAbsent(docId, -1)
        }
        val order = computeContentSyncOrder(
            contentSelections = contentSelections,
            comparison = comparisonDataMap,
            allTargetMap = augmentedAllTargetMap
        )
        // Filter out false-positive missing deps for files satisfied by selected files or target presence
        val filteredMissing = order.missingDependencies.filterNot { m ->
            if (m.link.targetTable == "files") {
                val depDocId = resolveDocIdForLinkInternal("files", m.link.targetId, comparisonDataMap)
                depDocId != null && (
                        augmentedAllTargetMap.containsKey(depDocId) ||
                                comparisonDataMap.files.any { it.targetImage?.metadata?.documentId == depDocId }
                        )
            } else false
        }
        val batchesDtoContent: List<List<SyncPlanItemDTO>> = order.batches.map { batch ->
            batch.map { item ->
                SyncPlanItemDTO(
                    tableName = item.selection.tableName,
                    documentId = item.selection.documentId,
                    direction = item.selection.direction
                )
            }
        }
        // Prepend files as the first batch, since they are synchronized first during completion
        val fileBatchDto: List<SyncPlanItemDTO> = mergeRequestFiles.map { f ->
            SyncPlanItemDTO(
                tableName = f.tableName,
                documentId = f.documentId,
                direction = f.direction
            )
        }
        val batchesDto: List<List<SyncPlanItemDTO>> =
            if (fileBatchDto.isNotEmpty()) listOf(fileBatchDto) + batchesDtoContent else batchesDtoContent

        val missingDto = filteredMissing.map { m ->
            MissingDependencyDTO(
                fromTable = m.fromTable,
                fromDocumentId = m.fromDocumentId,
                linkField = m.link.field,
                linkTargetTable = m.link.targetTable,
                reason = m.reason
            )
        }
        val circularDto = order.circularEdges.map { c ->
            CircularDependencyEdgeDTO(
                fromTable = c.fromTable,
                fromDocumentId = c.fromDocumentId,
                toTable = c.toTable,
                toDocumentId = c.toDocumentId,
                viaField = c.viaLink.field
            )
        }
        val edgesDto = order.edges.map { e ->
            DependencyEdgeDTO(
                fromTable = e.fromTable,
                fromDocumentId = e.fromDocumentId,
                toTable = e.toTable,
                toDocumentId = e.toDocumentId,
                viaField = e.viaLink.field
            )
        }
        return SyncPlanDTO(
            batches = batchesDto,
            missingDependencies = missingDto,
            circularEdges = circularDto,
            edges = edgesDto
        )
    }

}
