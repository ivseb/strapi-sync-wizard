package it.sebi.models

import kotlinx.serialization.Serializable


/**
 * Details about schema error
 */
@Serializable
data class SchemaErrorDetails(
    val isCompatible: Boolean,
    val missingInTarget: List<String>,
    val missingInSource: List<String>,
    val incompatibleContentTypes: List<ContentTypeIncompatibilityDTO>
)

/**
 * DTO for content type incompatibility
 */
@Serializable
data class ContentTypeIncompatibilityDTO(
    val contentType: String,
    val reason: String,
    val attributes: List<AttributeIncompatibilityDTO> = emptyList()
)

/**
 * DTO for attribute incompatibility
 */
@Serializable
data class AttributeIncompatibilityDTO(
    val attributeName: String,
    val reason: String
)

/**
 * Response for schema compatibility check
 */
@Serializable
data class SchemaCompatibilityResponse(
    val isCompatible: Boolean
)

/**
 * Response for merge operation
 */
@Serializable
data class MergeResponse(
    val success: Boolean,
    val message: String
)