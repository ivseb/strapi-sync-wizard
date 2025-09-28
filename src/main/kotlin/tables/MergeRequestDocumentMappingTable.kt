package it.sebi.tables

import it.sebi.database.dbQuery
import it.sebi.models.MergeRequestDocumentMapping
import it.sebi.models.MergeRequestWithInstancesDTO
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.OffsetDateTime

/**
 * Database table for merge request document mappings
 */
object MergeRequestDocumentMappingTable : IntIdTable("strapi_document_mapping") {
    val sourceStrapiId = reference("source_strapi_id", StrapiInstancesTable)
    val targetStrapiId = reference("target_strapi_id", StrapiInstancesTable)
    val contentType = varchar("content_type", 255)
    val sourceId = integer("source_id").nullable()
    val sourceDocumentId = varchar("source_document_id", 255).nullable()
    val sourceLastUpdateDate = timestampWithTimeZone("source_last_update_date").nullable()
    val sourceDocumentMD5 = varchar("source_document_md5", 255).nullable()
    val targetId = integer("target_id").nullable()
    val targetDocumentId = varchar("target_document_id", 255).nullable()
    val targetLastUpdateDate = timestampWithTimeZone("target_last_update_date").nullable()
    val targetDocumentMD5 = varchar("target_document_md5", 255).nullable()
    val locale = varchar("locale", 16).nullable()
    val createdAt = timestampWithTimeZone("created_at").default(OffsetDateTime.now())
    val updatedAt = timestampWithTimeZone("updated_at").default(OffsetDateTime.now())


    suspend fun fetchMappingList(mergeRequest: MergeRequestWithInstancesDTO): List<MergeRequestDocumentMapping> = dbQuery {
        MergeRequestDocumentMappingTable.selectAll().where {
            sourceStrapiId inList listOf(
                mergeRequest.sourceInstance.id,
                mergeRequest.targetInstance.id
            ) and (targetStrapiId inList listOf(
                mergeRequest.sourceInstance.id,
                mergeRequest.targetInstance.id
            ))
        }.toList().map { mapping ->
            val map = MergeRequestDocumentMapping(
                mapping[MergeRequestDocumentMappingTable.id].value,
                mapping[sourceStrapiId].value,
                mapping[targetStrapiId].value,
                mapping[contentType],
                mapping[sourceId],
                mapping[sourceDocumentId],
                mapping[sourceLastUpdateDate],
                mapping[sourceDocumentMD5],
                mapping[targetId],
                mapping[targetDocumentId],
                mapping[targetLastUpdateDate],
            )

            if (map.sourceStrapiInstanceId != mergeRequest.sourceInstance.id)
                map.copy(
                    sourceDocumentId = map.targetDocumentId,
                    targetDocumentId = map.sourceDocumentId,
                    sourceId = map.targetId,
                    targetId = map.sourceId
                )
            else map
        }.filter { it.sourceDocumentId != null }
    }

    suspend fun fetchMappingMap(mergeRequest: MergeRequestWithInstancesDTO): Map<String, Map<String, MergeRequestDocumentMapping>> =
        fetchMappingList(mergeRequest).groupBy { it.contentType }
            .mapValues { (_, v) -> v.associateBy { it.sourceDocumentId!! } }
}
