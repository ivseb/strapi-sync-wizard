package it.sebi.tables

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

/**
 * Database table for merge request document mappings
 */
object MergeRequestDocumentMappingTable : IntIdTable("strapi_document_mapping") {
    val sourceStrapiId = reference("source_strapi_id", StrapiInstancesTable)
    val targetStrapiId = reference("target_strapi_id", StrapiInstancesTable)
    val contentType =varchar("content_type", 255)
    val sourceId = integer("source_id").nullable()
    val sourceDocumentId = varchar("source_document_id", 255).nullable()
    val sourceLastUpdateDate = timestampWithTimeZone("source_last_update_date").nullable()
    val sourceDocumentMD5 = varchar("source_document_md5", 255).nullable()
    val targetId = integer("target_id").nullable()
    val targetDocumentId = varchar("target_document_id", 255).nullable()
    val targetLastUpdateDate = timestampWithTimeZone("target_last_update_date").nullable()
    val targetDocumentMD5 = varchar("target_document_md5", 255).nullable()
    val createdAt = timestampWithTimeZone("created_at").default(OffsetDateTime.now())
    val updatedAt = timestampWithTimeZone("updated_at").default(OffsetDateTime.now())
}
