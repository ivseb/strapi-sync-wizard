package it.sebi.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * DTO representing a single manual mapping item to upsert
 */
@Serializable
data class ManualMappingItemDTO(
    val contentType: String,
    val sourceDocumentId: String,
    val sourceId: Int,
    val targetDocumentId: String,
    val targetId: Int,
    val locale: String? = null
)

/**
 * Bulk manual mappings request DTO
 */
@Serializable
data class ManualMappingsRequestDTO(
    val items: List<ManualMappingItemDTO>
)

/**
 * Response of manual mappings upsert containing refreshed data
 */
@Serializable
data class ManualMappingsResponseDTO(
    val success: Boolean,
    val data: MergeRequestData? = null,
    val message: String? = null
)

@Serializable
data class ManualMappingWithContentDTO(
    val id: Int,
    val contentType: String,
    val sourceDocumentId: String? = null,
    val targetDocumentId: String? = null,
    val locale: String? = null,
    val sourceJson: JsonObject? = null,
    val targetJson: JsonObject? = null
)

@Serializable
data class ManualMappingsListResponseDTO(
    val success: Boolean,
    val data: List<ManualMappingWithContentDTO> = emptyList(),
    val message: String? = null
)
