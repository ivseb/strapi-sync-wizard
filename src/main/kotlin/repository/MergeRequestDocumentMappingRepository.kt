package it.sebi.repository

import it.sebi.database.dbQuery
import it.sebi.models.MergeRequestDocumentMapping
import it.sebi.tables.MergeRequestDocumentMappingTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime

class MergeRequestDocumentMappingRepository {

    suspend fun getFilesMappingsForInstances(
        sourceStrapiInstanceId: Int, targetStrapiInstanceId: Int,
        sourceDocumentId: List<String>,
        targetDocumentId: List<String>,
    ): List<MergeRequestDocumentMapping> = dbQuery {
        MergeRequestDocumentMappingTable.selectAll()
            .where {
                MergeRequestDocumentMappingTable.sourceStrapiId eq sourceStrapiInstanceId and
                        (MergeRequestDocumentMappingTable.targetStrapiId eq targetStrapiInstanceId and (
                                MergeRequestDocumentMappingTable.sourceDocumentId inList sourceDocumentId)or (MergeRequestDocumentMappingTable.targetDocumentId inList targetDocumentId)
                                )
            }
            .map { it.toMergeRequestDocumentMapping() }
    }

    suspend fun getFilesMappingForInstances(
        sourceStrapiInstanceId: Int, targetStrapiInstanceId: Int,
        sourceDocumentId: String,
        targetDocumentId: String,
    ): MergeRequestDocumentMapping? = dbQuery {
        MergeRequestDocumentMappingTable.selectAll()
            .where {
                MergeRequestDocumentMappingTable.sourceStrapiId eq sourceStrapiInstanceId and
                        (MergeRequestDocumentMappingTable.targetStrapiId eq targetStrapiInstanceId and (
                                MergeRequestDocumentMappingTable.sourceDocumentId eq sourceDocumentId) and (MergeRequestDocumentMappingTable.targetDocumentId eq targetDocumentId)
                                )
            }
            .singleOrNull()?.toMergeRequestDocumentMapping()
    }


    suspend fun getFilesAllMappingsForInstances(
        sourceStrapiInstanceId: Int, targetStrapiInstanceId: Int,
    ): List<MergeRequestDocumentMapping> = dbQuery {
        MergeRequestDocumentMappingTable.selectAll()
            .where {
                MergeRequestDocumentMappingTable.sourceStrapiId eq sourceStrapiInstanceId and
                        (MergeRequestDocumentMappingTable.targetStrapiId eq targetStrapiInstanceId)
            }
            .map { it.toMergeRequestDocumentMapping() }
    }

    suspend fun deleteFilesMappingsForInstancesByTarget(
        sourceStrapiInstanceId: Int, targetStrapiInstanceId: Int,
        targetDocumentId: String,
    ): Int = dbQuery {
        MergeRequestDocumentMappingTable.deleteWhere {
            MergeRequestDocumentMappingTable.sourceStrapiId eq sourceStrapiInstanceId and
                    (MergeRequestDocumentMappingTable.targetStrapiId eq targetStrapiInstanceId and (
                            MergeRequestDocumentMappingTable.targetDocumentId eq targetDocumentId))
        }

    }


    suspend fun getTargetFilesMappingsForMergeRequest(
        sourceStrapiInstanceId: Int,
        targetStrapiInstanceId: Int,
        documentIds: List<String>
    ): List<MergeRequestDocumentMapping> = dbQuery {
        MergeRequestDocumentMappingTable.selectAll()
            .where { MergeRequestDocumentMappingTable.sourceStrapiId eq sourceStrapiInstanceId and (MergeRequestDocumentMappingTable.targetStrapiId eq targetStrapiInstanceId and (MergeRequestDocumentMappingTable.targetDocumentId inList documentIds)) }
            .map { it.toMergeRequestDocumentMapping() }
    }


    suspend fun getMappingById(id: Int): MergeRequestDocumentMapping? = dbQuery {
        MergeRequestDocumentMappingTable.selectAll()
            .where { MergeRequestDocumentMappingTable.id eq id }
            .map { it.toMergeRequestDocumentMapping() }
            .singleOrNull()
    }

    suspend fun createMapping(mapping: MergeRequestDocumentMapping): MergeRequestDocumentMapping = dbQuery {
        val insertStatement = MergeRequestDocumentMappingTable.insert {
            it[sourceId] = mapping.sourceId
            it[contentType] = mapping.contentType
            it[sourceStrapiId] = mapping.sourceStrapiInstanceId
            it[targetStrapiId] = mapping.targetStrapiInstanceId
            it[sourceDocumentId] = mapping.sourceDocumentId
            it[sourceLastUpdateDate] = mapping.sourceLastUpdateDate
            it[sourceDocumentMD5] = mapping.sourceDocumentMD5
            it[targetId] = mapping.targetId
            it[targetDocumentId] = mapping.targetDocumentId
            it[targetLastUpdateDate] = mapping.targetLastUpdateDate
            it[targetDocumentMD5] = mapping.targetDocumentMD5
        }

        insertStatement.resultedValues?.singleOrNull()?.toMergeRequestDocumentMapping()
            ?: throw IllegalStateException("Insert failed")
    }

    suspend fun updateMapping(id: Int, mapping: MergeRequestDocumentMapping): Boolean = dbQuery {
        MergeRequestDocumentMappingTable.update({ MergeRequestDocumentMappingTable.id eq id }) {
            it[sourceId] = mapping.sourceId
            it[contentType] = mapping.contentType
            it[sourceDocumentId] = mapping.sourceDocumentId
            it[sourceLastUpdateDate] = mapping.sourceLastUpdateDate
            it[sourceDocumentMD5] = mapping.sourceDocumentMD5
            it[targetId] = mapping.targetId
            it[targetDocumentId] = mapping.targetDocumentId
            it[targetLastUpdateDate] = mapping.targetLastUpdateDate
            it[targetDocumentMD5] = mapping.targetDocumentMD5
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    suspend fun deleteMapping(id: Int): Boolean = dbQuery {
        MergeRequestDocumentMappingTable.deleteWhere { MergeRequestDocumentMappingTable.id eq id } > 0
    }


    private fun ResultRow.toMergeRequestDocumentMapping() = MergeRequestDocumentMapping(
        id = this[MergeRequestDocumentMappingTable.id].value,
        sourceId = this[MergeRequestDocumentMappingTable.sourceId],
        sourceStrapiInstanceId = this[MergeRequestDocumentMappingTable.sourceStrapiId].value,
        targetStrapiInstanceId = this[MergeRequestDocumentMappingTable.targetStrapiId].value,
        contentType = this[MergeRequestDocumentMappingTable.contentType],
        sourceDocumentId = this[MergeRequestDocumentMappingTable.sourceDocumentId],
        sourceLastUpdateDate = this[MergeRequestDocumentMappingTable.sourceLastUpdateDate],
        sourceDocumentMD5 = this[MergeRequestDocumentMappingTable.sourceDocumentMD5],
        targetId = this[MergeRequestDocumentMappingTable.targetId],
        targetDocumentId = this[MergeRequestDocumentMappingTable.targetDocumentId],
        targetLastUpdateDate = this[MergeRequestDocumentMappingTable.targetLastUpdateDate],
        targetDocumentMD5 = this[MergeRequestDocumentMappingTable.targetDocumentMD5],
        createdAt = this[MergeRequestDocumentMappingTable.createdAt],
        updatedAt = this[MergeRequestDocumentMappingTable.updatedAt]
    )
}