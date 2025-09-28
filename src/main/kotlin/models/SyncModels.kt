package it.sebi.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Result of schema compatibility check
 */
@Serializable
data class SchemaCompatibilityResult(
    val isCompatible: Boolean,
    val missingInTarget: List<StrapiContentType>,
    val missingInSource: List<StrapiContentType>,
    val incompatibleContentTypes: List<ContentTypeIncompatibility>,
    val sourceContentTypes: List<StrapiContentType> = listOf(),
    val targetContentTypes: List<StrapiContentType> = listOf(),
    val missingComponentsInTarget: List<StrapiComponent> = listOf(),
    val missingComponentsInSource: List<StrapiComponent> = listOf(),
    val incompatibleComponents: List<ComponentIncompatibility> = listOf(),
    val sourceComponents: List<StrapiComponent> = listOf(),
    val targetComponents: List<StrapiComponent> = listOf(),
)

/**
 * Details about incompatibility in a content type
 */
@Serializable
data class ContentTypeIncompatibility(
    val contentType: String,
    val reason: String,
    val attributes: List<AttributeIncompatibility> = emptyList()
)

/**
 * Details about incompatibility in a component
 */
@Serializable
data class ComponentIncompatibility(
    val component: String,
    val reason: String,
    val attributes: List<AttributeIncompatibility> = emptyList()
)

/**
 * Details about incompatibility in an attribute
 */
@Serializable
data class AttributeIncompatibility(
    val attributeName: String,
    val reason: String
)


@Serializable
enum class ContentTypeComparisonResultKind {
    @SerialName("ONLY_IN_SOURCE")
    ONLY_IN_SOURCE,

    @SerialName("ONLY_IN_TARGET")
    ONLY_IN_TARGET,

    @SerialName("DIFFERENT")
    DIFFERENT,

    @SerialName("IDENTICAL")
    IDENTICAL
}


@Serializable
data class ContentTypeFileComparisonResult(
    val sourceImage: StrapiImage?,
    val targetImage: StrapiImage?,
    val compareState: ContentTypeComparisonResultKind
) {
    @OptIn(ExperimentalUuidApi::class)
    val id = sourceImage?.metadata?.documentId ?: targetImage?.metadata?.documentId ?: Uuid.random().toString()

    fun toContentTypeComparisonResultWithRelationships(): ContentTypeComparisonResultWithRelationships {
        return ContentTypeComparisonResultWithRelationships(
            id = id,
            tableName = "files",
            contentType = "files",
            sourceContent = sourceImage?.let { (metadata, rawData) ->
                StrapiContent(
                    metadata = StrapiContentMetadata(
                        id = metadata.id,
                        documentId = metadata.documentId,
                        uniqueKey = metadata.documentId,
                        locale = metadata.locale
                    ),
                    rawData = rawData,
                    cleanData = rawData,
                    links = listOf()
                )
            },
            targetContent = targetImage?.let { (metadata, rawData) ->
                StrapiContent(
                    metadata = StrapiContentMetadata(
                        id = metadata.id,
                        documentId = metadata.documentId,
                        uniqueKey = metadata.documentId,
                        locale = metadata.locale
                    ),
                    rawData = rawData,
                    cleanData = rawData,
                    links = listOf()
                )
            },
            compareState = compareState,
            kind = StrapiContentTypeKind.Files,
        )
    }

    @Transient
    val asContent = toContentTypeComparisonResultWithRelationships()
}


/**
 * Result of content synchronization
 */
@Serializable
data class SyncResult(
    val contentType: String,
    val created: List<String>,
    val updated: List<String>,
    val failed: List<String>,
    val kind: StrapiContentTypeKind
)

/**
 * Represents a relationship between content types
 */
@Serializable
data class ContentRelationship(
    val sourceContentType: String,
    val sourceField: String,
    val targetContentType: String,
    val targetField: String? = null,
    val relationType: String,
    val isBidirectional: Boolean = false
)

/**
 * Represents a relationship between specific content entries
 */
@Serializable
data class EntryRelationship(
    val sourceContentType: String,
    val sourceDocumentId: String,
    val sourceField: String,
    val targetContentType: String,
    val targetDocumentId: String,
    val targetField: String? = null,
    val relationType: String,
    val compareStatus: ContentTypeComparisonResultKind?
)

/**
 * Enhanced comparison result with relationship information
 */
@Serializable
data class ContentTypeComparisonResultWithRelationships(
    val id: String,
    val tableName: String,
    val contentType: String,
    val sourceContent: StrapiContent?,
    val targetContent: StrapiContent?,
    val compareState: ContentTypeComparisonResultKind,
    val kind: StrapiContentTypeKind,
)

/**
 * Enhanced comparison result with relationship information for collection types
 */

/**
 * Enhanced comparison result map with relationship information
 */
@Serializable
data class ContentTypeComparisonResultMapWithRelationships(
    val files: List<ContentTypeFileComparisonResult> = listOf(),
    val singleTypes: Map<String, ContentTypeComparisonResultWithRelationships> = emptyMap(),
    val collectionTypes: Map<String, List<ContentTypeComparisonResultWithRelationships>> = emptyMap(),
    val contentTypeRelationships: List<ContentRelationship> = emptyList()
)


/**
 * Represents a dependency that must be selected when selecting a content type entry
 */
@Serializable
data class SelectedContentTypeDependency(
    val contentType: String,
    val documentId: String,
    val relationshipPath: List<EntryRelationship> = emptyList()
)

/**
 * Data class to hold all the necessary data for a merge request
 */
@Serializable
data class MergeRequestData(
    val files: List<ContentTypeFileComparisonResult>,
    val singleTypes: Map<String, ContentTypeComparisonResultWithRelationships>,
    val collectionTypes: Map<String, List<ContentTypeComparisonResultWithRelationships>>,
    val contentTypeRelationships: List<ContentRelationship> = emptyList(),
    val selections: List<MergeRequestSelectionDTO> = emptyList()
)
