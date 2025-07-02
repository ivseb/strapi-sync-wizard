package it.sebi.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime


@Serializable
data class MergeRequestSelectionData(
   val data:List<MergeRequestSelection>
)

/**
 * Represents a selection for a merge request
 */
@Serializable
data class MergeRequestSelection(
    val id: Int,
    val mergeRequestId: Int,
    val contentType: String,
    val documentId: String,
    val direction: Direction,
    @Contextual
    val createdAt: OffsetDateTime,
    val syncSuccess: Boolean? = null,
    val syncFailureResponse: String? = null,
    @Contextual
    val syncDate: OffsetDateTime? = null
)



/**
 * Data transfer object for merge request selections
 */
@Serializable
data class MergeRequestSelectionDataDTO(
    val data: List<MergeRequestSelectionDTO>,
)


/**
 * Data transfer object for selection status information
 */
@Serializable
data class SelectionStatusInfo(
    val documentId: String,
    val syncSuccess: Boolean?,
    val syncFailureResponse: String?,
    @Contextual
    val syncDate: OffsetDateTime?
)

/**
 * Data transfer object for merge request selections
 */
@Serializable
data class MergeRequestSelectionDTO(
    val contentType: String,
    val entriesToCreate: List<String> = emptyList(),
    val entriesToUpdate: List<String> = emptyList(),
    val entriesToDelete: List<String> = emptyList(),
    val createStatus: List<SelectionStatusInfo> = emptyList(),
    val updateStatus: List<SelectionStatusInfo> = emptyList(),
    val deleteStatus: List<SelectionStatusInfo> = emptyList()
)

/**
 * Data transfer object for a single selection update
 */
@Serializable
data class SingleSelectionDTO(
    val contentType: String,
    val documentId: String,
    val direction: String,
    val isSelected: Boolean
)

/**
 * Data transfer object for bulk selection updates
 */
@Serializable
data class BulkSelectionDTO(
    val contentType: String,
    val direction: String,
    val documentIds: List<String>,
    val isSelected: Boolean
)

/**
 * Data transfer object for the response to a selection update
 */
@Serializable
data class SelectionUpdateResponseDTO(
    val success: Boolean,
    val additionalSelections: List<RelatedSelectionDTO> = emptyList()
)

/**
 * Data transfer object for a related selection that was automatically selected
 */
@Serializable
data class RelatedSelectionDTO(
    val contentType: String,
    val documentId: String,
    val direction: Direction
)
