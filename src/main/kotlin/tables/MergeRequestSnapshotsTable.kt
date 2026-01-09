package it.sebi.tables

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

/**
 * Database table for tracking merge request snapshots
 */
object MergeRequestSnapshotsTable : IntIdTable("merge_request_snapshots") {
    val mergeRequestId = integer("merge_request_id").references(MergeRequestsTable.id)
    val snapshotSchemaName = varchar("snapshot_schema_name", 255)
    val createdAt = timestampWithTimeZone("created_at").default(OffsetDateTime.now())
}
