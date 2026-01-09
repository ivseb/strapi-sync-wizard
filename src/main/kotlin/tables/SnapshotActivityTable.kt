package it.sebi.tables

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

/**
 * Database table for tracking snapshot and restore activities
 */
object SnapshotActivityTable : IntIdTable("snapshot_activities") {
    val mergeRequestId = integer("merge_request_id").references(MergeRequestsTable.id)
    val activityType = varchar("activity_type", 50) // TAKE_SNAPSHOT, RESTORE_SNAPSHOT, DELETE_SNAPSHOT
    val status = varchar("status", 50) // SUCCESS, FAILED
    val snapshotSchemaName = varchar("schema_name", 255).nullable()
    val message = text("message").nullable()
    val createdAt = timestampWithTimeZone("created_at").default(OffsetDateTime.now())
}
