package it.sebi.repository

import it.sebi.database.dbQuery
import it.sebi.models.Direction
import it.sebi.models.MergeRequestSelection
import it.sebi.models.MergeRequestSelectionDTO
import it.sebi.models.SelectionStatusInfo
import it.sebi.models.STRAPI_FILE_CONTENT_TYPE_NAME
import it.sebi.tables.MergeRequestSelectionsTable
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.inList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime

class MergeRequestSelectionsRepository {

    /**
     * Get all selections for a merge request
     */
    suspend fun getSelectionsForMergeRequest(mergeRequestId: Int): List<MergeRequestSelection> = dbQuery {
        MergeRequestSelectionsTable.selectAll().where { MergeRequestSelectionsTable.mergeRequestId eq mergeRequestId }
            .map {
                MergeRequestSelection(
                    id = it[MergeRequestSelectionsTable.id].value,
                    mergeRequestId = it[MergeRequestSelectionsTable.mergeRequestId].value,
                    contentType = it[MergeRequestSelectionsTable.contentType],
                    documentId = it[MergeRequestSelectionsTable.documentId],
                    direction = it[MergeRequestSelectionsTable.direction],
                    createdAt = it[MergeRequestSelectionsTable.createdAt],
                    syncSuccess = it[MergeRequestSelectionsTable.syncSuccess],
                    syncFailureResponse = it[MergeRequestSelectionsTable.syncFailureResponse],
                    syncDate = it[MergeRequestSelectionsTable.syncDate]
                )
            }
    }

    /**
     * Get selections for a merge request grouped by content type
     */
    suspend fun getSelectionsGroupedByContentType(mergeRequestId: Int): List<MergeRequestSelectionDTO> = dbQuery {
        val selections = MergeRequestSelectionsTable.selectAll()
            .where { MergeRequestSelectionsTable.mergeRequestId eq mergeRequestId }
            .map {
                MergeRequestSelection(
                    id = it[MergeRequestSelectionsTable.id].value,
                    mergeRequestId = it[MergeRequestSelectionsTable.mergeRequestId].value,
                    contentType = it[MergeRequestSelectionsTable.contentType],
                    documentId = it[MergeRequestSelectionsTable.documentId],
                    direction = it[MergeRequestSelectionsTable.direction],
                    createdAt = it[MergeRequestSelectionsTable.createdAt],
                    syncSuccess = it[MergeRequestSelectionsTable.syncSuccess],
                    syncFailureResponse = it[MergeRequestSelectionsTable.syncFailureResponse],
                    syncDate = it[MergeRequestSelectionsTable.syncDate]
                )
            }

        // Group selections by content type
        val groupedSelections = selections.groupBy { it.contentType }

        // Convert to DTOs
        groupedSelections.map { (contentType, entries) ->
            val createEntries = entries.filter { it.direction == Direction.TO_CREATE }
            val updateEntries = entries.filter { it.direction == Direction.TO_UPDATE }
            val deleteEntries = entries.filter { it.direction == Direction.TO_DELETE }

            MergeRequestSelectionDTO(
                contentType = contentType,
                entriesToCreate = createEntries.map { it.documentId },
                entriesToUpdate = updateEntries.map { it.documentId },
                entriesToDelete = deleteEntries.map { it.documentId },
                createStatus = createEntries.map { 
                    SelectionStatusInfo(
                        documentId = it.documentId,
                        syncSuccess = it.syncSuccess,
                        syncFailureResponse = it.syncFailureResponse,
                        syncDate = it.syncDate
                    )
                },
                updateStatus = updateEntries.map { 
                    SelectionStatusInfo(
                        documentId = it.documentId,
                        syncSuccess = it.syncSuccess,
                        syncFailureResponse = it.syncFailureResponse,
                        syncDate = it.syncDate
                    )
                },
                deleteStatus = deleteEntries.map { 
                    SelectionStatusInfo(
                        documentId = it.documentId,
                        syncSuccess = it.syncSuccess,
                        syncFailureResponse = it.syncFailureResponse,
                        syncDate = it.syncDate
                    )
                }
            )
        }
    }

    /**
     * Delete all selections for a merge request
     */
    suspend fun deleteSelectionsForMergeRequest(mergeRequestId: Int): Boolean = dbQuery {
        MergeRequestSelectionsTable.deleteWhere { MergeRequestSelectionsTable.mergeRequestId eq  mergeRequestId } > 0
    } /**
     * Delete all selections for a merge request
     */
    suspend fun deleteAllFiles(mergeRequestId: Int): Boolean = dbQuery {
        MergeRequestSelectionsTable.deleteWhere { MergeRequestSelectionsTable.mergeRequestId eq mergeRequestId and (MergeRequestSelectionsTable.contentType eq STRAPI_FILE_CONTENT_TYPE_NAME) } > 0
    }

