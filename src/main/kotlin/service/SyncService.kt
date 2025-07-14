package it.sebi.service

import it.sebi.client.StrapiClient
import it.sebi.client.client
import it.sebi.models.*
import it.sebi.repository.MergeRequestDocumentMappingRepository
import it.sebi.utils.calculateMD5Hash
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.*
import java.time.OffsetDateTime


class SyncService(private val mergeRequestDocumentMappingRepository: MergeRequestDocumentMappingRepository) {

    /**
     * Check if two Strapi instances have compatible schemas
     * @return SchemaCompatibilityResult with compatibility status and details
     */
    suspend fun checkSchemaCompatibility(
        sourceInstance: StrapiInstance,
        targetInstance: StrapiInstance
    ): SchemaCompatibilityResult {
        val sourceClient = sourceInstance.client()
        val targetClient = targetInstance.client()

        // Get content types from both instances
        val sourceContentTypes = sourceClient.getContentTypes().filter { it.uid.startsWith("api::") }
        val targetContentTypes = targetClient.getContentTypes().filter { it.uid.startsWith("api::") }

        // Get component schemas from both instances
        val sourceComponents = sourceClient.getComponentSchema()
        val targetComponents = targetClient.getComponentSchema()

        // Check content types compatibility
        val missingInTarget = sourceContentTypes.filter { sourceType ->
            targetContentTypes.none { it.uid == sourceType.uid }
        }

        val missingInSource = targetContentTypes.filter { targetType ->
            sourceContentTypes.none { it.uid == targetType.uid }
        }

        val incompatibleContentTypes = mutableListOf<ContentTypeIncompatibility>()

        // Check compatibility for content types that exist in both instances
        for (sourceType in sourceContentTypes) {
            val targetType = targetContentTypes.find { it.uid == sourceType.uid } ?: continue

            // Check if the content type kinds match (singleType vs collectionType)
            if (sourceType.schema.kind != targetType.schema.kind) {
                incompatibleContentTypes.add(
                    ContentTypeIncompatibility(
                        contentType = sourceType.uid,
                        reason = "Content type kind mismatch: source is ${sourceType.schema.kind}, target is ${targetType.schema.kind}"
                    )
                )
                continue
            }

            // Check attributes compatibility
            val incompatibleAttributes = mutableListOf<AttributeIncompatibility>()

            for ((attrName, sourceAttr) in sourceType.schema.attributes) {
                val targetAttr = targetType.schema.attributes[attrName]

                if (targetAttr == null) {
                    incompatibleAttributes.add(
                        AttributeIncompatibility(
                            attributeName = attrName,
                            reason = "Attribute exists in source but not in target"
                        )
                    )
                    continue
                }

                // Check attribute type compatibility
                if (sourceAttr.type != targetAttr.type) {
                    incompatibleAttributes.add(
                        AttributeIncompatibility(
                            attributeName = attrName,
                            reason = "Attribute type mismatch: source is ${sourceAttr.type}, target is ${targetAttr.type}"
                        )
                    )
                    continue
                }

                // For relation attributes, check relation type and target
                if (sourceAttr.type == "relation") {
                    if (sourceAttr.relation != targetAttr.relation || sourceAttr.target != targetAttr.target) {
                        incompatibleAttributes.add(
                            AttributeIncompatibility(
                                attributeName = attrName,
                                reason = "Relation mismatch: source relation=${sourceAttr.relation}, target=${sourceAttr.target}; " +
                                        "target relation=${targetAttr.relation}, target=${targetAttr.target}"
                            )
                        )
                    }
                }

                // For component attributes, check component type and repeatable flag
                if (sourceAttr.type == "component") {
                    if (sourceAttr.component != targetAttr.component) {
                        incompatibleAttributes.add(
                            AttributeIncompatibility(
                                attributeName = attrName,
                                reason = "Component mismatch: source component=${sourceAttr.component}, target component=${targetAttr.component}"
                            )
                        )
                    }

                    if (sourceAttr.repeatable != targetAttr.repeatable) {
                        incompatibleAttributes.add(
                            AttributeIncompatibility(
                                attributeName = attrName,
                                reason = "Component repeatable mismatch: source repeatable=${sourceAttr.repeatable}, target repeatable=${targetAttr.repeatable}"
                            )
                        )
                    }
                }
            }

            if (incompatibleAttributes.isNotEmpty()) {
                incompatibleContentTypes.add(
                    ContentTypeIncompatibility(
                        contentType = sourceType.uid,
                        reason = "Incompatible attributes",
                        attributes = incompatibleAttributes
                    )
                )
            }
        }

        // Check components compatibility
        val missingComponentsInTarget = sourceComponents.filter { sourceComponent ->
            targetComponents.none { it.uid == sourceComponent.uid }
        }

        val missingComponentsInSource = targetComponents.filter { targetComponent ->
            sourceComponents.none { it.uid == targetComponent.uid }
        }

        val incompatibleComponents = mutableListOf<ComponentIncompatibility>()

        // Check compatibility for components that exist in both instances
        for (sourceComponent in sourceComponents) {
            val targetComponent = targetComponents.find { it.uid == sourceComponent.uid } ?: continue

            // Check attributes compatibility
            val incompatibleAttributes = mutableListOf<AttributeIncompatibility>()

            for ((attrName, sourceAttr) in sourceComponent.schema.attributes) {
                val targetAttr = targetComponent.schema.attributes[attrName]

                if (targetAttr == null) {
                    incompatibleAttributes.add(
                        AttributeIncompatibility(
                            attributeName = attrName,
                            reason = "Attribute exists in source but not in target"
                        )
                    )
                    continue
                }

                // Check attribute type compatibility
                if (sourceAttr.type != targetAttr.type) {
                    incompatibleAttributes.add(
                        AttributeIncompatibility(
                            attributeName = attrName,
                            reason = "Attribute type mismatch: source is ${sourceAttr.type}, target is ${targetAttr.type}"
                        )
                    )
                    continue
                }

                // For relation attributes, check relation type and target
                if (sourceAttr.type == "relation") {
                    if (sourceAttr.relation != targetAttr.relation || sourceAttr.target != targetAttr.target) {
                        incompatibleAttributes.add(
                            AttributeIncompatibility(
                                attributeName = attrName,
                                reason = "Relation mismatch: source relation=${sourceAttr.relation}, target=${sourceAttr.target}; " +
                                        "target relation=${targetAttr.relation}, target=${targetAttr.target}"
                            )
                        )
                    }
                }

                // For component attributes, check component type and repeatable flag
                if (sourceAttr.type == "component") {
                    if (sourceAttr.component != targetAttr.component) {
                        incompatibleAttributes.add(
                            AttributeIncompatibility(
                                attributeName = attrName,
                                reason = "Component mismatch: source component=${sourceAttr.component}, target component=${targetAttr.component}"
                            )
                        )
                    }

                    if (sourceAttr.repeatable != targetAttr.repeatable) {
                        incompatibleAttributes.add(
                            AttributeIncompatibility(
                                attributeName = attrName,
                                reason = "Component repeatable mismatch: source repeatable=${sourceAttr.repeatable}, target repeatable=${targetAttr.repeatable}"
                            )
                        )
                    }
                }
            }

            if (incompatibleAttributes.isNotEmpty()) {
                incompatibleComponents.add(
                    ComponentIncompatibility(
                        component = sourceComponent.uid,
                        reason = "Incompatible attributes",
                        attributes = incompatibleAttributes
                    )
                )
            }
        }

        val isCompatible = missingInTarget.isEmpty() && missingInSource.isEmpty() &&
                incompatibleContentTypes.isEmpty() && missingComponentsInTarget.isEmpty() &&
                missingComponentsInSource.isEmpty() && incompatibleComponents.isEmpty()

        return SchemaCompatibilityResult(
            isCompatible = isCompatible,
            missingInTarget = missingInTarget,
            missingInSource = missingInSource,
            incompatibleContentTypes = incompatibleContentTypes,
            sourceContentTypes = sourceContentTypes,
            targetContentTypes = targetContentTypes,
            missingComponentsInTarget = missingComponentsInTarget,
            missingComponentsInSource = missingComponentsInSource,
            incompatibleComponents = incompatibleComponents,
            sourceComponents = sourceComponents,
            targetComponents = targetComponents
        )
    }


