package it.sebi.tables

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime

/**
 * Tabella per memorizzare i risultati dell'analisi dei file (fingerprint e dimensione).
 * La cache è specifica per istanza e file (identificato da document_id).
 */
object FileAnalysisCacheTable : IntIdTable("file_analysis_cache") {
    val instanceId = integer("instance_id").references(StrapiInstancesTable.id)
    val documentId = varchar("document_id", 255)
    val updatedAtStr = varchar("updated_at_str", 255).nullable() // Usato per invalidare se il file cambia su Strapi
    val calculatedHash = varchar("calculated_hash", 255)
    val calculatedSizeBytes = long("calculated_size_bytes")
    val createdAt = timestampWithTimeZone("created_at").default(OffsetDateTime.now())
    val lastUsedAt = timestampWithTimeZone("last_used_at").default(OffsetDateTime.now())

    init {
        uniqueIndex("idx_instance_file", instanceId, documentId)
    }
}