    /**
     * Create a new selection
     */
    suspend fun createSelection(
        mergeRequestId: Int,
        contentType: String,
        documentId: String,
        direction: Direction
    ): MergeRequestSelection = dbQuery {
        val insertStatement = MergeRequestSelectionsTable.insert {
            it[MergeRequestSelectionsTable.mergeRequestId] = mergeRequestId
            it[MergeRequestSelectionsTable.contentType] = contentType
            it[MergeRequestSelectionsTable.documentId] = documentId
            it[MergeRequestSelectionsTable.direction] = direction
        }

        val resultRow = insertStatement.resultedValues?.singleOrNull()
            ?: throw IllegalStateException("Insert failed")

        MergeRequestSelection(
            id = resultRow[MergeRequestSelectionsTable.id].value,
            mergeRequestId = resultRow[MergeRequestSelectionsTable.mergeRequestId].value,
            contentType = resultRow[MergeRequestSelectionsTable.contentType],
            documentId = resultRow[MergeRequestSelectionsTable.documentId],
            direction = resultRow[MergeRequestSelectionsTable.direction],
            createdAt = resultRow[MergeRequestSelectionsTable.createdAt]
        )
    }


    /**
     * Update a single selection for a merge request
     * Adds or removes a selection based on the isSelected parameter
     */
    suspend fun updateSingleSelection(
        mergeRequestId: Int,
        contentType: String,
        documentId: String,
        direction: Direction,
        isSelected: Boolean
    ): Boolean = dbQuery {
        if (isSelected) {
            // Check if the selection already exists
            val existingSelection = MergeRequestSelectionsTable.selectAll()
                .where {
                    (MergeRequestSelectionsTable.mergeRequestId eq mergeRequestId) and
                    (MergeRequestSelectionsTable.contentType eq contentType) and
                    (MergeRequestSelectionsTable.documentId eq documentId) and
                    (MergeRequestSelectionsTable.direction eq direction)
                }
                .toList().map { it[MergeRequestSelectionsTable.id].value }

            if (existingSelection.isEmpty()) {
                // Add the selection
                MergeRequestSelectionsTable.insert {
                    it[MergeRequestSelectionsTable.mergeRequestId] = mergeRequestId
                    it[MergeRequestSelectionsTable.contentType] = contentType
                    it[MergeRequestSelectionsTable.documentId] = documentId
                    it[MergeRequestSelectionsTable.direction] = direction
                }
            } else if(existingSelection.size > 1) {
                MergeRequestSelectionsTable.deleteWhere { MergeRequestSelectionsTable.id inList  existingSelection.drop(1) }
            }
            true
        } else {
            // Remove the selection
            val deleted = MergeRequestSelectionsTable.deleteWhere {
                (MergeRequestSelectionsTable.mergeRequestId eq mergeRequestId) and
                (MergeRequestSelectionsTable.contentType eq contentType) and
                (MergeRequestSelectionsTable.documentId eq documentId) and
                (MergeRequestSelectionsTable.direction eq direction)
            } > 0
            deleted
        }
    }

    /**
     * Update all selections for a specific content type and direction
     * Adds or removes all selections based on the isSelected parameter
     */
    suspend fun updateAllSelections(
        mergeRequestId: Int,
        contentType: String,
        direction: Direction,
        documentIds: List<String>,
        isSelected: Boolean
    ): Boolean = dbQuery {
        if (isSelected) {
            // Add all selections that don't already exist
            documentIds.forEach { documentId ->
                // Check if the selection already exists
                val existingSelection = MergeRequestSelectionsTable.selectAll()
                    .where {
                        (MergeRequestSelectionsTable.mergeRequestId eq mergeRequestId) and
                        (MergeRequestSelectionsTable.contentType eq contentType) and
                        (MergeRequestSelectionsTable.documentId eq documentId) and
                        (MergeRequestSelectionsTable.direction eq direction)
                    }
                    .singleOrNull()

                if (existingSelection == null) {
                    // Add the selection
                    MergeRequestSelectionsTable.insert {
                        it[MergeRequestSelectionsTable.mergeRequestId] = mergeRequestId
                        it[MergeRequestSelectionsTable.contentType] = contentType
                        it[MergeRequestSelectionsTable.documentId] = documentId
                        it[MergeRequestSelectionsTable.direction] = direction
                    }
                }
            }
            true
        } else {
            // Remove all selections for this content type and direction
            // Since we can't use 'inList' directly, we'll delete each document ID individually
            var anyDeleted = false
            documentIds.forEach { documentId ->
                val deleted = MergeRequestSelectionsTable.deleteWhere {
                    (MergeRequestSelectionsTable.mergeRequestId eq mergeRequestId) and
                    (MergeRequestSelectionsTable.contentType eq contentType) and
                    (MergeRequestSelectionsTable.documentId eq documentId) and
                    (MergeRequestSelectionsTable.direction eq direction)
                } > 0
                if (deleted) anyDeleted = true
            }
            anyDeleted
        }
    }

    /**
     * Update the sync status of a selection
     */
    suspend fun updateSyncStatus(
        id: Int,
        success: Boolean,
        failureResponse: String? = null
    ): Boolean = dbQuery {
        MergeRequestSelectionsTable.update({ MergeRequestSelectionsTable.id eq id }) {
            it[syncSuccess] = success
            it[syncFailureResponse] = failureResponse
            it[syncDate] = OffsetDateTime.now()
        } > 0
    }
}
