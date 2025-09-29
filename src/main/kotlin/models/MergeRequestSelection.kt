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
    val tableName: String,
    val documentId: String,
    val direction: Direction,
    @Contextual
    val createdAt: OffsetDateTime,
    val syncSuccess: Boolean? = null,
    val syncFailureResponse: String? = null,
    @Contextual
    val syncDate: OffsetDateTime? = null

){
}



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
    val tableName: String,
    val selections: List<MergeRequestSelection> = emptyList()
)




/**
 * Data transfer object for the response to a selection update
 */
@Serializable
data class SelectionUpdateResponseDTO(
    val success: Boolean
)



/**
 * Unified selection command DTO to handle single, list, or all items
 * - If ids is provided and non-empty: toggle selection for those items
 * - If all is true: toggle selection for all diff items in the comparison
 */
@Serializable
data class UnifiedSelectionDTO(
    val kind: StrapiContentTypeKind,
    val tableName: String?=null,
    val ids: List<String>? = null,
    val selectAllKind: ContentTypeComparisonResultKind? = null,
    val isSelected: Boolean
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