    /**
     * Compare content between two Strapi instances with enhanced relationship analysis
     * @return Enhanced comparison results with relationship information
     */
    suspend fun compareContentWithRelationships(
        mergeRequest: MergeRequestWithInstancesDTO,
        contentTypes: List<StrapiContentType>,
        components: List<StrapiComponent>
    ): ContentTypeComparisonResultMapWithRelationships {
        val sourceClient = mergeRequest.sourceInstance.client()
        val targetClient = mergeRequest.targetInstance.client()

        val mappings = mergeRequestDocumentMappingRepository.getAllMappings(
            mergeRequest.sourceInstance.id,
            mergeRequest.targetInstance.id,
        )


        var sourceContentTypes =
            contentTypes.filter { it.uid.startsWith("api::") || it.uid == STRAPI_FILE_CONTENT_TYPE_NAME }


        // Build content type relationships
        val contentTypeRelationships = buildContentTypeRelationships(sourceContentTypes, components)


        // Cache for content entries to avoid repeated API calls
        val sourceEntriesCache = mutableMapOf<String, List<EntryElement>>()
        val targetEntriesCache = mutableMapOf<String, List<EntryElement>>()

        // Fetch all entries for each content type once in parallel
        coroutineScope {
            val sourceDeferred = async {
                sourceContentTypes.map { contentType ->


                    val entries = sourceClient.getContentEntries(contentType)

                    contentType to entries

                }
            }
            val targetDeferred = async {
                sourceContentTypes.map { contentType ->
                    val entries = targetClient.getContentEntries(contentType)

                    contentType to entries

                }
            }
            val (sourceEntries ,targetEntries)= awaitAll( sourceDeferred,targetDeferred)

//            val toDeleteSource: List<MergeRequestDocumentMapping> = sourceEntries.flatMap { (contentType, entries) ->
//                val allIds = entries.mapNotNull { it["documentId"]?.jsonPrimitive?.content }
//                mappings.filter { it.contentType == contentType.uid && !allIds.contains(it.sourceDocumentId) }
//            }
//            val toDeleteTarget = sourceEntries.flatMap { (contentType, entries) ->
//                val allIds = entries.mapNotNull { it["documentId"]?.jsonPrimitive?.content }
//                mappings.filter { it.contentType == contentType.uid && !allIds.contains(it.targetDocumentId) }
//            }

            sourceEntries.forEach { (contentType, entries) ->

                sourceEntriesCache[contentType.uid] = sourceClient.processEntries(entries, null)
            }

            targetEntries.forEach { (contentType, entries) ->
                targetEntriesCache[contentType.uid] = targetClient.processEntries(entries, mappings)
            }
        }


        // First handle files using the proper upload API
        val files =
            compareFiles(mappings, mergeRequest.sourceInstance, mergeRequest.targetInstance, sourceClient, targetClient)

        val (singleTypes, collectionTypes) = sourceContentTypes.partition { it.schema.kind == StrapiContentTypeKind.SingleType }

        // Process single types with comparison information
        val singleResultsWithRelationships = mutableMapOf<String, ContentTypeComparisonResultWithRelationships>()
        val singleTypeComparisonResults = mutableMapOf<String, ContentTypeComparisonResult>()

        for (contentType in singleTypes) {
            val comparisonResult = compareContentType(
                mergeRequest.sourceInstance,
                mergeRequest.targetInstance,
                contentType,
                sourceEntriesCache,
                targetEntriesCache
            )

            singleTypeComparisonResults[contentType.uid] = comparisonResult

            // Determine dependencies including transitive dependencies through components
            val dependsOn = calculateAllDependencies(contentType.uid, contentTypeRelationships)
                .distinct()

            val dependedOnBy = calculateAllDependedOnBy(contentType.uid, contentTypeRelationships)
                .distinct()

            // We'll add relationships later
            singleResultsWithRelationships[contentType.uid] = ContentTypeComparisonResultWithRelationships(
                contentType = comparisonResult.contentType,
                onlyInSource = comparisonResult.onlyInSource,
                onlyInTarget = comparisonResult.onlyInTarget,
                different = comparisonResult.different,
                identical = comparisonResult.identical,
                kind = comparisonResult.kind,
                compareKind = comparisonResult.compareKind,
                relationships = emptyList(),
                dependsOn = dependsOn,
                dependedOnBy = dependedOnBy
            )
        }

        // Process collection types with comparison information
        val collectionResultsWithRelationships = mutableMapOf<String, ContentTypesComparisonResultWithRelationships>()
        val collectionTypeComparisonResults = mutableMapOf<String, ContentTypesComparisonResult>()

        for (contentType in collectionTypes) {
            val comparisonResult = compareContentTypes(
                sourceClient,
                targetClient,
                mergeRequest.sourceInstance,
                mergeRequest.targetInstance,
                contentType,
                sourceEntriesCache,
                targetEntriesCache
            )

            collectionTypeComparisonResults[contentType.uid] = comparisonResult

            // Determine dependencies including transitive dependencies through components
            val dependsOn = calculateAllDependencies(contentType.uid, contentTypeRelationships)
                .distinct()

            val dependedOnBy = calculateAllDependedOnBy(contentType.uid, contentTypeRelationships)
                .distinct()

            // We'll add relationships later
            collectionResultsWithRelationships[contentType.uid] = ContentTypesComparisonResultWithRelationships(
                contentType = comparisonResult.contentType,
                onlyInSource = comparisonResult.onlyInSource,
                onlyInTarget = comparisonResult.onlyInTarget,
                different = comparisonResult.different,
                identical = comparisonResult.identical,
                kind = comparisonResult.kind,
                relationships = emptyMap(),
                dependsOn = dependsOn,
                dependedOnBy = dependedOnBy
            )
        }

        // Now analyze entry relationships with comparison status information
        val entryRelationships = analyzeEntryRelationshipsWithComparisonStatus(
            sourceContentTypes,
            components,
            contentTypeRelationships,
            sourceEntriesCache,

            singleTypeComparisonResults,
            collectionTypeComparisonResults,
            files
        )

        // Update single types with relationship information
        for (contentType in singleTypes) {
            val relationships = extractRelationshipsForContentType(contentType.uid, entryRelationships)

            val currentResult = singleResultsWithRelationships[contentType.uid]
            if (currentResult != null) {
                singleResultsWithRelationships[contentType.uid] = currentResult.copy(
                    relationships = relationships
                )
            }
        }

        // Update collection types with relationship information
        for (contentType in collectionTypes) {
            val relationshipsByEntry = mutableMapOf<String, List<EntryRelationship>>()

            // Process entries that are only in source
            val comparisonResult = collectionTypeComparisonResults[contentType.uid]
            if (comparisonResult != null) {
                // Process entries that are only in source
                for (entry in comparisonResult.onlyInSource) {
                    val documentId = entry.metadata.documentId
                    val relationships = entryRelationships[documentId] ?: emptyList()
                    relationshipsByEntry[documentId] = relationships
                }

                // Process entries that are different
                for (diffEntry in comparisonResult.different) {
                    val documentId = diffEntry.source.metadata.documentId
                    val relationships = entryRelationships[documentId] ?: emptyList()
                    relationshipsByEntry[documentId] = relationships
                }

                // Process entries that are identical
                for (entry in comparisonResult.identical) {
                    val documentId = entry.metadata.documentId
                    val relationships = entryRelationships[documentId] ?: emptyList()
                    relationshipsByEntry[documentId] = relationships
                }
            }


            val currentResult = collectionResultsWithRelationships[contentType.uid]
            if (currentResult != null) {
                collectionResultsWithRelationships[contentType.uid] = currentResult.copy(
                    relationships = relationshipsByEntry
                )
            }
        }

        return ContentTypeComparisonResultMapWithRelationships(
            files = files,
            singleTypes = singleResultsWithRelationships,
            collectionTypes = collectionResultsWithRelationships,
            contentTypeRelationships = contentTypeRelationships
        )
    }

    /**
     * Extract relationships for a specific content type from the entry relationships map
     */
    private fun extractRelationshipsForContentType(
        contentTypeUid: String,
        entryRelationships: Map<String, List<EntryRelationship>>
    ): List<EntryRelationship> {
        return entryRelationships.values.flatten().filter {
            it.sourceContentType == contentTypeUid || it.targetContentType == contentTypeUid
        }
    }

    data class MappingStatusRef(val contentTypeId: String, val status: ContentTypeComparisonResultKind)

    /**
     * Analyze relationships between entries with comparison status information
     * @param sourceEntriesCache Optional cache of entries to avoid repeated API calls
     * @param singleTypeComparisonResults Comparison results for single types
     * @param collectionTypeComparisonResults Comparison results for collection types
     * @param files Comparison results for files
     * @return Map of entry ID to list of related entry IDs with comparison status
     */

