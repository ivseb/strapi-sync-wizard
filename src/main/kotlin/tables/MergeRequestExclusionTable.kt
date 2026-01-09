package it.sebi.tables

import it.sebi.database.dbQuery
import it.sebi.models.MergeRequestExclusion
import it.sebi.models.MergeRequestWithInstancesDTO
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.OffsetDateTime

/**
 * Tabella per le esclusioni di sincronizzazione
 */
object MergeRequestExclusionTable : IntIdTable("strapi_sync_exclusion") {
    val sourceStrapiId = reference("source_strapi_id", StrapiInstancesTable)
    val targetStrapiId = reference("target_strapi_id", StrapiInstancesTable)
    val contentType = varchar("content_type", 255)
    val documentId = varchar("document_id", 255).nullable()
    val fieldPath = varchar("field_path", 255).nullable()
    val createdAt = timestampWithTimeZone("created_at").default(OffsetDateTime.now())

    suspend fun fetchExclusions(mergeRequest: MergeRequestWithInstancesDTO): List<MergeRequestExclusion> = dbQuery {
        MergeRequestExclusionTable.selectAll().where {
            sourceStrapiId eq mergeRequest.sourceInstance.id and (targetStrapiId eq mergeRequest.targetInstance.id)
        }.map {
            MergeRequestExclusion(
                id = it[MergeRequestExclusionTable.id].value,
                sourceStrapiId = it[sourceStrapiId].value,
                targetStrapiId = it[targetStrapiId].value,
                contentType = it[contentType],
                documentId = it[documentId],
                fieldPath = it[fieldPath],
                createdAt = it[createdAt]
            )
        }
    }
}
