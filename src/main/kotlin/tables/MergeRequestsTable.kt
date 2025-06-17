package it.sebi.tables

import it.sebi.models.MergeRequestStatus
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

val format = Json { prettyPrint = true }

/**
 * Database table for merge requests
 */
object MergeRequestsTable : IntIdTable("merge_requests") {
    val name = varchar("name", 255)
    val description = text("description")
    val sourceInstanceId = integer("source_instance_id").references(StrapiInstancesTable.id)
    val targetInstanceId = integer("target_instance_id").references(StrapiInstancesTable.id)
    val status = enumeration("status", MergeRequestStatus::class)
    val createdAt = timestampWithTimeZone("created_at").default(OffsetDateTime.now())
    val updatedAt = timestampWithTimeZone("updated_at").default(OffsetDateTime.now())
}
