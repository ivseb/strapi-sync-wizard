package it.sebi.models

import kotlinx.serialization.Serializable

@Serializable
data class FilesRelatedMph(
    val id: Int,
    val fileId: Int?,
    val relatedId: Int?,
    val relatedType: String?,
    val field: String?,
    val order: Double?
)
