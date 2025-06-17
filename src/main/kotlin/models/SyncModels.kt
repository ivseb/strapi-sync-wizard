package it.sebi.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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


@Serializable
data class SchemaCompatibilityResultDTO(
    val isCompatible: Boolean,
    val missingInTarget: List<StrapiContentType>,
    val missingInSource: List<StrapiContentType>,
    val incompatibleContentTypes: List<ContentTypeIncompatibility>,
    val missingComponentsInTarget: List<StrapiComponent> = listOf(),
    val missingComponentsInSource: List<StrapiComponent> = listOf(),
    val incompatibleComponents: List<ComponentIncompatibility> = listOf()
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
data class ContentTypeComparisonResult(
    val contentType: String,
    val onlyInSource: StrapiContent?,
    val onlyInTarget: StrapiContent?,
    val different: DifferentEntry?,
    val identical: StrapiContent?,
    val kind: StrapiContentTypeKind,
    val compareKind: ContentTypeComparisonResultKind
)


/**
 * Result of content type comparison
 */
@Serializable
data class ContentTypesComparisonResult(
    val contentType: String,
    val onlyInSource: List<StrapiContent>,
    val onlyInTarget: List<StrapiContent>,
    val different: List<DifferentEntry>,
    val identical: List<StrapiContent>,
    val kind: StrapiContentTypeKind
)


@Serializable
data class ContentTypeFileComparisonResult(
    val onlyInSource: List<StrapiImage>,
    val onlyInTarget: List<StrapiImage>,
    val different: List<DifferentFile>,
    val identical: List<StrapiImage>,
    val contentTypeExists: Boolean
)


/**
 * Represents a pair of entries that are different between source and target
 */
@Serializable
data class DifferentEntry(
    val source: StrapiContent,
    val target: StrapiContent
)

/**
 * Represents a pair of entries that are different between source and target
 */
@Serializable
data class DifferentFile(
    val source: StrapiImage,
    val target: StrapiImage
)



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
    val contentType: String,
    val onlyInSource: StrapiContent?,
    val onlyInTarget: StrapiContent?,
    val different: DifferentEntry?,
    val identical: StrapiContent?,
    val kind: StrapiContentTypeKind,
    val compareKind: ContentTypeComparisonResultKind,
    val relationships: List<EntryRelationship> = emptyList(),
    val dependsOn: List<String> = emptyList(),
    val dependedOnBy: List<String> = emptyList()
)

/**
 * Enhanced comparison result with relationship information for collection types
 */
@Serializable
data class ContentTypesComparisonResultWithRelationships(
    val contentType: String,
    val onlyInSource: List<StrapiContent>,
    val onlyInTarget: List<StrapiContent>,
    val different: List<DifferentEntry>,
    val identical: List<StrapiContent>,
    val kind: StrapiContentTypeKind,
    val relationships: Map<String, List<EntryRelationship>> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    val dependedOnBy: List<String> = emptyList()
)

/**
 * Enhanced comparison result map with relationship information
 */
@Serializable
data class ContentTypeComparisonResultMapWithRelationships(
    val files: ContentTypeFileComparisonResult,
    val singleTypes: Map<String, ContentTypeComparisonResultWithRelationships>,
    val collectionTypes: Map<String, ContentTypesComparisonResultWithRelationships>,
    val contentTypeRelationships: List<ContentRelationship> = emptyList()
)

/**
 * Selected entries for a content type
 */
@Serializable
data class SelectedContentTypeEntries(
    val contentType: String,
    val entriesToCreate: List<String> = emptyList(),
    val entriesToUpdate: List<String> = emptyList(),
    val entriesToDelete: List<String> = emptyList(),
    val requiredDependencies: List<SelectedContentTypeDependency> = emptyList()
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
    val files: ContentTypeFileComparisonResult,
    val singleTypes: Map<String, ContentTypeComparisonResultWithRelationships>,
    val collectionTypes: Map<String, ContentTypesComparisonResultWithRelationships>,
    val contentTypeRelationships: List<ContentRelationship> = emptyList(),
    val selections: List<MergeRequestSelectionDTO> = emptyList()
)
