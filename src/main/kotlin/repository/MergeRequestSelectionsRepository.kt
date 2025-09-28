package it.sebi.repository

import it.sebi.database.dbQuery
import it.sebi.models.Direction
import it.sebi.models.MergeRequestSelection
import it.sebi.models.MergeRequestSelectionDTO
import it.sebi.tables.MergeRequestSelectionsTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import org.jetbrains.exposed.v1.core.neq

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
                    tableName = it[MergeRequestSelectionsTable.table],
                    documentId = it[MergeRequestSelectionsTable.documentId],
                    direction = it[MergeRequestSelectionsTable.direction],
                    createdAt = it[MergeRequestSelectionsTable.createdAt],
                    syncSuccess = it[MergeRequestSelectionsTable.syncSuccess],
                    syncFailureResponse = it[MergeRequestSelectionsTable.syncFailureResponse],
                    syncDate = it[MergeRequestSelectionsTable.syncDate]
                )
            }.toList()
    }

    /**
     * Get selections for a merge request grouped by content type
     */
    suspend fun getSelectionsGroupedByTableName(mergeRequestId: Int): List<MergeRequestSelectionDTO> = dbQuery {
        val selections = MergeRequestSelectionsTable.selectAll()
            .where { MergeRequestSelectionsTable.mergeRequestId eq mergeRequestId }
            .map {
                MergeRequestSelection(
                    id = it[MergeRequestSelectionsTable.id].value,
                    mergeRequestId = it[MergeRequestSelectionsTable.mergeRequestId].value,
                    tableName = it[MergeRequestSelectionsTable.table],
                    documentId = it[MergeRequestSelectionsTable.documentId],
                    direction = it[MergeRequestSelectionsTable.direction],
                    createdAt = it[MergeRequestSelectionsTable.createdAt],
                    syncSuccess = it[MergeRequestSelectionsTable.syncSuccess],
                    syncFailureResponse = it[MergeRequestSelectionsTable.syncFailureResponse],
                    syncDate = it[MergeRequestSelectionsTable.syncDate]
                )
            }.toList()

        // Group selections by content type
        val groupedSelections = selections.groupBy { it.tableName }

        // Convert to DTOs
        groupedSelections.map { (contentType, entries) ->

            MergeRequestSelectionDTO(
                tableName = contentType,
                selections =  entries,
            )
        }
    }

    /**
     * Delete all selections for a merge request
     */
    suspend fun deleteSelectionsForMergeRequest(mergeRequestId: Int): Boolean = dbQuery {
        MergeRequestSelectionsTable.deleteWhere { MergeRequestSelectionsTable.mergeRequestId eq  mergeRequestId } > 0
    }



    /**
     * Update the sync status of a selection
     */
    suspend fun updateSyncStatus(
        id: Int,
        success: Boolean,
        failureResponse: String? = null
    ): Boolean {
        // Perform DB update and gather data needed for SSE update
        data class RowData(
            val mergeRequestId: Int,
            val tableName: String,
            val documentId: String,
            val direction: Direction,
            val totalItems: Int,
            val processedItems: Int
        )
        val row: RowData? = dbQuery {
            // Update row first
            val updated = MergeRequestSelectionsTable.update({ MergeRequestSelectionsTable.id eq id }) {
                it[syncSuccess] = success
                it[syncFailureResponse] = failureResponse
                it[syncDate] = OffsetDateTime.now()
            } > 0

            // If not updated, return null
            if (!updated) return@dbQuery null

            // Fetch the selection row data
            val sel = MergeRequestSelectionsTable.selectAll()
                .where { MergeRequestSelectionsTable.id eq id }
                .single()

            val mrId = sel[MergeRequestSelectionsTable.mergeRequestId].value
            val tableName = sel[MergeRequestSelectionsTable.table]
            val documentId = sel[MergeRequestSelectionsTable.documentId]
            val direction = sel[MergeRequestSelectionsTable.direction]

            // Compute counts
            val totalItems = MergeRequestSelectionsTable.selectAll()
                .where { MergeRequestSelectionsTable.mergeRequestId eq mrId }
                .count().toInt()
            val processedItems = MergeRequestSelectionsTable.selectAll()
                .where { (MergeRequestSelectionsTable.mergeRequestId eq mrId) and (MergeRequestSelectionsTable.syncDate neq null) }
                .count().toInt()

            RowData(
                mergeRequestId = mrId,
                tableName = tableName,
                documentId = documentId,
                direction = direction,
                totalItems = totalItems,
                processedItems = processedItems
            )
        }

        if (row != null) {
            // Emit SSE progress update
            try {
                it.sebi.SyncProgressService.sendProgressUpdate(
                    it.sebi.SyncProgressUpdate(
                        mergeRequestId = row.mergeRequestId,
                        totalItems = row.totalItems,
                        processedItems = row.processedItems,
                        currentItem = "${row.tableName}:${row.documentId}",
                        currentItemType = row.tableName,
                        currentOperation = row.direction.name,
                        status = if (success) "SUCCESS" else "ERROR",
                        message = failureResponse
                    )
                )
            } catch (_: Exception) {
                // Ignore SSE errors
            }
            return true
        }
        return false
    }

    /**
     * Bulk upsert (insert if not exists) and bulk delete of selections.
     * This method minimizes queries by:
     * - Fetching existing selections once
     * - Inserting only missing rows
     * - Deleting grouped by (contentType, direction) with documentId IN (...)
     */
    suspend fun bulkUpsertAndDelete(
        mergeRequestId: Int,
        upserts: List<Triple<String, String, Direction>>,
        deletes: List<Triple<String, String, Direction>>
    ): Boolean = dbQuery {
        val distinctUpserts = upserts.distinct()
        val distinctDeletes = deletes.distinct()

        // Load all existing keys for this MR once
        val existingKeys: Set<Triple<String, String, Direction>> = MergeRequestSelectionsTable
            .selectAll()
            .where { MergeRequestSelectionsTable.mergeRequestId eq mergeRequestId }
            .map {
                Triple(
                    it[MergeRequestSelectionsTable.table],
                    it[MergeRequestSelectionsTable.documentId],
                    it[MergeRequestSelectionsTable.direction]
                )
            }
            .toSet()

        // Compute missing inserts
        val toInsert = distinctUpserts.filterNot { existingKeys.contains(it) }


        MergeRequestSelectionsTable.batchInsert(toInsert) { (contentType, documentId, direction) ->
            this[MergeRequestSelectionsTable.mergeRequestId] = mergeRequestId
            this[MergeRequestSelectionsTable.table] = contentType
            this[MergeRequestSelectionsTable.documentId] = documentId
            this[MergeRequestSelectionsTable.direction] = direction
        }

        // Perform deletes grouping by (contentType, direction)
        val deletesByGroup: Map<Pair<String, Direction>, List<String>> = distinctDeletes
            .groupBy({ it.first to it.third }, { it.second })
            .mapValues { (_, docs) -> docs.distinct() }


        for ((key, docs) in deletesByGroup) {
            val (contentType, direction) = key
            if (docs.isEmpty()) continue
            MergeRequestSelectionsTable.deleteWhere {
                (MergeRequestSelectionsTable.mergeRequestId eq mergeRequestId) and
                (MergeRequestSelectionsTable.table eq contentType) and
                (MergeRequestSelectionsTable.direction eq direction) and
                (MergeRequestSelectionsTable.documentId inList docs)
            }
        }

        true
    }
}