    private fun analyzeEntryRelationshipsWithComparisonStatus(
        contentTypes: List<StrapiContentType>,
        strapiComponentTypes: List<StrapiComponent>,
        contentTypeRelationships: List<ContentRelationship>,
        sourceEntriesCache: Map<String, List<EntryElement>>? = null,
        singleTypeComparisonResults: Map<String, ContentTypeComparisonResult>,
        collectionTypeComparisonResults: Map<String, ContentTypesComparisonResult>,
        files: ContentTypeFileComparisonResult
    ): Map<String, List<EntryRelationship>> {
        val entryRelationships = mutableMapOf<String, MutableList<EntryRelationship>>()
        val contentTypeEntries = mutableMapOf<String, List<EntryElement>>()

        // Use cached entries if available, otherwise fetch them
        for (contentType in contentTypes) {
            sourceEntriesCache?.get(contentType.uid)?.let { entries ->
                contentTypeEntries[contentType.uid] = entries
            }
        }

        // Create maps to quickly look up comparison status for entries
        val singleTypeStatusMap = mutableMapOf<String, MappingStatusRef>()
        for ((k, result) in singleTypeComparisonResults) {
            val documentId = result.onlyInSource?.metadata?.documentId
                ?: result.different?.source?.metadata?.documentId
                ?: result.identical?.metadata?.documentId
                ?: continue

            val status = when {
                result.onlyInSource != null -> ContentTypeComparisonResultKind.ONLY_IN_SOURCE
                result.onlyInTarget != null -> ContentTypeComparisonResultKind.ONLY_IN_TARGET
                result.different != null -> ContentTypeComparisonResultKind.DIFFERENT
                else -> ContentTypeComparisonResultKind.IDENTICAL
            }
            singleTypeStatusMap[documentId] = MappingStatusRef(k, status)
        }

        val collectionTypeStatusMap = mutableMapOf<String, MappingStatusRef>()
        for ((k, result) in collectionTypeComparisonResults) {
            // Entries only in source
            for (entry in result.onlyInSource) {
                collectionTypeStatusMap[entry.metadata.documentId] =
                    MappingStatusRef(k, ContentTypeComparisonResultKind.ONLY_IN_SOURCE)
            }

            // Different entries
            for (diffEntry in result.different) {
                collectionTypeStatusMap[diffEntry.source.metadata.documentId] =
                    MappingStatusRef(k, ContentTypeComparisonResultKind.DIFFERENT)
            }

            // Identical entries
            for (entry in result.identical) {
                collectionTypeStatusMap[entry.metadata.documentId] =
                    MappingStatusRef(k, ContentTypeComparisonResultKind.IDENTICAL)
            }
        }

        val fileStatusMap = mutableMapOf<String, ContentTypeComparisonResultKind>()
        // Files only in source
        for (file in files.onlyInSource) {
            fileStatusMap[file.metadata.documentId] = ContentTypeComparisonResultKind.ONLY_IN_SOURCE
        }

        // Different files
        for (diffFile in files.different) {
            fileStatusMap[diffFile.source.metadata.documentId] = ContentTypeComparisonResultKind.DIFFERENT
        }

        // Identical files
        for (file in files.identical) {
            fileStatusMap[file.metadata.documentId] = ContentTypeComparisonResultKind.IDENTICAL
        }

        // Analyze relationships between entries
        for (contentType in contentTypes) {
            if (contentType.uid == "api::contatti-page.contatti-page") {
                println("heere")
            }

            val entries = contentTypeEntries[contentType.uid] ?: continue

            for (entry in entries) {
                val documentId = entry.obj.metadata.documentId
                val relationships = mutableListOf<EntryRelationship>()

                // Get the comparison status for this entry
                val sourceStatus = when (contentType.schema.kind) {
                    StrapiContentTypeKind.SingleType -> singleTypeStatusMap[documentId]
                    StrapiContentTypeKind.CollectionType -> collectionTypeStatusMap[documentId]
                }

                // Check each field for relationships
                for ((fieldName, attribute) in contentType.schema.attributes) {
                    val attributes = entry.obj.rawData
                    val fieldData = attributes[fieldName] ?: continue
                    if (fieldData is JsonNull) continue
                    if (fieldData is JsonArray && fieldData.jsonArray.isEmpty()) continue
                    if (fieldData is JsonObject && fieldData.jsonObject.isEmpty()) continue
                    if (fieldData is JsonPrimitive) continue

                    if ((fieldName == "defaultSeo" || fieldName == "seo") && fieldData is JsonObject && fieldData.containsKey(
                            "shareImage"
                        )
                    ) {
                        fieldData.jsonObject["shareImage"]?.let { shareImage ->


                            val mediaFiles = when {
                                shareImage is JsonObject && shareImage.containsKey("documentId") -> {
                                    listOf(shareImage.jsonObject)
                                }

                                shareImage is JsonArray && shareImage.jsonArray.isNotEmpty() -> {
                                    fieldData.jsonArray.toList().map { it.jsonObject }
                                }

                                else -> listOf()
                            }
                            mediaFiles.map { mediaFile ->
                                val targetDocumentId =
                                    mediaFile["documentId"]!!.jsonPrimitive.content.trim('"')

                                // Get the comparison status for the file
                                val targetStatus = fileStatusMap[targetDocumentId]

                                // Determine the relationship status based on source and target status
                                val relationshipStatus = determineRelationshipStatus(sourceStatus?.status, targetStatus)

                                // Add a relationship to the file
                                relationships.add(
                                    EntryRelationship(
                                        sourceContentType = contentType.uid,
                                        sourceDocumentId = documentId,
                                        sourceField = fieldName,
                                        targetContentType = STRAPI_FILE_CONTENT_TYPE_NAME,
                                        targetDocumentId = targetDocumentId,
                                        relationType = "media",
                                        compareStatus = relationshipStatus
                                    )
                                )
                            }
                        }
                    } else if (attribute.type == "relation" && attribute.target != null) {
                        val targetContentType = attribute.target

                        // Extract related entry IDs based on relation type
                        when (attribute.relation) {
                            "oneToOne", "manyToOne" -> {
                                val targetId = extractRelateddocumentId(fieldData)
                                if (targetId != null) {
                                    // Get the comparison status for the target entry
                                    val targetStatus = getComparisonStatusForTarget(
                                        targetContentType,
                                        targetId,
                                        singleTypeStatusMap,
                                        collectionTypeStatusMap,
                                        fileStatusMap
                                    )

                                    // Determine the relationship status based on source and target status
                                    val relationshipStatus =
                                        determineRelationshipStatus(sourceStatus?.status, targetStatus)

                                    relationships.add(
                                        EntryRelationship(
                                            sourceContentType = contentType.uid,
                                            sourceDocumentId = documentId,
                                            sourceField = fieldName,
                                            targetContentType = targetContentType,
                                            targetDocumentId = targetId,
                                            relationType = attribute.relation,
                                            compareStatus = relationshipStatus
                                        )
                                    )
                                }
                            }

                            "oneToMany", "manyToMany" -> {
                                val targetIds = extractRelateddocumentIds(fieldData)
                                for (targetId in targetIds) {
                                    // Get the comparison status for the target entry
                                    val targetStatus = getComparisonStatusForTarget(
                                        targetContentType,
                                        targetId,
                                        singleTypeStatusMap,
                                        collectionTypeStatusMap,
                                        fileStatusMap
                                    )

                                    // Determine the relationship status based on source and target status
                                    val relationshipStatus =
                                        determineRelationshipStatus(sourceStatus?.status, targetStatus)

                                    relationships.add(
                                        EntryRelationship(
                                            sourceContentType = contentType.uid,
                                            sourceDocumentId = documentId,
                                            sourceField = fieldName,
                                            targetContentType = targetContentType,
                                            targetDocumentId = targetId,
                                            relationType = attribute.relation,
                                            compareStatus = relationshipStatus
                                        )
                                    )
                                }
                            }
                        }
                    } else if (attribute.type == "media") {
                        // Handle media attributes (file references)

                        val mediaFiles = when {
                            fieldData is JsonObject && fieldData.containsKey("documentId") -> {
                                listOf(fieldData.jsonObject)
                            }

                            fieldData is JsonArray && fieldData.jsonArray.isNotEmpty() -> {
                                fieldData.jsonArray.toList().map { it.jsonObject }
                            }

                            else -> listOf()
                        }
                        mediaFiles.map { mediaFile ->
                            val targetDocumentId =
                                mediaFile["documentId"]!!.jsonPrimitive.content.trim('"')

                            // Get the comparison status for the file
                            val targetStatus = fileStatusMap[targetDocumentId]

                            // Determine the relationship status based on source and target status
                            val relationshipStatus = determineRelationshipStatus(sourceStatus?.status, targetStatus)

                            // Add a relationship to the file
                            relationships.add(
                                EntryRelationship(
                                    sourceContentType = contentType.uid,
                                    sourceDocumentId = documentId,
                                    sourceField = fieldName,
                                    targetContentType = STRAPI_FILE_CONTENT_TYPE_NAME,
                                    targetDocumentId = targetDocumentId,
                                    relationType = "media",
                                    compareStatus = relationshipStatus
                                )
                            )
                        }
                    } else if (attribute.type == "dynamiczone") {
                        // Handle dynamiczone attributes which can contain components with relations or media
                        if (fieldData is JsonArray) {
                            for (component in fieldData) {
                                if (component is JsonObject) {
                                    // Process each component in the dynamiczone

                                    val componentRelationships = processComponent(
                                        strapiComponentTypes.find { it.uid == attribute.component },
                                        contentTypes,
                                        strapiComponentTypes,
                                        component,
                                        contentType.uid,
                                        documentId,
                                        fieldName,
                                        sourceStatus?.status,
                                        singleTypeStatusMap,
                                        collectionTypeStatusMap,
                                        fileStatusMap
                                    )
                                    relationships.addAll(componentRelationships)

                                }
                            }
                        }
                    } else if (attribute.type == "component") {
                        // Handle component attributes which can contain relations, media, or nested components
                        if (attribute.repeatable == true && fieldData is JsonArray) {
                            // Handle repeatable components (array of components)
                            for (component in fieldData) {
                                if (component is JsonObject) {
                                    // Process each component in the array
                                    strapiComponentTypes.find { cc -> cc.uid == attribute.component }
                                        ?.let { componentObj ->
                                            val componentRelationships = processComponent(
                                                componentObj,
                                                contentTypes,
                                                strapiComponentTypes,
                                                component,
                                                contentType.uid,
                                                documentId,
                                                fieldName,
                                                sourceStatus?.status,
                                                singleTypeStatusMap,
                                                collectionTypeStatusMap,
                                                fileStatusMap
                                            )
                                            relationships.addAll(componentRelationships)
                                        }

                                }
                            }
                        } else if (fieldData is JsonObject) {
                            // Handle single component
                            val componentRelationships = processComponent(
                                strapiComponentTypes.find { it.uid == attribute.component },
                                contentTypes,
                                strapiComponentTypes,
                                fieldData,
                                contentType.uid,
                                documentId,
                                fieldName,
                                sourceStatus?.status,
                                singleTypeStatusMap,
                                collectionTypeStatusMap,
                                fileStatusMap
                            )
                            relationships.addAll(componentRelationships)
                        }
                    }
                }

                if (relationships.isNotEmpty()) {
                    entryRelationships[documentId] = relationships.toMutableList()
                }
            }
        }

        // Update bidirectional relationships with target field information
        for ((_, relationships) in entryRelationships) {
            for (relationship in relationships) {
                // Skip file relationships as they are not bidirectional
                if (relationship.targetContentType == STRAPI_FILE_CONTENT_TYPE_NAME) {
                    continue
                }

                // Find the corresponding content relationship
                val contentRelationship = contentTypeRelationships.find {
                    it.sourceContentType == relationship.sourceContentType &&
                            it.targetContentType == relationship.targetContentType &&
                            it.sourceField == relationship.sourceField
                }

                if (contentRelationship?.isBidirectional == true && contentRelationship.targetField != null) {
                    // Update the relationship with the target field
                    val index = relationships.indexOf(relationship)
                    if (index >= 0) {
                        relationships[index] = relationship.copy(targetField = contentRelationship.targetField)
                    }
                }
            }
        }

        return entryRelationships
    }

