package it.sebi.tables

import it.sebi.models.Direction
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

/**
 * Database table for merge request selections
 */
object MergeRequestSelectionsTable : IntIdTable("merge_request_selections") {
    val mergeRequestId = reference("merge_request_id", MergeRequestsTable)
    val table = varchar("content_type", 255)
    val documentId = varchar("documentId", 255)
    val direction = enumeration("direction", Direction::class)
    val createdAt = timestampWithTimeZone("created_at").default(OffsetDateTime.now())
    val syncSuccess = bool("sync_success").nullable()
    val syncFailureResponse = text("sync_failure_response").nullable()
    val syncDate = timestampWithTimeZone("sync_date").nullable()
}
