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
    val isCompatible: Boolean,
    val blocking: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

/**
 * Response for merge operation
 */
/**
 * Post-sync verification: after a merge, recompute the comparison and confirm each successfully
 * synced item is actually consistent on the target (create/update -> IDENTICAL, delete -> absent).
 */
@Serializable
data class MergeVerificationItem(
    val contentType: String,
    val documentId: String,
    val direction: String,
    val expected: String,
    val actual: String,
    val consistent: Boolean,
    // "ok" (matches), "schema_gap" (differs only in source-only fields the target schema can't store —
    // not a real failure), or "mismatch" (genuine content/value difference).
    val severity: String = "ok",
    val reason: String? = null
)

@Serializable
data class MergeVerificationReport(
    val mergeRequestId: Int,
    val total: Int,
    val consistent: Int,
    val schemaGap: Int,
    val inconsistent: Int,
    val items: List<MergeVerificationItem>
)

@Serializable
data class MergeResponse(
    val success: Boolean,
    val message: String,
    // Per-run outcome counts (present for the complete/sync response), so the UI can report partial
    // failures honestly instead of always claiming success.
    val total: Int? = null,
    val succeeded: Int? = null,
    val failed: Int? = null
)