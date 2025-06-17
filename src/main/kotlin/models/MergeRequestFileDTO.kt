package it.sebi.models

import kotlinx.serialization.Serializable

/**
 * Data transfer object for updating merge request files
 */
@Serializable
data class MergeRequestFileDTO(
    val fileToCreate: List<String> = emptyList(),
    val fileToUpdate: List<String> = emptyList(),
    val fileToDelete: List<String> = emptyList()
)