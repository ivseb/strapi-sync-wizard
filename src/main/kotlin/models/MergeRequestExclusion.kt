package it.sebi.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * Rappresenta una regola di esclusione per la sincronizzazione.
 * Può escludere un intero Content Type, un'entità specifica (tramite documentId) 
 * o un campo specifico (tramite fieldPath).
 */
@Serializable
data class MergeRequestExclusion(
    val id: Int = 0,
    val sourceStrapiId: Int,
    val targetStrapiId: Int,
    val contentType: String,
    val documentId: String? = null,
    val fieldPath: String? = null,
    @Contextual val createdAt: OffsetDateTime = OffsetDateTime.now()
)

@Serializable
data class ExclusionRequestDTO(
    val contentType: String,
    val documentId: String? = null,
    val fieldPath: String? = null
)

@Serializable
data class ExclusionResponseDTO(
    val success: Boolean,
    val data: List<MergeRequestExclusion> = emptyList(),
    val message: String? = null
)
