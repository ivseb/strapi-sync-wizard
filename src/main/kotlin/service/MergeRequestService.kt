package it.sebi.service

import io.ktor.server.config.*
import it.sebi.JsonParser
import it.sebi.client.StrapiClient
import it.sebi.client.client
import it.sebi.database.dbQuery
import it.sebi.models.*
import it.sebi.repository.MergeRequestDocumentMappingRepository
import it.sebi.repository.MergeRequestRepository
import it.sebi.repository.MergeRequestSelectionsRepository
import it.sebi.utils.calculateMD5Hash
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.io.File
import java.time.OffsetDateTime

/**
 * Service for handling merge request operations
 */
class MergeRequestService(
    applicationConfig: ApplicationConfig,
    private val mergeRequestRepository: MergeRequestRepository,
    private val syncService: SyncService,
    private val mergeRequestDocumentMappingRepository: MergeRequestDocumentMappingRepository,
    private val mergeRequestSelectionsRepository: MergeRequestSelectionsRepository
) {
    // Load application configuration
    private val dataFolder = applicationConfig.property("application.dataFolder").getString()

    init {
        // Create data folder if it doesn't exist
        val folder = File(dataFolder)
        if (!folder.exists()) {
            folder.mkdirs()
        }
    }

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

        // Delete any files stored on disk for this merge request
        val mergeRequestFolder = File(dataFolder, "merge_request_$id")
        if (mergeRequestFolder.exists()) {
            mergeRequestFolder.deleteRecursively()
        }

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

        return MergeRequestSelectionDataDTO(mergeRequestSelectionsRepository.getSelectionsGroupedByContentType(id))
    }


    /**
     * Update a single selection for a merge request
     * @param id Merge request ID
     * @param contentType Content type
     * @param documentId Entry ID
     * @param direction Direction (TO_CREATE, TO_UPDATE, TO_DELETE)
     * @param isSelected Whether the entry is selected or not
     * @return SelectionUpdateResponseDTO containing success status and any additional selections made
     */
    suspend fun updateSingleSelection(
        id: Int,
        contentType: String,
        documentId: String,
        direction: Direction,
        isSelected: Boolean
    ): SelectionUpdateResponseDTO {
        // Check if merge request exists
        mergeRequestRepository.getMergeRequest(id)
            ?: throw IllegalArgumentException("Merge request not found")

        // Update the selection for the main entry
        val result = mergeRequestSelectionsRepository.updateSingleSelection(
            id,
            contentType,
            documentId,
            direction,
            isSelected
        )

        // List to store additional selections made
        val additionalSelections = mutableListOf<RelatedSelectionDTO>()

        // If we're selecting an entry (not deselecting) and the update was successful,
        // also select any related entries that aren't already selected
        if (isSelected && result) {
            // Get the content comparison data to access relationships
            val contentComparison = getContentComparisonFile(id)

            // Find relationships for this entry
            val relationships = findRelationshipsForEntry(contentComparison, contentType, documentId)

            // For each relationship, select the related entry if it isn't already selected
            for (relationship in relationships) {

                val directionMapping = when (relationship.compareStatus) {
                    ContentTypeComparisonResultKind.ONLY_IN_SOURCE -> Direction.TO_CREATE
                    ContentTypeComparisonResultKind.ONLY_IN_TARGET -> Direction.TO_DELETE
                    ContentTypeComparisonResultKind.DIFFERENT -> Direction.TO_UPDATE
                    else -> continue
                }
                // Check if the related entry is already selected
                val isAlreadySelected = mergeRequestSelectionsRepository.getSelectionsForMergeRequest(id)
                    .any {
                        it.contentType == relationship.targetContentType &&
                                it.documentId == relationship.targetDocumentId &&
                                it.direction == directionMapping
                    }

                // If not already selected, select it
                if (!isAlreadySelected) {
                    val selectionResult = mergeRequestSelectionsRepository.updateSingleSelection(
                        id,
                        relationship.targetContentType,
                        relationship.targetDocumentId,
                        directionMapping,
                        true
                    )

                    // If selection was successful, add to the list of additional selections
                    if (selectionResult) {
                        additionalSelections.add(
                            RelatedSelectionDTO(
                                contentType = relationship.targetContentType,
                                documentId = relationship.targetDocumentId,
                                direction = directionMapping
                            )
                        )
                    }
                }
            }
        }

        return SelectionUpdateResponseDTO(
            success = result,
            additionalSelections = additionalSelections
        )
    }

    /**
     * Find relationships for a specific entry
     * @param contentComparison The content comparison data
     * @param contentType The content type of the entry
     * @param documentId The document ID of the entry
     * @return List of relationships for the entry
     */
    private fun findRelationshipsForEntry(
        contentComparison: ContentTypeComparisonResultMapWithRelationships?,
        contentType: String,
        documentId: String
    ): List<EntryRelationship> {
        if (contentComparison == null) return emptyList()

        // Check in single types
        contentComparison.singleTypes[contentType]?.let { singleType ->
            if (singleType.relationships.isNotEmpty()) {
                return singleType.relationships.filter {
                    it.sourceContentType == contentType && it.sourceDocumentId == documentId
                }
            }
        }

        // Check in collection types
        contentComparison.collectionTypes[contentType]?.let { collectionType ->
            collectionType.relationships[documentId]?.let { relationships ->
                return relationships.filter {
                    it.sourceContentType == contentType && it.sourceDocumentId == documentId
                }
            }
        }

        // Check for relationships with files
        // For single types
        contentComparison.singleTypes.values.forEach { singleType ->
            val fileRelationships = singleType.relationships.filter {
                it.sourceContentType == contentType &&
                        it.sourceDocumentId == documentId &&
                        it.targetContentType == STRAPI_FILE_CONTENT_TYPE_NAME
            }
            if (fileRelationships.isNotEmpty()) {
                return fileRelationships
            }
        }

        // For collection types
        contentComparison.collectionTypes.values.forEach { collectionType ->
            collectionType.relationships.values.forEach { relationships ->
                val fileRelationships = relationships.filter {
                    it.sourceContentType == contentType &&
                            it.sourceDocumentId == documentId &&
                            it.targetContentType == STRAPI_FILE_CONTENT_TYPE_NAME
                }
                if (fileRelationships.isNotEmpty()) {
                    return fileRelationships
                }
            }
        }

        return emptyList()
    }


    /**
     * Get the file path for storing schema compatibility results for a merge request
     */
    private fun getSchemaCompatibilityFilePath(id: Int): String {
        val mergeRequestFolder = File(dataFolder, "merge_request_$id")
        if (!mergeRequestFolder.exists()) {
            mergeRequestFolder.mkdirs()
        }
        return File(mergeRequestFolder, "schema_compatibility.json").absolutePath
    }


    private fun getSchemaCompatibilityFile(id: Int): SchemaCompatibilityResult? {
        val schemaFilePath = getSchemaCompatibilityFilePath(id)
        val schemaFile = File(schemaFilePath)

        // Check if file exists and force is false
        if (schemaFile.exists()) {
            try {
                // Read the file and deserialize
                val fileContent = schemaFile.readText()
                val fileStorage = JsonParser.decodeFromString<SchemaCompatibilityResult>(fileContent)
                return fileStorage
            } catch (e: Exception) {
                // If there's an error reading the file, log it and continue with a new check
                println("Error reading schema compatibility file: ${e.message}")
                // Continue with a new check

            }
        }
        return null
    }

    private fun saveSchemaCompatibilityFile(id: Int, schemaResult: SchemaCompatibilityResult) {
        val schemaFilePath = getSchemaCompatibilityFilePath(id)
        val schemaResultFile = File(schemaFilePath)
        val jsonContent = JsonParser.encodeToString(schemaResult)
        schemaResultFile.writeText(jsonContent)
    }


    /**
     * Get the file path for storing content comparison results for a merge request
     */
    private fun getContentComparisonFilePath(id: Int): String {
        val mergeRequestFolder = File(dataFolder, "merge_request_$id")
        if (!mergeRequestFolder.exists()) {
            mergeRequestFolder.mkdirs()
        }
        return File(mergeRequestFolder, "content_comparison.json").absolutePath
    }

    private fun getContentComparisonFile(id: Int): ContentTypeComparisonResultMapWithRelationships? {
        val schemaFilePath = getContentComparisonFilePath(id)
        val schemaFile = File(schemaFilePath)

        if (schemaFile.exists()) {
            // Read the file and deserialize
            val fileContent = schemaFile.readText()
            val fileStorage = JsonParser.decodeFromString<ContentTypeComparisonResultMapWithRelationships>(fileContent)
            return fileStorage

        }
        return null
    }

    private fun saveContentComparisonFile(id: Int, contentComparison: ContentTypeComparisonResultMapWithRelationships) {
        val schemaFilePath = getContentComparisonFilePath(id)
        val schemaResultFile = File(schemaFilePath)
        val jsonContent = JsonParser.encodeToString(contentComparison)
        schemaResultFile.writeText(jsonContent)
    }


    suspend fun checkSchemaCompatibility(
        id: Int,
        force: Boolean = false
    ): SchemaCompatibilityResult {
        val mergeRequest = mergeRequestRepository.getMergeRequestWithInstances(id)
            ?: throw IllegalArgumentException("Merge request not found")

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
    ): SchemaCompatibilityResult {


        if (!force)
            getSchemaCompatibilityFile(mergeRequest.id)?.let { return it }

        // Perform the schema compatibility check
        val schemaResult = syncService.checkSchemaCompatibility(
            mergeRequest.sourceInstance,
            mergeRequest.targetInstance
        )


        // Update the merge request with the schema compatibility result and sourceContentTypes if compatible
        // This will reset the status to SCHEMA_CHECKED, effectively resetting all subsequent steps
        mergeRequestRepository.updateSchemaCompatibility(mergeRequest.id)

        // Save the result to a file
        saveSchemaCompatibilityFile(mergeRequest.id, schemaResult)

        return schemaResult
    }

    /**
     * Compare content for a merge request
     * @param id Merge request ID
     * @param force If true, force a new comparison even if results already exist
     * @return Map of content type UID to comparison results
     */
    suspend fun compareContent(
        id: Int,
        force: Boolean = false
    ): ContentTypeComparisonResultMapWithRelationships {
        val mergeRequest = mergeRequestRepository.getMergeRequestWithInstances(id)
            ?: throw IllegalArgumentException("Merge request not found")

        return compareContent(mergeRequest, force)
    }

    /**
     * Compare content for a merge request
     * @param mergeRequest Merge request with instances
     * @param force If true, force a new comparison even if results already exist
     * @return Map of content type UID to comparison results
     */
    private suspend fun compareContent(
        mergeRequest: MergeRequestWithInstancesDTO,
        force: Boolean = false
    ): ContentTypeComparisonResultMapWithRelationships {
        if (mergeRequest.status == MergeRequestStatus.COMPLETED) {
            throw IllegalStateException("Merge request has already been completed")
        }

        val schemas = checkSchemaCompatibility(mergeRequest)

        if (!schemas.isCompatible) {
            throw IllegalStateException("Source and target instances are not compatible")
        }

        if (!force)
            getContentComparisonFile(mergeRequest.id)?.let { return it }


        // Perform the content comparison
        val comparisonResults = syncService.compareContentWithRelationships(
            mergeRequest,
            schemas.sourceContentTypes,
            schemas.sourceComponents
        )

        // Update the merge request with the comparison data and reset subsequent steps
        mergeRequestRepository.updateComparisonData(mergeRequest.id)

        saveContentComparisonFile(mergeRequest.id, comparisonResults)

        return comparisonResults
    }


    suspend fun getAllMergeRequestData(id: Int): MergeRequestData {
        return getAllMergeRequestDataIfCompared(id, true) ?: throw IllegalStateException("Merge request not completed")
    }

    // The methods getMergeRequestFiles and getMergeRequestCollectionContentTypes have been removed
    // All data is now provided by the getAllMergeRequestData method

    /**
     * Get all data for a merge request after the compare step
     * This includes files, single types, collection types, and selections
     * @param id Merge request ID
     * @return MergeRequestData containing all the necessary information
     */
    private suspend fun getAllMergeRequestDataIfCompared(id: Int, forceCompare: Boolean): MergeRequestData? {
        // Use a single transaction for all database operations to prevent locks
        return dbQuery {
            val mergeRequest = mergeRequestRepository.getMergeRequestWithInstances(id)
                ?: throw IllegalArgumentException("Merge request not found")

            // Get comparison data from file if available, otherwise compute it
            var compareResult = getContentComparisonFile(id)
            if (compareResult == null && forceCompare)
                compareResult = compareContent(mergeRequest, false)

            // Get selections
            val selections = mergeRequestSelectionsRepository.getSelectionsGroupedByContentType(id)

            // Return all data in a single object
            if (compareResult == null) {
                return@dbQuery null
            }
            MergeRequestData(
                files = compareResult.files.let { files ->
                    files.copy(
                        onlyInSource = files.onlyInSource.map {
                            it.copy(
                                metadata = it.metadata.copy(
                                    url = it.downloadUrl(
                                        mergeRequest.sourceInstance.url
                                    )
                                )
                            )
                        },
                        onlyInTarget = files.onlyInTarget.map {
                            it.copy(
                                metadata = it.metadata.copy(
                                    url = it.downloadUrl(
                                        mergeRequest.targetInstance.url
                                    )
                                )
                            )
                        },
                        different = files.different.map {
                            it.copy(
                                source = it.source.copy(
                                    metadata = it.source.metadata.copy(
                                        url = it.source.downloadUrl(
                                            mergeRequest.sourceInstance.url
                                        )
                                    )
                                ),
                                target = it.target.copy(
                                    metadata = it.target.metadata.copy(
                                        url = it.target.downloadUrl(
                                            mergeRequest.targetInstance.url
                                        )
                                    )
                                )
                            )
                        },
                        identical = files.identical.map {
                            it.copy(
                                metadata = it.metadata.copy(
                                    url = it.downloadUrl(
                                        mergeRequest.sourceInstance.url
                                    )
                                )
                            )
                        }
                    )
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

        val comparisonDataMap = getContentComparisonFile(id)
            ?: throw IllegalStateException("Comparison data not found")

        // Create clients for source and target instances
        val sourceClient = mergeRequest.sourceInstance.client()
        val targetClient = mergeRequest.targetInstance.client()

        val mergeRequestSelection = mergeRequestSelectionsRepository.getSelectionsForMergeRequest(id)

        // 2. Retrieve files related to the merge request from the table
        val (mergeRequestFiles, contentSelections) = mergeRequestSelection.partition { it.contentType == STRAPI_FILE_CONTENT_TYPE_NAME }

        // 3. Process files if there are any
        if (mergeRequestFiles.isNotEmpty()) {
            val comparisonData = comparisonDataMap.files
            mergeRequestFileMergeProcess(mergeRequest, sourceClient, targetClient, mergeRequestFiles, comparisonData)
        }

        // 4. Retrieve content selections from the database
        val groupedSelections = contentSelections.groupBy { it.contentType }

        // 5. Process singleTypes and collectionTypes
        if (groupedSelections.isNotEmpty()) {
            mergeRequestContentMergeProcess(
                mergeRequest,
                sourceClient,
                targetClient,
                groupedSelections,
                comparisonDataMap
            )
        }

        // 6. Update the merge request status
        return mergeRequestRepository.updateMergeRequestStatus(id, MergeRequestStatus.COMPLETED)
    }

    data class ContentMapping(
        val targetDocumentId: String,
        val targetId: Int
    )

    private suspend fun mergeRequestContentMergeProcess(
        mergeRequest: MergeRequestWithInstancesDTO,
        sourceClient: StrapiClient,
        targetClient: StrapiClient,
        groupedSelections: Map<String, List<MergeRequestSelection>>,
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships
    ) {
        val mergeRequestSelectionsRepository = MergeRequestSelectionsRepository()
        // Store mappings between source and target IDs for all processed content types

        val schema = getSchemaCompatibilityFile(mergeRequest.id)!!.sourceContentTypes

        val contentTypeMappingByUid: Map<String, Schema> = schema.associate { it.uid to it.schema }

        val idMappings =
            mutableMapOf<String, MutableMap<String, ContentMapping>>() // contentType -> sourceDocumentId -> targetDocumentId

        // Get existing mappings from the database
        val existingMappings = mergeRequestDocumentMappingRepository.getFilesAllMappingsForInstances(
            mergeRequest.sourceInstance.id,
            mergeRequest.targetInstance.id,
        )


        groupedSelections.forEach { (contentType, selections) ->
            if (comparisonDataMap.singleTypes.containsKey(contentType)) {
                comparisonDataMap.singleTypes[contentType]?.relationships?.flatMap { relationship ->
                    selections.mapNotNull { selection ->
                        if (selection.documentId == relationship.sourceDocumentId) {
                            existingMappings.find { m -> m.sourceDocumentId == relationship.targetDocumentId }
                                ?.let { it.sourceDocumentId!! to ContentMapping(it.targetDocumentId!!, it.targetId!!) }
                        } else null
                    }
                }?.toMap()?.let { idMappings.put(contentType, it.toMutableMap()) }
            } else {
                if (comparisonDataMap.collectionTypes.containsKey(contentType)) {
                    selections.mapNotNull { selection ->
                        comparisonDataMap.collectionTypes[contentType]?.relationships?.get(selection.documentId)
                            ?.mapNotNull { relationship ->

                                if (selection.documentId == relationship.sourceDocumentId) {
                                    existingMappings.find { m -> m.sourceDocumentId == relationship.targetDocumentId }
                                        ?.let {
                                            it.sourceDocumentId!! to ContentMapping(
                                                it.targetDocumentId!!,
                                                it.targetId!!
                                            )
                                        }
                                } else null
                            }

                    }.flatten().toMap().let { idMappings.put(contentType, it.toMutableMap()) }
                }

            }
        }


        // Process singleTypes first
        val singleTypeSelections = groupedSelections.filter { entry ->
            comparisonDataMap.singleTypes.containsKey(entry.key)
        }


        // Process collectionTypes next
        val collectionTypeSelections = groupedSelections.filter { entry ->
            comparisonDataMap.collectionTypes.containsKey(entry.key)
        }

        // Sort content types based on dependencies to ensure they are processed in the correct order
        val sortedContentTypes = sortContentTypesByDependencies(
            singleTypeSelections.keys.toList(),
            collectionTypeSelections.keys.toList(),
            comparisonDataMap
        )

        // First pass: Create or update entries without relationships
        for (contentType in sortedContentTypes) {
            val selections = groupedSelections[contentType] ?: continue

            if (comparisonDataMap.singleTypes.containsKey(contentType)) {
                // Process singleType
                processSingleType(
                    contentType,
                    contentTypeMappingByUid[contentType]!!,
                    selections,
                    comparisonDataMap.singleTypes[contentType]!!,
                    sourceClient,
                    targetClient,
                    mergeRequest,
                    idMappings,
                    firstPass = true
                )
            } else if (comparisonDataMap.collectionTypes.containsKey(contentType)) {
                // Process collectionType
                processCollectionType(
                    contentType,
                    contentTypeMappingByUid[contentType]!!,
                    selections,
                    comparisonDataMap.collectionTypes[contentType]!!,
                    sourceClient,
                    targetClient,
                    mergeRequest,
                    idMappings,
                    firstPass = true
                )
            }
        }

        // Second pass: Update entries with relationships
        for (contentType in sortedContentTypes) {
            val selections = groupedSelections[contentType] ?: continue

            if (comparisonDataMap.singleTypes.containsKey(contentType)) {
                // Process singleType
                processSingleType(
                    contentType,
                    contentTypeMappingByUid[contentType]!!,
                    selections,
                    comparisonDataMap.singleTypes[contentType]!!,
                    sourceClient,
                    targetClient,
                    mergeRequest,
                    idMappings,
                    firstPass = false
                )
            } else if (comparisonDataMap.collectionTypes.containsKey(contentType)) {
                // Process collectionType
                processCollectionType(
                    contentType,
                    contentTypeMappingByUid[contentType]!!,
                    selections,
                    comparisonDataMap.collectionTypes[contentType]!!,
                    sourceClient,
                    targetClient,
                    mergeRequest,
                    idMappings,
                    firstPass = false
                )
            }
        }
    }

    private fun sortContentTypesByDependencies(
        singleTypes: List<String>,
        collectionTypes: List<String>,
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships
    ): List<String> {
        val contentTypes = (singleTypes + collectionTypes).toMutableList()
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        // Helper function to get dependencies for a content type
        fun getDependencies(contentType: String): List<String> {
            return when {
                comparisonDataMap.singleTypes.containsKey(contentType) ->
                    comparisonDataMap.singleTypes[contentType]?.dependsOn ?: emptyList()

                comparisonDataMap.collectionTypes.containsKey(contentType) ->
                    comparisonDataMap.collectionTypes[contentType]?.dependsOn ?: emptyList()

                else -> emptyList()
            }
        }

        // Topological sort using depth-first search
        fun visit(contentType: String) {
            if (contentType in visited) return
            visited.add(contentType)

            val dependencies = getDependencies(contentType)
            for (dependency in dependencies) {
                if (dependency in contentTypes && dependency !in visited) {
                    visit(dependency)
                }
            }

            result.add(contentType)
        }

        // Visit all content types
        for (contentType in contentTypes) {
            if (contentType !in visited) {
                visit(contentType)
            }
        }

        return result
    }

    private suspend fun processSingleType(
        contentTypeUid: String,
        contentTypeSchema: Schema,
        selections: List<MergeRequestSelection>,
        comparisonResult: ContentTypeComparisonResultWithRelationships,
        sourceClient: StrapiClient,
        targetClient: StrapiClient,
        mergeRequest: MergeRequestWithInstancesDTO,
        idMappings: MutableMap<String, MutableMap<String, ContentMapping>>,
        firstPass: Boolean
    ) {
        val mergeRequestSelectionsRepository = MergeRequestSelectionsRepository()
        // Single types only have one entry, so we only need to check if there's a selection
        val selection = selections.firstOrNull() ?: return

        when (selection.direction) {
            Direction.TO_CREATE -> {
                // Create the entry in the target
                val sourceEntry = comparisonResult.onlyInSource ?: return
                val sourceData = sourceEntry.rawData

                // Prepare data for creation by removing relationships in first pass
                val dataToCreate = prepareDataForCreation(
                    sourceData,
                    idMappings,
                    firstPass
                )

                try {
                    val response = targetClient.createContentEntry(
                        contentTypeSchema.queryName,
                        dataToCreate,
                        StrapiContentTypeKind.SingleType
                    )

                    // Extract the created entry's ID and documentId
                    val data = response["data"]?.jsonObject
                    val targetId = data?.get("id")?.toString()?.toInt() ?: 0
                    val targetDocumentId = data?.get("documentId")?.toString()?.replace("\"", "") ?: ""

                    // Store the mapping
                    idMappings.getOrPut(contentTypeUid) { mutableMapOf() }[sourceEntry.metadata.documentId] =
                        ContentMapping(targetDocumentId, targetId)

                    // Create or update the mapping in the database
                    val existingMapping = mergeRequestDocumentMappingRepository.getFilesMappingsForInstances(
                        mergeRequest.sourceInstance.id,
                        mergeRequest.targetInstance.id,
                        listOf(sourceEntry.metadata.documentId),
                        emptyList()
                    ).firstOrNull()

                    if (existingMapping == null) {
                        // Create new mapping
                        val newMapping = MergeRequestDocumentMapping(
                            sourceId = sourceEntry.metadata.id,
                            contentType = contentTypeUid,
                            sourceStrapiInstanceId = mergeRequest.sourceInstance.id,
                            targetStrapiInstanceId = mergeRequest.targetInstance.id,
                            sourceDocumentId = sourceEntry.metadata.documentId,
                            sourceLastUpdateDate = OffsetDateTime.now(),
                            sourceDocumentMD5 = "",
                            targetId = targetId,
                            targetDocumentId = targetDocumentId,
                            targetLastUpdateDate = OffsetDateTime.now(),
                            targetDocumentMD5 = ""
                        )
                        mergeRequestDocumentMappingRepository.createMapping(newMapping)
                    } else {
                        // Update existing mapping
                        val updatedMapping = existingMapping.copy(
                            sourceLastUpdateDate = OffsetDateTime.now(),
                            contentType = contentTypeUid,
                            targetId = targetId,
                            targetDocumentId = targetDocumentId,
                            targetLastUpdateDate = OffsetDateTime.now()
                        )
                        mergeRequestDocumentMappingRepository.updateMapping(existingMapping.id, updatedMapping)
                    }

                    // Update sync status to success
                    mergeRequestSelectionsRepository.updateSyncStatus(
                        selection.id,
                        true,
                        null
                    )
                } catch (e: Exception) {
                    println("Error creating single type $contentTypeUid: ${e.message}")

                    // Update sync status to failure
                    mergeRequestSelectionsRepository.updateSyncStatus(
                        selection.id,
                        false,
                        e.message ?: "Unknown error"
                    )
                }
            }

            Direction.TO_UPDATE -> {
                // Update the entry in the target
                val differentEntry = comparisonResult.different ?: return
                val sourceData = differentEntry.source.rawData

                // Prepare data for update by removing relationships in first pass
                val dataToUpdate = prepareDataForCreation(
                    sourceData,
                    idMappings,
                    firstPass
                )

                try {
                    val response = targetClient.updateContentEntry(
                        contentTypeSchema.queryName,
                        differentEntry.target.metadata.id.toString(),
                        dataToUpdate,
                        StrapiContentTypeKind.SingleType
                    )

                    // Extract the updated entry's ID and documentId
                    val data = response["data"]?.jsonObject
                    val targetId = data?.get("id")?.toString()?.toInt() ?: 0
                    val targetDocumentId = data?.get("documentId")?.toString()?.replace("\"", "") ?: ""

                    // Store the mapping
                    idMappings.getOrPut(contentTypeUid) { mutableMapOf() }[differentEntry.source.metadata.documentId] =
                        ContentMapping(targetDocumentId, targetId)

                    // Update the mapping in the database
                    val existingMapping = mergeRequestDocumentMappingRepository.getFilesMappingsForInstances(
                        mergeRequest.sourceInstance.id,
                        mergeRequest.targetInstance.id,
                        listOf(differentEntry.source.metadata.documentId),
                        emptyList()
                    ).firstOrNull()

                    if (existingMapping != null) {
                        // Update existing mapping
                        val updatedMapping = existingMapping.copy(
                            sourceLastUpdateDate = OffsetDateTime.now(),
                            targetId = targetId,
                            targetDocumentId = targetDocumentId,
                            targetLastUpdateDate = OffsetDateTime.now()
                        )
                        mergeRequestDocumentMappingRepository.updateMapping(existingMapping.id, updatedMapping)
                    }

                    // Update sync status to success
                    mergeRequestSelectionsRepository.updateSyncStatus(
                        selection.id,
                        true,
                        null
                    )
                } catch (e: Exception) {
                    println("Error updating single type $contentTypeUid: ${e.message}")

                    // Update sync status to failure
                    mergeRequestSelectionsRepository.updateSyncStatus(
                        selection.id,
                        false,
                        e.message ?: "Unknown error"
                    )
                }
            }

            Direction.TO_DELETE -> {
                // Delete the entry in the target
                val targetEntry = comparisonResult.onlyInTarget ?: return

                try {
                    targetClient.deleteContentEntry(
                        contentTypeSchema.queryName,
                        targetEntry.metadata.id.toString(),
                        "singleType"
                    )

                    // Delete the mapping from the database
                    mergeRequestDocumentMappingRepository.deleteFilesMappingsForInstancesByTarget(
                        mergeRequest.sourceInstance.id,
                        mergeRequest.targetInstance.id,
                        targetEntry.metadata.documentId
                    )

                    // Update sync status to success
                    mergeRequestSelectionsRepository.updateSyncStatus(
                        selection.id,
                        true,
                        null
                    )
                } catch (e: Exception) {
                    println("Error deleting single type $contentTypeUid: ${e.message}")

                    // Update sync status to failure
                    mergeRequestSelectionsRepository.updateSyncStatus(
                        selection.id,
                        false,
                        e.message ?: "Unknown error"
                    )
                }
            }
        }
    }

    private suspend fun processCollectionType(
        contentType: String,
        contentTypeSchema: Schema,
        selections: List<MergeRequestSelection>,
        comparisonResult: ContentTypesComparisonResultWithRelationships,
        sourceClient: StrapiClient,
        targetClient: StrapiClient,
        mergeRequest: MergeRequestWithInstancesDTO,
        idMappings: MutableMap<String, MutableMap<String, ContentMapping>>,
        firstPass: Boolean
    ) {
        val mergeRequestSelectionsRepository = MergeRequestSelectionsRepository()
        // Group selections by direction
        val toCreate = selections.filter { idMappings[contentType]?.get(it.documentId) == null }
        val toUpdate = selections.filter { idMappings[contentType]?.get(it.documentId) != null }
        val toDelete = selections.filter { it.direction == Direction.TO_DELETE }

        // Process entries to create
        for (selection in toCreate) {
            val sourceEntry =
                comparisonResult.onlyInSource.find { it.metadata.documentId == selection.documentId } ?: continue
            val sourceData = sourceEntry.rawData

            // Prepare data for creation by removing relationships in first pass
            val dataToCreate = prepareDataForCreation(
                sourceData,
                idMappings,
                firstPass
            )

            try {
                val response = targetClient.createContentEntry(
                    contentTypeSchema.queryName,
                    dataToCreate,
                    StrapiContentTypeKind.CollectionType
                )

                // Extract the created entry's ID and documentId
                val data = response["data"]?.jsonObject
                val targetId = data?.get("id")?.toString()?.toInt() ?: 0
                val targetDocumentId = data?.get("documentId")?.toString()?.replace("\"", "") ?: ""

                // Store the mapping
                idMappings.getOrPut(contentType) { mutableMapOf() }[sourceEntry.metadata.documentId] =
                    ContentMapping(targetDocumentId, targetId)

                // Create or update the mapping in the database
                val existingMapping = mergeRequestDocumentMappingRepository.getFilesMappingsForInstances(
                    mergeRequest.sourceInstance.id,
                    mergeRequest.targetInstance.id,
                    listOf(sourceEntry.metadata.documentId),
                    emptyList()
                ).firstOrNull()

                if (existingMapping == null) {
                    // Create new mapping
                    val newMapping = MergeRequestDocumentMapping(
                        sourceId = sourceEntry.metadata.id,
                        contentType = contentType,
                        sourceStrapiInstanceId = mergeRequest.sourceInstance.id,
                        targetStrapiInstanceId = mergeRequest.targetInstance.id,
                        sourceDocumentId = sourceEntry.metadata.documentId,
                        sourceLastUpdateDate = OffsetDateTime.now(),
                        sourceDocumentMD5 = "",
                        targetId = targetId,
                        targetDocumentId = targetDocumentId,
                        targetLastUpdateDate = OffsetDateTime.now(),
                        targetDocumentMD5 = ""
                    )
                    mergeRequestDocumentMappingRepository.createMapping(newMapping)
                } else {
                    // Update existing mapping
                    val updatedMapping = existingMapping.copy(
                        sourceLastUpdateDate = OffsetDateTime.now(),
                        targetId = targetId,
                        targetDocumentId = targetDocumentId,
                        targetLastUpdateDate = OffsetDateTime.now()
                    )
                    mergeRequestDocumentMappingRepository.updateMapping(existingMapping.id, updatedMapping)
                }

                // Update sync status to success
                mergeRequestSelectionsRepository.updateSyncStatus(
                    selection.id,
                    true,
                    null
                )
            } catch (e: Exception) {
                println("Error creating collection type entry $contentType: ${e.message}")

                // Update sync status to failure
                mergeRequestSelectionsRepository.updateSyncStatus(
                    selection.id,
                    false,
                    e.message ?: "Unknown error"
                )
            }
        }

        // Process entries to update
        for (selection in toUpdate) {
            val differentEntry =
                comparisonResult.different.find { it.source.metadata.documentId == selection.documentId }?.source
                    ?: comparisonResult.onlyInSource.find { it.metadata.documentId == selection.documentId } ?: continue
            val sourceData = differentEntry.rawData

            // Prepare data for update by removing relationships in first pass
            val dataToUpdate = prepareDataForCreation(
                sourceData,
                idMappings,
                firstPass
            )
            val mapping = idMappings[contentType]?.get(differentEntry.metadata.documentId) ?: continue


            try {
                val response = targetClient.updateContentEntry(
                    contentTypeSchema.queryName,
                    mapping.targetDocumentId,
                    dataToUpdate,
                    StrapiContentTypeKind.CollectionType
                )

                // Extract the updated entry's ID and documentId
                val data = response["data"]?.jsonObject
                val targetId = data?.get("id")?.toString()?.toInt() ?: 0
                val targetDocumentId = data?.get("documentId")?.toString()?.replace("\"", "") ?: ""

                // Store the mapping
                idMappings.getOrPut(contentType) { mutableMapOf() }[differentEntry.metadata.documentId] =
                    ContentMapping(targetDocumentId, targetId)

                // Update the mapping in the database
                val existingMapping = mergeRequestDocumentMappingRepository.getFilesMappingForInstances(
                    mergeRequest.sourceInstance.id,
                    mergeRequest.targetInstance.id,
                    differentEntry.metadata.documentId,
                    targetDocumentId
                )

                if (existingMapping != null) {
                    // Update existing mapping
                    val updatedMapping = existingMapping.copy(
                        sourceLastUpdateDate = OffsetDateTime.now(),
                        targetId = targetId,
                        targetDocumentId = targetDocumentId,
                        targetLastUpdateDate = OffsetDateTime.now()
                    )
                    mergeRequestDocumentMappingRepository.updateMapping(existingMapping.id, updatedMapping)
                }

                // Update sync status to success
                mergeRequestSelectionsRepository.updateSyncStatus(
                    selection.id,
                    true,
                    null
                )
            } catch (e: Exception) {
                println("Error updating collection type entry $contentType: ${e.message}")

                // Update sync status to failure
                mergeRequestSelectionsRepository.updateSyncStatus(
                    selection.id,
                    false,
                    e.message ?: "Unknown error"
                )
            }
        }

        // Process entries to delete
        for (selection in toDelete) {
            val targetEntry =
                comparisonResult.onlyInTarget.find { it.metadata.documentId == selection.documentId } ?: continue

            try {
                targetClient.deleteContentEntry(
                    contentTypeSchema.queryName,
                    targetEntry.metadata.id.toString(),
                    "collectionType"
                )

                // Delete the mapping from the database
                mergeRequestDocumentMappingRepository.deleteFilesMappingsForInstancesByTarget(
                    mergeRequest.sourceInstance.id,
                    mergeRequest.targetInstance.id,
                    targetEntry.metadata.documentId
                )

                // Update sync status to success
                mergeRequestSelectionsRepository.updateSyncStatus(
                    selection.id,
                    true,
                    null
                )
            } catch (e: Exception) {
                println("Error deleting collection type entry $contentType: ${e.message}")

                // Update sync status to failure
                mergeRequestSelectionsRepository.updateSyncStatus(
                    selection.id,
                    false,
                    e.message ?: "Unknown error"
                )
            }
        }
    }

    private fun prepareDataForCreation(
        sourceData: JsonObject,
        idMappings: Map<String, Map<String, ContentMapping>>,
        firstPass: Boolean
    ): JsonObject {
        // Create a mutable copy of the source data
        val result = sourceData.toMutableMap()

        // Remove metadata fields that should not be included in the creation/update
        result.remove("id")
        result.remove("documentId")
        result.remove("createdAt")
        result.remove("updatedAt")
        result.remove("publishedAt")
        result.remove("createdBy")
        result.remove("updatedBy")
        result.remove("status")
        result.remove("localizations")


        // Process relationships at the first level
        // Create a list of changes to apply after iteration to avoid ConcurrentModificationException
        val updates = mutableMapOf<String, JsonElement>()
        val keysToRemove = mutableListOf<String>()

        // Use toList() to create a copy of entries for safe iteration
        result.entries.toList().forEach { (key, value) ->
            when (value) {
                is JsonObject -> {
                    // Handle single relationship
                    if (value.containsKey("id") && value.containsKey("documentId")) {
                        val relatedContentType = findContentTypeForDocumentId(
                            value["documentId"].toString().replace("\"", ""),
                            idMappings
                        )
                        if (relatedContentType != null) {
                            val sourceDocumentId = value["documentId"].toString().replace("\"", "")
                            val targetDocumentId = idMappings[relatedContentType]?.get(sourceDocumentId)

                            if (targetDocumentId != null) {
                                // Replace with target ID reference
                                if (value.containsKey("formats") && value.containsKey("ext") && value.containsKey("mime")) {
                                    updates[key] = buildJsonObject {
                                        put("set", JsonArray(listOf(JsonPrimitive(targetDocumentId.targetId))))
                                    }
                                } else {
                                    updates[key] = buildJsonObject {
                                        put("set", JsonArray(listOf(JsonPrimitive(targetDocumentId.targetDocumentId))))
                                    }
                                }
                            } else {
                                // No mapping found, remove the relationship
                                keysToRemove.add(key)
                            }
                        } else {
                            keysToRemove.add(key)
                        }
                    } else {
                        // Process nested objects recursively
                        val res = processNestedObject(value, idMappings)
                        if (res != null) {
                            updates[key] = res
                        } else keysToRemove.add(key)
                    }
                }

                is JsonArray -> {
                    // Handle multiple relationships
                    val relatedItems = value.jsonArray.mapNotNull { item ->
                        if (item is JsonObject && item.containsKey("id") && item.containsKey("documentId")) {
                            val relatedContentType = findContentTypeForDocumentId(
                                item["documentId"].toString().replace("\"", ""),
                                idMappings
                            )
                            if (relatedContentType != null) {
                                val sourceDocumentId = item["documentId"].toString().replace("\"", "")
                                val targetDocumentId = idMappings[relatedContentType]?.get(sourceDocumentId)

                                if (targetDocumentId != null) {
                                    if (item.containsKey("formats") && item.containsKey("ext") && item.containsKey("mime")) {
                                        JsonPrimitive(targetDocumentId.targetId)
                                    } else
                                        JsonPrimitive(targetDocumentId.targetDocumentId)
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        } else {
                            null
                        }
                    }

                    if (relatedItems.isNotEmpty()) {
                        // Replace with target ID references
                        updates[key] = buildJsonObject {
                            put("set", JsonArray(relatedItems.map { it }))
                        }
                    } else if (value.jsonArray.isNotEmpty()) {
                        // Process array elements recursively
                        val res = processNestedArray(value, idMappings)
                        if (res.isNotEmpty())
                            updates[key] = res
                        else
                            keysToRemove.add(key)
                    } else {
                        // No mappings found, remove the relationship
                        keysToRemove.add(key)
                    }
                }
                // For other JSON element types, keep them as is
                else -> {
                    // No action needed for primitive values or null
                }
            }
        }

        // Apply all updates
        updates.forEach { (key, value) ->
            result[key] = value
        }

        // Remove keys marked for removal
        keysToRemove.forEach { key ->
            result.remove(key)
        }

        return JsonObject(result)
    }

    /**
     * Recursively process a nested JsonObject
     */
    private fun processNestedObject(
        obj: JsonObject,
        idMappings: Map<String, Map<String, ContentMapping>>
    ): JsonObject? {
        val result = obj.toMutableMap()

        // If object has both id and documentId, replace with target id if found
        if (obj.containsKey("id") && obj.containsKey("documentId")) {
            val documentId = obj["documentId"].toString().replace("\"", "")
            val relatedContentType = findContentTypeForDocumentId(documentId, idMappings)

            if (relatedContentType != null) {
                val targetId = idMappings[relatedContentType]?.get(documentId)
                if (targetId != null) {
                    // Replace the entire object with just the id
                    if (obj.containsKey("formats") && obj.containsKey("ext") && obj.containsKey("mime")) {
                        return buildJsonObject {
                            put("id", JsonPrimitive(targetId.targetId))
                        }
                    }
                    return buildJsonObject {
                        put("documentId", JsonPrimitive(targetId.targetDocumentId))
                    }
                }
            } else {
                return null
            }
        } else if (obj.containsKey("id") && !obj.containsKey("documentId")) {
            // If object has id but no documentId, remove the id field
            result.remove("id")
        }

        // Process all fields recursively
        // Create collections to track changes to avoid ConcurrentModificationException
        val updates = mutableMapOf<String, JsonElement>()
        val keysToRemove = mutableListOf<String>()

        // Use toList() to create a copy of entries for safe iteration
        obj.entries.toList().forEach { (key, value) ->
            when (value) {
                is JsonObject -> {
                    val res = processNestedObject(value, idMappings)
                    if (res != null) {
                        updates[key] = res
                    } else keysToRemove.add(key)
                }

                is JsonArray -> {
                    val res = processNestedArray(value, idMappings)
                    if (res.isNotEmpty())
                        updates[key] = res
                    else
                        keysToRemove.add(key)
                }

                else -> {
                    // Keep primitive values as is
                }
            }
        }

        // Apply all updates
        updates.forEach { (key, value) ->
            result[key] = value
        }
        keysToRemove.forEach { key ->
            result.remove(key)
        }

        return JsonObject(result)
    }

    /**
     * Recursively process a nested JsonArray
     */
    private fun processNestedArray(array: JsonArray, idMappings: Map<String, Map<String, ContentMapping>>): JsonArray {
        val processedItems = array.mapNotNull { item ->
            when (item) {
                is JsonObject -> processNestedObject(item, idMappings)
                is JsonArray -> processNestedArray(item, idMappings)
                else -> item // Keep primitive values as is
            }
        }

        return JsonArray(processedItems)
    }

    private fun findContentTypeForDocumentId(
        documentId: String,
        idMappings: Map<String, Map<String, ContentMapping>>
    ): String? {
        for ((contentType, mappings) in idMappings) {
            if (mappings.containsKey(documentId)) {
                return contentType
            }
        }
        return null
    }

    private suspend fun mergeRequestFileMergeProcess(
        mergeRequest: MergeRequestWithInstancesDTO,
        sourceClient: StrapiClient,
        targetClient: StrapiClient,
        mergeRequestFiles: List<MergeRequestSelection>,
        comparisonData: ContentTypeFileComparisonResult
    ) {
        val mergeRequestSelectionsRepository = MergeRequestSelectionsRepository()
        val sourceFolders = sourceClient.getFolders()
        val sourceFoldersMap = sourceFolders.associateBy { it.path }

        val fileMapping = mergeRequestDocumentMappingRepository.getFilesMappingsForInstances(
            mergeRequest.sourceInstance.id,
            mergeRequest.targetInstance.id,
            mergeRequestFiles.filter { it.direction != Direction.TO_DELETE }.map { it.documentId },
            mergeRequestFiles.filter { it.direction == Direction.TO_DELETE }.map { it.documentId },
        )


        // 4. Find folders present in the target and identify missing folders
        val targetFolders = targetClient.getFolders()
        val targetFolderPaths = targetFolders.map { it.pathFull }.toSet()
        val targetFolderMap = targetFolders.associateBy { it.pathFull }.toMutableMap()


        // Collect all folder paths from images to be processed
        val requiredFolderPaths = mutableSetOf<String>()

        // Process files to create or update based on the merge request files
        val filesToProcess = mutableListOf<Pair<StrapiImage, Direction>>()

        // Files to delete
        val filesToDelete = mutableListOf<StrapiImage>()

        // Extract files from comparison data based on documentIds in mergeRequestFiles
        for (file in mergeRequestFiles) {
            when (file.direction) {
                Direction.TO_CREATE -> {
                    // Find the file in onlyInSource
                    val sourceFile = comparisonData.onlyInSource.find { it.metadata.documentId == file.documentId }
                    if (sourceFile != null) {
                        filesToProcess.add(Pair(sourceFile, Direction.TO_CREATE))
                        sourceFoldersMap[sourceFile.metadata.folderPath]?.pathFull?.let { requiredFolderPaths.add(it) }
                    }
                }

                Direction.TO_UPDATE -> {
                    // Find the file in different
                    val differentFile =
                        comparisonData.different.find { it.source.metadata.documentId == file.documentId }
                    if (differentFile != null) {
                        filesToProcess.add(Pair(differentFile.source, Direction.TO_UPDATE))
                        sourceFoldersMap[differentFile.source.metadata.folderPath]?.pathFull?.let {
                            requiredFolderPaths.add(
                                it
                            )
                        }
                    }
                }

                Direction.TO_DELETE -> {
                    // Find the file in onlyInTarget
                    val targetFile = comparisonData.onlyInTarget.find { it.metadata.documentId == file.documentId }
                    if (targetFile != null) {
                        filesToDelete.add(targetFile)
                    }
                }
            }
        }

        // 5. Create missing folders on the target
        val missingFolderPaths = requiredFolderPaths.flatMap { folderPath ->
            folderPath.split("/")
                .filter { it.isNotEmpty() }
                .scan("") { acc, segment ->
                    if (acc.isEmpty()) "/$segment" else "$acc/$segment"
                }
                .filter { it.isNotEmpty() }
        }.distinct()
            .filter { folderPath ->
                !targetFolderPaths.contains(folderPath)
            }

        for (folderPath in missingFolderPaths.sortedBy { it.split("/").size }) {
            try {
                // Extract folder name from path (last segment)
                val folderName = folderPath.split("/").last()
                // Extract parent path (everything before the last segment)
                val parentPath = if (folderPath.contains("/")) {
                    folderPath.substring(0, folderPath.lastIndexOf("/"))
                } else {
                    ""
                }

                val parent = if (parentPath.isNotEmpty())
                    targetFolderMap[parentPath]?.id
                else
                    null


                // Create the folder on the target
                val createdFolder = targetClient.createFolder(folderName, parent)
                val fullPath = "$parentPath/$folderName"
                targetFolderMap[fullPath] = createdFolder.copy(pathFull = fullPath)

            } catch (e: Exception) {
                println("Error creating folder $folderPath: ${e.message}")
                // Continue with other folders even if one fails
            }
        }

        // 6. Delete files marked for deletion
        for (selection in mergeRequestFiles.filter { it.direction == Direction.TO_DELETE }) {
            val file = comparisonData.onlyInTarget.find { it.metadata.documentId == selection.documentId }
            if (file != null) {
                try {
                    // Delete the file from target
                    newSuspendedTransaction {
                        targetClient.deleteFile(file.metadata.id.toString())

                        println("Deleted file ${file.metadata.name} with ID ${file.metadata.id}")

                        mergeRequestDocumentMappingRepository.deleteFilesMappingsForInstancesByTarget(
                            mergeRequest.sourceInstance.id,
                            mergeRequest.targetInstance.id,
                            file.metadata.documentId
                        )

                        // Update sync status to success
                        mergeRequestSelectionsRepository.updateSyncStatus(
                            selection.id,
                            true,
                            null
                        )
                    }
                } catch (e: Exception) {
                    println("Error deleting file ${file.metadata.name}: ${e.message}")
                    // Update sync status to failure
                    mergeRequestSelectionsRepository.updateSyncStatus(
                        selection.id,
                        false,
                        e.message ?: "Unknown error"
                    )
                    // Continue with other files even if one fails
                }
            }
        }

        // 7. Create or update each image by making appropriate calls to the target
        for ((file, direction) in filesToProcess) {
            // Find the corresponding selection
            val selection = mergeRequestFiles.find { 
                it.documentId == file.metadata.documentId && 
                (it.direction == Direction.TO_CREATE || it.direction == Direction.TO_UPDATE) 
            }

            if (selection == null) continue

            try {
                // Download the file from source
                val sourceFile = sourceClient.downloadFile(file)
                val md5 = calculateMD5Hash(sourceFile.inputStream())

                val folder = sourceFoldersMap[file.metadata.folderPath]?.let { targetFolderMap[it.pathFull]?.id }

                val existingMappings = fileMapping.find { it.sourceDocumentId == file.metadata.documentId }

                // Upload or update the file on target
                val uploadResponse = targetClient.uploadFile(
                    existingMappings?.targetId,
                    file.metadata.name,
                    sourceFile,
                    file.metadata.mime,
                    file.metadata.caption,
                    file.metadata.alternativeText,
                    folder
                )

                // Update the document mapping table
                if (existingMappings == null) {
                    // Create new mapping
                    val newMapping = MergeRequestDocumentMapping(
                        sourceId = file.metadata.id,
                        contentType = STRAPI_FILE_CONTENT_TYPE_NAME,
                        sourceStrapiInstanceId = mergeRequest.sourceInstance.id,
                        targetStrapiInstanceId = mergeRequest.targetInstance.id,
                        sourceDocumentId = file.metadata.documentId,
                        sourceLastUpdateDate = OffsetDateTime.now(),
                        sourceDocumentMD5 = md5,
                        targetId = uploadResponse.id,
                        targetDocumentId = uploadResponse.documentId,
                        targetLastUpdateDate = OffsetDateTime.now(),
                        targetDocumentMD5 = md5
                    )
                    mergeRequestDocumentMappingRepository.createMapping(newMapping)
                } else {
                    // Update existing mapping
                    val updatedMapping = existingMappings.copy(
                        sourceLastUpdateDate = OffsetDateTime.now(),
                        contentType = STRAPI_FILE_CONTENT_TYPE_NAME,
                        sourceDocumentMD5 = md5,
                        targetId = uploadResponse.id,
                        targetDocumentId = uploadResponse.documentId,
                        targetLastUpdateDate = OffsetDateTime.now(),
                        targetDocumentMD5 = md5
                    )
                    mergeRequestDocumentMappingRepository.updateMapping(existingMappings.id, updatedMapping)
                }

                // Clean up the temporary file
                sourceFile.delete()

                println("${if (direction == Direction.TO_CREATE) "Created" else "Updated"} file ${file.metadata.name} with ID ${uploadResponse.id}")

                // Update sync status to success
                mergeRequestSelectionsRepository.updateSyncStatus(
                    selection.id,
                    true,
                    null
                )
            } catch (e: Exception) {
                println("Error ${if (direction == Direction.TO_CREATE) "creating" else "updating"} file ${file.metadata.name}: ${e.message}")

                // Update sync status to failure
                mergeRequestSelectionsRepository.updateSyncStatus(
                    selection.id,
                    false,
                    e.message ?: "Unknown error"
                )
                // Continue with other files even if one fails
            }
        }
    }
}