    /**
     * Get the comparison status for a target entry
     */
    private fun getComparisonStatusForTarget(
        targetContentType: String,
        targetId: String,
        singleTypeStatusMap: Map<String, MappingStatusRef>,
        collectionTypeStatusMap: Map<String, MappingStatusRef>,
        fileStatusMap: Map<String, ContentTypeComparisonResultKind>
    ): ContentTypeComparisonResultKind? {
        return when {
            targetContentType == STRAPI_FILE_CONTENT_TYPE_NAME -> fileStatusMap[targetId]
            // For other content types, we need to determine if it's a single type or collection type
            // This is a simplification - in a real implementation, you'd need to check the content type kind
            singleTypeStatusMap.containsKey(targetId) -> singleTypeStatusMap[targetId]?.status
            else -> collectionTypeStatusMap[targetId]?.status
        }
    }

    /**
     * Process a component and extract relationships from it
     */
    private fun processComponent(
        componentObj: StrapiComponent?,
        contentTypes: List<StrapiContentType>,
        strapiComponentTypes: List<StrapiComponent>,
        component: JsonObject,
        sourceContentType: String,
        sourceDocumentId: String,
        sourceField: String,
        sourceStatus: ContentTypeComparisonResultKind?,
        singleTypeStatusMap: Map<String, MappingStatusRef>,
        collectionTypeStatusMap: Map<String, MappingStatusRef>,
        fileStatusMap: Map<String, ContentTypeComparisonResultKind>
    ): List<EntryRelationship> {
        val relationships = mutableListOf<EntryRelationship>()

        // Get the component type if available
        val componentType = component["__component"]?.jsonPrimitive?.content?.trim('"')

        // If componentObj is available, use its schema to determine relationships
        if (componentObj != null) {
            // Process each field in the component using the schema
            for ((fieldName, attribute) in componentObj.schema.attributes) {
                val fieldData = component[fieldName] ?: continue

                // Skip null or empty values
                if (fieldData is JsonNull) continue
                if (fieldData is JsonArray && fieldData.jsonArray.isEmpty()) continue
                if (fieldData is JsonObject && fieldData.jsonObject.isEmpty()) continue

                when (attribute.type) {
                    "relation" -> {
                        if (attribute.target != null) {
                            val targetContentType = attribute.target

                            // Extract related entry IDs based on relation type
                            when (attribute.relation) {
                                "oneToOne", "manyToOne" -> {
                                    val targetId = extractRelateddocumentId(fieldData)
                                    if (targetId != null) {
                                        // Get the comparison status for the target entry
                                        val targetStatus = getComparisonStatusForTarget(
                                            targetContentType,
                                            targetId,
                                            singleTypeStatusMap,
                                            collectionTypeStatusMap,
                                            fileStatusMap
                                        )

                                        // Determine the relationship status
                                        val relationshipStatus = determineRelationshipStatus(sourceStatus, targetStatus)

                                        relationships.add(
                                            EntryRelationship(
                                                sourceContentType = sourceContentType,
                                                sourceDocumentId = sourceDocumentId,
                                                sourceField = "$sourceField.$fieldName",
                                                targetContentType = targetContentType,
                                                targetDocumentId = targetId,
                                                relationType = attribute.relation,
                                                compareStatus = relationshipStatus
                                            )
                                        )
                                    }
                                }

                                "oneToMany", "manyToMany" -> {
                                    val targetIds = extractRelateddocumentIds(fieldData)
                                    for (targetId in targetIds) {
                                        // Get the comparison status for the target entry
                                        val targetStatus = getComparisonStatusForTarget(
                                            targetContentType,
                                            targetId,
                                            singleTypeStatusMap,
                                            collectionTypeStatusMap,
                                            fileStatusMap
                                        )

                                        // Determine the relationship status
                                        val relationshipStatus = determineRelationshipStatus(sourceStatus, targetStatus)

                                        relationships.add(
                                            EntryRelationship(
                                                sourceContentType = sourceContentType,
                                                sourceDocumentId = sourceDocumentId,
                                                sourceField = "$sourceField.$fieldName",
                                                targetContentType = targetContentType,
                                                targetDocumentId = targetId,
                                                relationType = attribute.relation,
                                                compareStatus = relationshipStatus
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    "media" -> {
                        // Handle media attributes (file references)
                        val mediaFiles = when {
                            fieldData is JsonObject && fieldData.containsKey("documentId") -> {
                                listOf(fieldData.jsonObject)
                            }

                            fieldData is JsonArray && fieldData.jsonArray.isNotEmpty() -> {
                                fieldData.jsonArray.toList().map { it.jsonObject }
                            }

                            else -> listOf()
                        }

                        for (mediaFile in mediaFiles) {
                            if (mediaFile.containsKey("documentId")) {
                                val targetDocumentId =
                                    mediaFile["documentId"]?.jsonPrimitive?.content?.trim('"') ?: continue

                                // Get the comparison status for the file
                                val targetStatus = fileStatusMap[targetDocumentId]

                                // Determine the relationship status
                                val relationshipStatus = determineRelationshipStatus(sourceStatus, targetStatus)

                                // Add a relationship to the file
                                relationships.add(
                                    EntryRelationship(
                                        sourceContentType = sourceContentType,
                                        sourceDocumentId = sourceDocumentId,
                                        sourceField = "$sourceField.$fieldName",
                                        targetContentType = STRAPI_FILE_CONTENT_TYPE_NAME,
                                        targetDocumentId = targetDocumentId,
                                        relationType = "media",
                                        compareStatus = relationshipStatus
                                    )
                                )
                            }
                        }
                    }

                    "component" -> {
                        // Handle component attributes
                        if (attribute.component != null) {
                            val nestedComponentType = attribute.component
                            val nestedComponentObj = strapiComponentTypes.find { it.uid == nestedComponentType }

                            if (attribute.repeatable == true && fieldData is JsonArray) {
                                // Handle repeatable components (array of components)
                                for (nestedComponent in fieldData) {
                                    if (nestedComponent is JsonObject) {
                                        // Process each component in the array
                                        val componentRelationships = processComponent(
                                            nestedComponentObj,
                                            contentTypes,
                                            strapiComponentTypes,
                                            nestedComponent.jsonObject,
                                            sourceContentType,
                                            sourceDocumentId,
                                            "$sourceField.$fieldName",
                                            sourceStatus,
                                            singleTypeStatusMap,
                                            collectionTypeStatusMap,
                                            fileStatusMap
                                        )
                                        relationships.addAll(componentRelationships)
                                    }
                                }
                            } else if (fieldData is JsonObject) {
                                // Handle single component
                                val componentRelationships = processComponent(
                                    nestedComponentObj,
                                    contentTypes,
                                    strapiComponentTypes,
                                    fieldData.jsonObject,
                                    sourceContentType,
                                    sourceDocumentId,
                                    "$sourceField.$fieldName",
                                    sourceStatus,
                                    singleTypeStatusMap,
                                    collectionTypeStatusMap,
                                    fileStatusMap
                                )
                                relationships.addAll(componentRelationships)
                            }
                        }
                    }

                    "dynamiczone" -> {
                        // Handle dynamiczone attributes
                        if (fieldData is JsonArray) {
                            for (dynamicComponent in fieldData) {
                                if (dynamicComponent is JsonObject) {
                                    val dynamicComponentType =
                                        dynamicComponent["__component"]?.jsonPrimitive?.content?.trim('"')
                                    val dynamicComponentObj = dynamicComponentType?.let { type ->
                                        strapiComponentTypes.find { it.uid == type }
                                    }

                                    // Process each component in the dynamiczone
                                    val componentRelationships = processComponent(
                                        dynamicComponentObj,
                                        contentTypes,
                                        strapiComponentTypes,
                                        dynamicComponent.jsonObject,
                                        sourceContentType,
                                        sourceDocumentId,
                                        "$sourceField.$fieldName",
                                        sourceStatus,
                                        singleTypeStatusMap,
                                        collectionTypeStatusMap,
                                        fileStatusMap
                                    )
                                    relationships.addAll(componentRelationships)
                                }
                            }
                        }
                    }
                }
            }

            return relationships
        } else {

            // If componentObj is not available, use the original implementation
            // Process each field in the component
            for ((fieldName, fieldValue) in component) {
                // Skip special fields like __component

                if (fieldName == "__component") continue

                // Skip null or empty values
                if (fieldValue is JsonNull) continue
                if (fieldValue is JsonArray && fieldValue.jsonArray.isEmpty()) continue
                if (fieldValue is JsonObject && fieldValue.jsonObject.isEmpty()) continue
                if (fieldValue is JsonPrimitive && fieldName != "documentId") continue

                if (fieldName == "documentId") {

                    val documentId = fieldValue.jsonPrimitive.content
                    val targetStatus = singleTypeStatusMap[documentId] ?: collectionTypeStatusMap[documentId]
                    if (targetStatus == null) {
                        continue
                    }

                    val relationshipStatus = determineRelationshipStatus(sourceStatus, targetStatus.status)

                    // Add a relationship to the file
                    relationships.add(
                        EntryRelationship(
                            sourceContentType = sourceContentType,
                            sourceDocumentId = sourceDocumentId,
                            sourceField = "$sourceField.$fieldName",
                            targetContentType = targetStatus.contentTypeId,
                            targetDocumentId = documentId,
                            relationType = "relation",
                            compareStatus = relationshipStatus
                        )
                    )
                }

                // Handle media fields (files)
                else if (fieldName == "image" || fieldName == "file" || fieldName == "media") {
                    val mediaFiles = when {
                        fieldValue is JsonObject && fieldValue.containsKey("documentId") -> {
                            listOf(fieldValue.jsonObject)
                        }

                        fieldValue is JsonArray && fieldValue.jsonArray.isNotEmpty() -> {
                            fieldValue.jsonArray.toList().map { it.jsonObject }
                        }

                        else -> listOf()
                    }

                    for (mediaFile in mediaFiles) {
                        if (mediaFile.containsKey("documentId")) {
                            val targetDocumentId =
                                mediaFile["documentId"]?.jsonPrimitive?.content?.trim('"') ?: continue

                            // Get the comparison status for the file
                            val targetStatus = fileStatusMap[targetDocumentId]

                            // Determine the relationship status
                            val relationshipStatus = determineRelationshipStatus(sourceStatus, targetStatus)

                            // Add a relationship to the file
                            relationships.add(
                                EntryRelationship(
                                    sourceContentType = sourceContentType,
                                    sourceDocumentId = sourceDocumentId,
                                    sourceField = "$sourceField.$fieldName",
                                    targetContentType = STRAPI_FILE_CONTENT_TYPE_NAME,
                                    targetDocumentId = targetDocumentId,
                                    relationType = "media",
                                    compareStatus = relationshipStatus
                                )
                            )
                        }
                    }
                }

                // Handle relation fields - check for data structure that indicates a relation
                else if (fieldValue is JsonObject && (fieldValue.containsKey("data") || fieldValue.containsKey("documentId"))) {
                    // Handle single relation
                    if (fieldValue.containsKey("data") && fieldValue["data"] is JsonObject) {
                        val data = fieldValue["data"]?.jsonObject
                        val targetDocumentId = data?.get("documentId")?.jsonPrimitive?.content?.trim('"')
                        val targetContentType = data?.get("__type")?.jsonPrimitive?.content?.trim('"')

                        if (targetDocumentId != null && targetContentType != null) {
                            // Get the comparison status for the target
                            val targetStatus = getComparisonStatusForTarget(
                                targetContentType,
                                targetDocumentId,
                                singleTypeStatusMap,
                                collectionTypeStatusMap,
                                fileStatusMap
                            )

                            // Determine the relationship status
                            val relationshipStatus = determineRelationshipStatus(sourceStatus, targetStatus)

                            // Add a relationship
                            relationships.add(
                                EntryRelationship(
                                    sourceContentType = sourceContentType,
                                    sourceDocumentId = sourceDocumentId,
                                    sourceField = "$sourceField.$fieldName",
                                    targetContentType = targetContentType,
                                    targetDocumentId = targetDocumentId,
                                    relationType = "relation",
                                    compareStatus = relationshipStatus
                                )
                            )
                        }
                    }
                    // Handle multiple relations
                    else if (fieldValue.containsKey("data") && fieldValue["data"] is JsonArray) {
                        val dataArray = fieldValue["data"]?.jsonArray ?: continue
                        for (item in dataArray) {
                            if (item is JsonObject) {
                                val targetDocumentId = item["documentId"]?.jsonPrimitive?.content?.trim('"') ?: continue
                                val targetContentType = item["__type"]?.jsonPrimitive?.content?.trim('"') ?: continue

                                // Get the comparison status for the target
                                val targetStatus = getComparisonStatusForTarget(
                                    targetContentType,
                                    targetDocumentId,
                                    singleTypeStatusMap,
                                    collectionTypeStatusMap,
                                    fileStatusMap
                                )

                                // Determine the relationship status
                                val relationshipStatus = determineRelationshipStatus(sourceStatus, targetStatus)

                                // Add a relationship
                                relationships.add(
                                    EntryRelationship(
                                        sourceContentType = sourceContentType,
                                        sourceDocumentId = sourceDocumentId,
                                        sourceField = "$sourceField.$fieldName",
                                        targetContentType = targetContentType,
                                        targetDocumentId = targetDocumentId,
                                        relationType = "relation",
                                        compareStatus = relationshipStatus
                                    )
                                )
                            }
                        }
                    }
                    // Direct documentId reference
                    else if (fieldValue.containsKey("documentId")) {
                        val targetDocumentId = fieldValue["documentId"]?.jsonPrimitive?.content?.trim('"') ?: continue
                        val targetContentType =
                            fieldValue["__type"]?.jsonPrimitive?.content?.trim('"') ?: STRAPI_FILE_CONTENT_TYPE_NAME

                        // Get the comparison status for the target
                        val targetStatus = getComparisonStatusForTarget(
                            targetContentType,
                            targetDocumentId,
                            singleTypeStatusMap,
                            collectionTypeStatusMap,
                            fileStatusMap
                        )

                        // Determine the relationship status
                        val relationshipStatus = determineRelationshipStatus(sourceStatus, targetStatus)

                        // Add a relationship
                        relationships.add(
                            EntryRelationship(
                                sourceContentType = sourceContentType,
                                sourceDocumentId = sourceDocumentId,
                                sourceField = "$sourceField.$fieldName",
                                targetContentType = targetContentType,
                                targetDocumentId = targetDocumentId,
                                relationType = "relation",
                                compareStatus = relationshipStatus
                            )
                        )
                    }
                } else if (fieldValue is JsonArray) {
                    // Process each item in the array as a potential nested component or relation
                    for (item in fieldValue.jsonArray) {
                        if (item is JsonObject) {
                            // Check if this is a component with potential relations
                            val nestedRelationships = processComponent(
                                null,
                                contentTypes,
                                strapiComponentTypes,
                                item,
                                sourceContentType,
                                sourceDocumentId,
                                "$sourceField.$fieldName",
                                sourceStatus,
                                singleTypeStatusMap,
                                collectionTypeStatusMap,
                                fileStatusMap
                            )
                            relationships.addAll(nestedRelationships)
                        }
                    }
                }

                // Handle nested components
                else if (fieldValue is JsonObject) {
                    // Recursively process nested component
                    val nestedRelationships = processComponent(
                        null,
                        contentTypes,
                        strapiComponentTypes,
                        fieldValue,
                        sourceContentType,
                        sourceDocumentId,
                        "$sourceField.$fieldName",
                        sourceStatus,
                        singleTypeStatusMap,
                        collectionTypeStatusMap,
                        fileStatusMap
                    )
                    relationships.addAll(nestedRelationships)
                }

                // Handle arrays (could be repeatable components or relations)
                else if (fieldValue is JsonArray) {
                    for (item in fieldValue) {
                        if (item is JsonObject) {
                            // Check if this is a relation (has documentId)
                            if (item.containsKey("documentId") && item.containsKey("__type")) {
                                val targetDocumentId = item["documentId"]?.jsonPrimitive?.content?.trim('"') ?: continue
                                val targetContentType = item["__type"]?.jsonPrimitive?.content?.trim('"') ?: continue

                                // Get the comparison status for the target
                                val targetStatus = getComparisonStatusForTarget(
                                    targetContentType,
                                    targetDocumentId,
                                    singleTypeStatusMap,
                                    collectionTypeStatusMap,
                                    fileStatusMap
                                )

                                // Determine the relationship status
                                val relationshipStatus = determineRelationshipStatus(sourceStatus, targetStatus)

                                // Add a relationship
                                relationships.add(
                                    EntryRelationship(
                                        sourceContentType = sourceContentType,
                                        sourceDocumentId = sourceDocumentId,
                                        sourceField = "$sourceField.$fieldName",
                                        targetContentType = targetContentType,
                                        targetDocumentId = targetDocumentId,
                                        relationType = "relation",
                                        compareStatus = relationshipStatus
                                    )
                                )
                            } else {
                                // Recursively process as a nested component
                                val nestedRelationships = processComponent(
                                    null,
                                    contentTypes,
                                    strapiComponentTypes,
                                    item,
                                    sourceContentType,
                                    sourceDocumentId,
                                    "$sourceField.$fieldName",
                                    sourceStatus,
                                    singleTypeStatusMap,
                                    collectionTypeStatusMap,
                                    fileStatusMap
                                )
                                relationships.addAll(nestedRelationships)
                            }
                        }
                    }
                }
            }

            return relationships
        }
    }

    /**
     * Determine the relationship status based on source and target status
     */
    private fun determineRelationshipStatus(
        sourceStatus: ContentTypeComparisonResultKind?,
        targetStatus: ContentTypeComparisonResultKind?
    ): ContentTypeComparisonResultKind? {
        // If either status is null, we can't determine the relationship status
        if (sourceStatus == null || targetStatus == null) {
            return null
        }

        // If either the source or target is only in source, the relationship is only in source
        if (sourceStatus == ContentTypeComparisonResultKind.ONLY_IN_SOURCE ||
            targetStatus == ContentTypeComparisonResultKind.ONLY_IN_SOURCE
        ) {
            return ContentTypeComparisonResultKind.ONLY_IN_SOURCE
        }

        // If either the source or target is different, the relationship is different
        if (sourceStatus == ContentTypeComparisonResultKind.DIFFERENT ||
            targetStatus == ContentTypeComparisonResultKind.DIFFERENT
        ) {
            return ContentTypeComparisonResultKind.DIFFERENT
        }

        // If both the source and target are identical, the relationship is identical
        if (sourceStatus == ContentTypeComparisonResultKind.IDENTICAL &&
            targetStatus == ContentTypeComparisonResultKind.IDENTICAL
        ) {
            return ContentTypeComparisonResultKind.IDENTICAL
        }

        // Default case
        return null
    }


    /**
     * Compare files between two Strapi instances using the upload API
     */
    private suspend fun compareFiles(
        mappings: List<MergeRequestDocumentMapping>,
        sourceInstance: StrapiInstance,
        targetInstance: StrapiInstance,
        sourceClient: StrapiClient,
        targetClient: StrapiClient
    ): ContentTypeFileComparisonResult {
        val sourceFiles = sourceClient.getFiles()
        val sourceDocumentIds = sourceFiles.map { it.metadata.documentId }
        val targetFiles = targetClient.getFiles()
        val targetDocumentIds = targetFiles.map { it.metadata.documentId }


        val targetIdsMapWithSourceIds = targetDocumentIds.mapNotNull { targetId ->
            val sourceId = mappings.find { it.targetDocumentId == targetId }?.sourceDocumentId
            sourceId?.let { sourceId to targetId }
        }.toMap()

        val sourceIdsMapWithTargetIds = sourceDocumentIds.mapNotNull { sourceIds ->
            val targetId = mappings.find { it.sourceDocumentId == sourceIds }?.targetDocumentId
            targetId?.let { targetId to sourceIds }
        }.toMap()


        val onlyInSource = sourceFiles.filter {
            it.metadata.documentId !in targetIdsMapWithSourceIds.keys
        }

        val onlyInTarget = targetFiles.filter {
            it.metadata.documentId !in sourceIdsMapWithTargetIds.keys
        }

        val inBoth = sourceFiles.filter {
            it.metadata.documentId in targetIdsMapWithSourceIds.keys
        }

        val different = mutableListOf<DifferentFile>()
        val identical = mutableListOf<StrapiImage>()

        for (sourceFile in inBoth) {
            val sourceId = sourceFile.metadata.documentId
            val targetFile = targetFiles.find {
                it.metadata.documentId == targetIdsMapWithSourceIds[sourceId]
            } ?: continue

            var mapping = mappings.find { it.sourceDocumentId == sourceId }

            if (mapping != null) {
                if (sourceFile.metadata.updatedAt > (mapping.sourceLastUpdateDate ?: OffsetDateTime.now())) {
                    val md5 = calculateMD5Hash(sourceClient.downloadFile(sourceFile).inputStream())
                    mapping = mapping.copy(sourceDocumentMD5 = md5, sourceLastUpdateDate = OffsetDateTime.now())
                    mergeRequestDocumentMappingRepository.updateMapping(
                        mapping.id,
                        mapping
                    )

                }
                if (targetFile.metadata.updatedAt > (mapping.targetLastUpdateDate ?: OffsetDateTime.now())) {
                    val md5 = calculateMD5Hash(targetClient.downloadFile(targetFile).inputStream())
                    mapping = mapping.copy(targetDocumentMD5 = md5, targetLastUpdateDate = OffsetDateTime.now())
                    mergeRequestDocumentMappingRepository.updateMapping(
                        mapping.id,
                        mapping
                    )
                }
            }

            if (mapping?.sourceDocumentMD5 != mapping?.targetDocumentMD5) {
                different.add(DifferentFile(source = sourceFile, target = targetFile))
            } else if (sourceFile.metadata.alternativeText != targetFile.metadata.alternativeText) {
                different.add(DifferentFile(source = sourceFile, target = targetFile))
            } else if (sourceFile.metadata.caption != targetFile.metadata.caption) {
                different.add(DifferentFile(source = sourceFile, target = targetFile))
            } else if (sourceFile.metadata.name != targetFile.metadata.name) {
                different.add(DifferentFile(source = sourceFile, target = targetFile))
            } else
                identical.add(sourceFile)
        }

        return ContentTypeFileComparisonResult(

            onlyInSource = onlyInSource,
            onlyInTarget = onlyInTarget,
            different = different,
            identical = identical,
            contentTypeExists = true,
        )
    }


    /**
     * Compare content for a collection content type
     * @param sourceEntriesCache Optional cache of source entries to avoid repeated API calls
     * @param targetEntriesCache Optional cache of target entries to avoid repeated API calls
     */
    private suspend fun compareContentTypes(
        sourceClient: StrapiClient,
        targetClient: StrapiClient,
        sourceInstance: StrapiInstance,
        targetInstance: StrapiInstance,
        contentType: StrapiContentType,
        sourceEntriesCache: Map<String, List<EntryElement>>? = null,
        targetEntriesCache: Map<String, List<EntryElement>>? = null
    ): ContentTypesComparisonResult {
        // Use cached entries if available, otherwise fetch them
        val sourceEntries = sourceEntriesCache?.get(contentType.uid) ?: listOf()
        val targetEntries = targetEntriesCache?.get(contentType.uid) ?: listOf()

        val sourceIds = sourceEntries.map { it.hash }
        val targetIds = targetEntries.map { it.hash }
        val sourceSlugs = sourceEntries.mapNotNull { it.obj.metadata.slug }
        val targetSlugs = targetEntries.mapNotNull { it.obj.metadata.slug }


        val onlyInSource = sourceEntries.filter {
            it.hash !in targetIds && it.obj.metadata.slug !in targetSlugs
        }

        val onlyInTarget = targetEntries.filter {
            it.hash !in sourceIds && it.obj.metadata.slug !in sourceSlugs
        }

        val inBoth = sourceEntries.filter {
            it.hash in targetIds || it.obj.metadata.slug in targetSlugs
        }

        val different = mutableListOf<DifferentEntry>()
        val identical = mutableListOf<StrapiContent>()

        for (sourceEntry in inBoth) {
            val targetEntry = targetEntries.find {
                it.hash == sourceEntry.hash || (it.obj.metadata.slug != null && it.obj.metadata.slug == sourceEntry.obj.metadata.slug)
            } ?: continue

            val mapping = mergeRequestDocumentMappingRepository.getFilesMappingForInstances(
                sourceInstance.id,
                targetInstance.id,
                sourceEntry.obj.metadata.documentId,
                targetEntry.obj.metadata.documentId
            )
            if (mapping == null) {
                mergeRequestDocumentMappingRepository.createMapping(
                    MergeRequestDocumentMapping(
                        0, sourceInstance.id, targetInstance.id, contentType.uid,
                        sourceEntry.obj.metadata.id, sourceEntry.obj.metadata.documentId,
                        OffsetDateTime.now(), null, targetEntry.obj.metadata.id, targetEntry.obj.metadata.documentId,
                        OffsetDateTime.now(), null
                    )
                )
            }
            if (targetEntry.hash != sourceEntry.hash) {
                different.add(DifferentEntry(source = sourceEntry.obj, target = targetEntry.obj))
            } else {
                identical.add(sourceEntry.obj)
            }
        }

        return ContentTypesComparisonResult(
            contentType = contentType.uid,
            onlyInSource = onlyInSource.map { it.obj },
            onlyInTarget = onlyInTarget.map { it.obj },
            different = different,
            identical = identical,
            kind = contentType.schema.kind
        )
    }

    /**
     * Compare content for a single content type
     * @param sourceEntriesCache Optional cache of source entries to avoid repeated API calls
     * @param targetEntriesCache Optional cache of target entries to avoid repeated API calls
     */
    private suspend fun compareContentType(
        sourceInstance: StrapiInstance,
        targetInstance: StrapiInstance,
        contentType: StrapiContentType,
        sourceEntriesCache: Map<String, List<EntryElement>>? = null,
        targetEntriesCache: Map<String, List<EntryElement>>? = null
    ): ContentTypeComparisonResult {
        // Use cached entries if available, otherwise fetch them
        val sourceEntry = sourceEntriesCache?.get(contentType.uid)?.firstOrNull()
        val targetEntry = targetEntriesCache?.get(contentType.uid)?.firstOrNull()


        val onlyInSource = if (targetEntry == null && sourceEntry != null) sourceEntry else null
        val onlyInTarget = if (targetEntry != null && sourceEntry == null) targetEntry else null

        val different =
            if (sourceEntry != null && targetEntry != null && sourceEntry.hash != targetEntry.hash) DifferentEntry(
                source = sourceEntry.obj,
                target = targetEntry.obj
            ) else null
        val identical =
            if (sourceEntry != null && targetEntry != null && sourceEntry.hash == targetEntry.hash) targetEntry else null

        if (different != null || identical != null) {
            val mapping = mergeRequestDocumentMappingRepository.getFilesMappingForInstances(
                sourceInstance.id,
                targetInstance.id,
                sourceEntry!!.obj.metadata.documentId,
                targetEntry!!.obj.metadata.documentId
            )
            if (mapping == null) {
                mergeRequestDocumentMappingRepository.createMapping(
                    MergeRequestDocumentMapping(
                        0, sourceInstance.id, targetInstance.id, contentType.uid,
                        sourceEntry.obj.metadata.id, sourceEntry.obj.metadata.documentId,
                        OffsetDateTime.now(), null, targetEntry.obj.metadata.id, targetEntry.obj.metadata.documentId,
                        OffsetDateTime.now(), null
                    )
                )
            }
        }

        return ContentTypeComparisonResult(
            contentType = contentType.uid,
            onlyInSource = onlyInSource?.obj,
            onlyInTarget = onlyInTarget?.obj,
            different = different,
            identical = identical?.obj,
            kind = contentType.schema.kind,
            compareKind = when {
                onlyInSource != null -> ContentTypeComparisonResultKind.ONLY_IN_SOURCE
                onlyInTarget != null -> ContentTypeComparisonResultKind.ONLY_IN_TARGET
                different != null -> ContentTypeComparisonResultKind.DIFFERENT
                else -> ContentTypeComparisonResultKind.IDENTICAL
            }
        )
    }


    /**
     * Recursively process component relationships
     * @param relationships The list of relationships to add to
     * @param sourceContentType The source content type UID
     * @param sourceField The source field name
     * @param componentUid The component UID
     * @param componentByUid Map of component UIDs to component objects
     */
    private fun processComponentRelationships(
        relationships: MutableList<ContentRelationship>,
        sourceContentType: String,
        sourceField: String,
        component: StrapiComponent,
        componentByUid: Map<String, StrapiComponent>,
        contentTypeByUid: Map<String, StrapiContentType>
    ) {

        // Add relationship for this component
        relationships.add(
            ContentRelationship(
                sourceContentType = sourceContentType,
                sourceField = sourceField,
                targetContentType = component.uid,
                relationType = "component",
                isBidirectional = false
            )
        )

        // Process component attributes
        for ((nestedFieldName, nestedAttribute) in component.schema.attributes) {
            when (nestedAttribute.type) {
                "component" -> {
                    // Recursively process nested component
                    componentByUid[nestedAttribute.component]?.let { nestedComponent ->
                        processComponentRelationships(
                            relationships,
                            component.uid, // Now the component becomes the source
                            nestedFieldName,
                            nestedComponent,
                            componentByUid,
                            contentTypeByUid
                        )
                    }
                }

                "relation" -> {
                    // Handle relation attributes within components
                    if (nestedAttribute.target != null) {
                        relationships.add(
                            ContentRelationship(
                                sourceContentType = component.uid, // The component is the source
                                sourceField = nestedFieldName,
                                targetContentType = nestedAttribute.target,
                                relationType = nestedAttribute.relation ?: "unknown",
                                isBidirectional = false // Will be updated in second pass
                            )
                        )
                    }
                }

                "media" -> {
                    // Add relationship to files for media attributes within components
                    relationships.add(
                        ContentRelationship(
                            sourceContentType = component.uid,
                            sourceField = nestedFieldName,
                            targetContentType = STRAPI_FILE_CONTENT_TYPE_NAME,
                            relationType = "media",
                            isBidirectional = false
                        )
                    )
                }

                "dynamiczone" -> {
                    // For dynamiczone within components
                    relationships.add(
                        ContentRelationship(
                            sourceContentType = component.uid,
                            sourceField = nestedFieldName,
                            targetContentType = STRAPI_FILE_CONTENT_TYPE_NAME,
                            relationType = "dynamiczone",
                            isBidirectional = false
                        )
                    )
                }
            }
        }
    }

    /**
     * Build a comprehensive relationship graph for content types
     * @return List of ContentRelationship objects
     */
    private fun buildContentTypeRelationships(
        contentTypes: List<StrapiContentType>,
        components: List<StrapiComponent>
    ): List<ContentRelationship> {
        val relationships = mutableListOf<ContentRelationship>()
        val contentTypeByUid: Map<String, StrapiContentType> = contentTypes.associateBy { it.uid }
        val componentByUid = components.associateBy { it.uid }

        // First pass: identify all relationships
        for (contentType in contentTypes) {

            for ((fieldName, attribute) in contentType.schema.attributes) {
                if (attribute.type == "relation" && attribute.target != null) {
                    val targetContentType = contentTypeByUid[attribute.target]
                    if (targetContentType != null) {
                        relationships.add(
                            ContentRelationship(
                                sourceContentType = contentType.uid,
                                sourceField = fieldName,
                                targetContentType = attribute.target,
                                relationType = attribute.relation ?: "unknown",
                                isBidirectional = false // Will be updated in second pass
                            )
                        )
                    }
                } else if (attribute.type == "component") {
                    // Process component and its nested components
                    componentByUid[attribute.component]?.let { component ->
                        processComponentRelationships(
                            relationships,
                            contentType.uid,
                            fieldName,
                            component,
                            componentByUid,
                            contentTypeByUid
                        )
                    }
                } else if (attribute.type == "media") {
                    // Add relationship to files for media attributes
                    relationships.add(
                        ContentRelationship(
                            sourceContentType = contentType.uid,
                            sourceField = fieldName,
                            targetContentType = STRAPI_FILE_CONTENT_TYPE_NAME,
                            relationType = "media",
                            isBidirectional = false
                        )
                    )
                } else if (attribute.type == "dynamiczone") {
                    // For dynamiczone, we add a potential relationship to files
                    // since dynamiczones can contain media components
                    relationships.add(
                        ContentRelationship(
                            sourceContentType = contentType.uid,
                            sourceField = fieldName,
                            targetContentType = STRAPI_FILE_CONTENT_TYPE_NAME,
                            relationType = "dynamiczone",
                            isBidirectional = false
                        )
                    )
                }
            }
        }

        // Second pass: identify bidirectional relationships
        val updatedRelationships = relationships.map { relationship ->
            // Check if there's a reverse relationship
            val isBidirectional = relationships.any { reverseRel ->
                reverseRel.sourceContentType == relationship.targetContentType &&
                        reverseRel.targetContentType == relationship.sourceContentType
            }

            // Find the target field for bidirectional relationships
            val targetField = if (isBidirectional) {
                val sourceContentType = contentTypeByUid[relationship.sourceContentType]
                val targetContentType = contentTypeByUid[relationship.targetContentType]

                if (sourceContentType != null && targetContentType != null) {
                    // Look for the attribute in the target content type that points back to the source
                    targetContentType.schema.attributes.entries.find { (_, attr) ->
                        attr.type == "relation" && attr.target == relationship.sourceContentType &&
                                (attr.inversedBy == relationship.sourceField || attr.mappedBy == relationship.sourceField)
                    }?.key
                } else null
            } else null

            relationship.copy(
                isBidirectional = isBidirectional,
                targetField = targetField
            )
        }

        return updatedRelationships
    }


    /**
     * Extract a single related entry ID from a JSON element
     */
    private fun extractRelateddocumentId(relationData: JsonElement): String? {
        return try {
            when {
                relationData is JsonObject && relationData.containsKey("data") -> {
                    val data = relationData["data"]
                    if (data is JsonObject && data.containsKey("documentId")) {
                        data["documentId"]?.toString()?.trim('"')
                    } else null
                }

                relationData is JsonObject && relationData.containsKey("documentId") -> {
                    relationData["documentId"]?.toString()?.trim('"')
                }

                else -> null
            }
        } catch (e: Exception) {
            println("[DEBUG_LOG] Error extracting related entry ID: ${e.message}")
            null
        }
    }

    /**
     * Extract multiple related entry IDs from a JSON element
     */
    private fun extractRelateddocumentIds(relationData: JsonElement): List<String> {
        return try {
            when {
                relationData is JsonObject && relationData.containsKey("data") -> {
                    val data = relationData["data"]
                    if (data is JsonArray) {
                        data.mapNotNull { item ->
                            if (item is JsonObject && item.containsKey("documentId")) {
                                item["documentId"]?.toString()?.trim('"')
                            } else if (item is JsonObject && item.containsKey("id")) {
                                item["id"]?.toString()?.trim('"')
                            } else null
                        }
                    } else emptyList()
                }

                relationData is JsonArray -> {
                    relationData.mapNotNull { item ->
                        if (item is JsonObject && item.containsKey("documentId")) {
                            item["documentId"]?.toString()?.trim('"')
                        } else if (item is JsonObject && item.containsKey("id")) {
                            item["id"]?.toString()?.trim('"')
                        } else null
                    }
                }

                else -> emptyList()
            }
        } catch (e: Exception) {
            println("[DEBUG_LOG] Error extracting related entry IDs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Calculate all dependencies (direct and transitive) for a given content type
     * @param contentTypeUid The UID of the content type
     * @param relationships The list of content relationships
     * @return List of content type UIDs that the given content type depends on
     */
    private fun calculateAllDependencies(
        contentTypeUid: String,
        relationships: List<ContentRelationship>
    ): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        // Helper function to recursively find dependencies
        fun findDependencies(currentUid: String) {
            if (currentUid in visited) return
            visited.add(currentUid)

            // Find direct dependencies
            val directDependencies = relationships
                .filter { it.sourceContentType == currentUid }
                .map { it.targetContentType }

            for (dependency in directDependencies) {
                if (dependency !in result && dependency != currentUid) {
                    result.add(dependency)
                    // Recursively find dependencies of this dependency
                    findDependencies(dependency)
                }
            }

            // Find component dependencies (where a component depends on a content type)
            val componentDependencies = relationships
                .filter { it.relationType == "component" && it.sourceContentType == currentUid }
                .map { it.targetContentType }

            for (componentUid in componentDependencies) {
                // Find content types that this component depends on
                // Need to handle both the component UID and the collection name format
                val componentName = componentUid.replace("components_", "").replace("_", "-")

                // For components_contacts_section_faq_data_sources, we need to try:
                // 1. components_contacts_section_faq_data_sources (original)
                // 2. contacts-section.faq-data-source (category.name format)

                // Split by hyphen and try to identify the category and name parts
                val parts = componentName.split("-")
                val categoryNameFormat = if (parts.size >= 2) {
                    // Assume first two parts are the category, rest is the name
                    val category = parts.take(2).joinToString("-")
                    val name = parts.drop(2).joinToString("-")
                    "$category.$name"
                } else {
                    componentName
                }

                val possibleComponentSources = listOf(
                    componentUid,
                    categoryNameFormat
                )

                val componentContentDependencies = relationships
                    .filter { possibleComponentSources.contains(it.sourceContentType) && it.relationType != "component" }
                    .map { it.targetContentType }

                for (dependency in componentContentDependencies) {
                    if (dependency !in result && dependency != currentUid && dependency.startsWith("api::")) {
                        result.add(dependency)
                        // Recursively find dependencies of this dependency
                        findDependencies(dependency)
                    }
                }
            }
        }

        findDependencies(contentTypeUid)
        return result
    }

    /**
     * Calculate all content types that depend on a given content type (directly or transitively)
     * @param contentTypeUid The UID of the content type
     * @param relationships The list of content relationships
     * @return List of content type UIDs that depend on the given content type
     */
    private fun calculateAllDependedOnBy(
        contentTypeUid: String,
        relationships: List<ContentRelationship>
    ): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()

        // Helper function to recursively find dependencies
        fun findDependedOnBy(currentUid: String) {
            if (currentUid in visited) return
            visited.add(currentUid)

            // Find direct dependents
            val directDependents = relationships
                .filter { it.targetContentType == currentUid }
                .map { it.sourceContentType }

            for (dependent in directDependents) {
                if (dependent !in result && dependent != currentUid && dependent.startsWith("api::")) {
                    result.add(dependent)
                    // Recursively find content types that depend on this dependent
                    findDependedOnBy(dependent)
                }
            }

            // Find content types that depend on components that depend on this content type
            val componentsUsingThisContent = relationships
                .filter { it.targetContentType == currentUid && it.sourceContentType.contains(".") }
                .map { it.sourceContentType }

            for (componentUid in componentsUsingThisContent) {
                // Find content types that use this component
                val contentTypesUsingComponent = relationships
                    .filter { it.targetContentType == componentUid && it.relationType == "component" }
                    .map { it.sourceContentType }

                for (dependent in contentTypesUsingComponent) {
                    if (dependent !in result && dependent != currentUid && dependent.startsWith("api::")) {
                        result.add(dependent)
                        // Recursively find content types that depend on this dependent
                        findDependedOnBy(dependent)
                    }
                }
            }
        }

        findDependedOnBy(contentTypeUid)
        return result
    }


}
