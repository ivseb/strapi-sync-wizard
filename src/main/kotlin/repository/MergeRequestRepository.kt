package it.sebi.repository

import it.sebi.database.dbQuery
import it.sebi.models.MergeRequest
import it.sebi.models.MergeRequestDTO
import it.sebi.models.MergeRequestStatus
import it.sebi.models.MergeRequestWithInstancesDTO
import it.sebi.tables.MergeRequestsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.*
import java.time.OffsetDateTime

class MergeRequestRepository(private val instanceRepository: StrapiInstanceRepository) {

    /**
     * Find all merge requests where the instance is either the source or target
     */
    suspend fun findMergeRequestsForInstance(instanceId: Int): List<MergeRequest> = dbQuery {
        MergeRequestsTable.selectAll()
            .where { (MergeRequestsTable.sourceInstanceId eq instanceId) or (MergeRequestsTable.targetInstanceId eq instanceId) }
            .map { it.toMergeRequest() }
    }

    /**
     * Delete all merge requests where the instance is either the source or target
     */
    suspend fun deleteMergeRequestsForInstance(instanceId: Int): Int = dbQuery {
        MergeRequestsTable.deleteWhere { 
            (MergeRequestsTable.sourceInstanceId eq instanceId) or (MergeRequestsTable.targetInstanceId eq instanceId) 
        }
    }

    suspend fun getAllMergeRequests(): List<MergeRequest> = dbQuery {
        MergeRequestsTable.selectAll()
            .map { it.toMergeRequest() }
    }

    suspend fun getMergeRequestsWithInstances(
        completed: Boolean? = null,
        sortBy: String = "updatedAt",
        sortOrder: String = "DESC",
        page: Int = 1,
        pageSize: Int = 10
    ): List<MergeRequestWithInstancesDTO> = dbQuery {
        // Build the query with optional filtering by completion status
        val query = MergeRequestsTable.selectAll().let { query ->
            when (completed) {
                true -> query.andWhere { MergeRequestsTable.status eq MergeRequestStatus.COMPLETED }
                false -> query.andWhere { MergeRequestsTable.status neq MergeRequestStatus.COMPLETED }
                null -> query
            }
        }

        // Apply sorting
        val sortedQuery = when (sortBy.lowercase()) {
            "createdat" -> when (sortOrder.uppercase()) {
                "ASC" -> query.orderBy(MergeRequestsTable.createdAt to SortOrder.ASC)
                else -> query.orderBy(MergeRequestsTable.createdAt to SortOrder.DESC)
            }
            "name" -> when (sortOrder.uppercase()) {
                "ASC" -> query.orderBy(MergeRequestsTable.name to SortOrder.ASC)
                else -> query.orderBy(MergeRequestsTable.name to SortOrder.DESC)
            }
            "status" -> when (sortOrder.uppercase()) {
                "ASC" -> query.orderBy(MergeRequestsTable.status to SortOrder.ASC)
                else -> query.orderBy(MergeRequestsTable.status to SortOrder.DESC)
            }
            else -> when (sortOrder.uppercase()) {
                "ASC" -> query.orderBy(MergeRequestsTable.updatedAt to SortOrder.ASC)
                else -> query.orderBy(MergeRequestsTable.updatedAt to SortOrder.DESC)
            }
        }

        // Execute the query and map to domain objects
        val mergeRequests = sortedQuery.map { it.toMergeRequest() }

        // Get all instances in a single query to avoid N+1 problem
        val sourceInstanceIds = mergeRequests.map { it.sourceInstanceId }
        val targetInstanceIds = mergeRequests.map { it.targetInstanceId }
        val allInstanceIds = (sourceInstanceIds + targetInstanceIds).distinct()
        val instances = allInstanceIds.mapNotNull { instanceRepository.getInstance(it) }
        val instancesById = instances.associateBy { it.id }

        // Apply pagination manually
        val startIndex = (page - 1) * pageSize
        val endIndex = minOf(startIndex + pageSize, mergeRequests.size)
        val paginatedMergeRequests = if (startIndex < mergeRequests.size) {
            mergeRequests.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        paginatedMergeRequests.mapNotNull { mergeRequest ->
            val sourceInstance = instancesById[mergeRequest.sourceInstanceId]
            val targetInstance = instancesById[mergeRequest.targetInstanceId]

            if (sourceInstance != null && targetInstance != null) {
                MergeRequestWithInstancesDTO(
                    id = mergeRequest.id,
                    name = mergeRequest.name,
                    description = mergeRequest.description,
                    sourceInstance = sourceInstance,
                    targetInstance = targetInstance,
                    status = mergeRequest.status,
                    createdAt = mergeRequest.createdAt,
                    updatedAt = mergeRequest.updatedAt
                )
            } else null
        }
    }

    suspend fun getMergeRequest(id: Int): MergeRequest? = dbQuery {
        MergeRequestsTable.selectAll().where { MergeRequestsTable.id eq id }
            .map { it.toMergeRequest() }
            .singleOrNull()
    }




    suspend fun getMergeRequestWithInstances(id: Int): MergeRequestWithInstancesDTO? = dbQuery {
        val mergeRequest = MergeRequestsTable.selectAll().where { MergeRequestsTable.id eq id }
            .map { it.toMergeRequest() }
            .singleOrNull() ?: return@dbQuery null

        val sourceInstance = instanceRepository.getInstance(mergeRequest.sourceInstanceId) ?: return@dbQuery null
        val targetInstance = instanceRepository.getInstance(mergeRequest.targetInstanceId) ?: return@dbQuery null

        MergeRequestWithInstancesDTO(
            id = mergeRequest.id,
            name = mergeRequest.name,
            description = mergeRequest.description,
            sourceInstance = sourceInstance,
            targetInstance = targetInstance,
            status = mergeRequest.status,
            createdAt = mergeRequest.createdAt,
            updatedAt = mergeRequest.updatedAt
        )
    }

    suspend fun createMergeRequest(mergeRequestDTO: MergeRequestDTO): MergeRequest = dbQuery {
        val insertStatement = MergeRequestsTable.insert {
            it[name] = mergeRequestDTO.name
            it[description] = mergeRequestDTO.description
            it[sourceInstanceId] = mergeRequestDTO.sourceInstanceId
            it[targetInstanceId] = mergeRequestDTO.targetInstanceId
            it[status] = mergeRequestDTO.status ?: MergeRequestStatus.CREATED
        }

        insertStatement.resultedValues?.singleOrNull()?.toMergeRequest()
            ?: throw IllegalStateException("Insert failed")
    }

    suspend fun updateMergeRequest(id: Int, mergeRequestDTO: MergeRequestDTO): Boolean = dbQuery {
        MergeRequestsTable.update({ MergeRequestsTable.id eq id }) {
            mergeRequestDTO.name.let { name -> it[MergeRequestsTable.name] = name }
            mergeRequestDTO.description.let { desc -> it[description] = desc }
            mergeRequestDTO.sourceInstanceId.let { srcId -> it[sourceInstanceId] = srcId }
            mergeRequestDTO.targetInstanceId.let { tgtId -> it[targetInstanceId] = tgtId }
            mergeRequestDTO.status?.let { status -> it[MergeRequestsTable.status] = status }
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    suspend fun updateMergeRequestStatus(id: Int, status: MergeRequestStatus): Boolean = dbQuery {
        MergeRequestsTable.update({ MergeRequestsTable.id eq id }) {
            it[MergeRequestsTable.status] = status
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    suspend fun updateSchemaCompatibility(
        id: Int,
    ): Boolean = dbQuery {
        MergeRequestsTable.update({ MergeRequestsTable.id eq id }) {
            it[status] = MergeRequestStatus.SCHEMA_CHECKED
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    suspend fun updateComparisonData(id: Int): Boolean = dbQuery {
        MergeRequestsTable.update({ MergeRequestsTable.id eq id }) {
            it[status] = MergeRequestStatus.COMPARED
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    suspend fun deleteMergeRequest(id: Int): Boolean = dbQuery {
        MergeRequestsTable.deleteWhere { MergeRequestsTable.id eq id } > 0
    }

    private fun ResultRow.toMergeRequest() = MergeRequest(
        id = this[MergeRequestsTable.id].value,
        name = this[MergeRequestsTable.name],
        description = this[MergeRequestsTable.description],
        sourceInstanceId = this[MergeRequestsTable.sourceInstanceId],
        targetInstanceId = this[MergeRequestsTable.targetInstanceId],
        status = this[MergeRequestsTable.status],
        createdAt = this[MergeRequestsTable.createdAt],
        updatedAt = this[MergeRequestsTable.updatedAt]
    )
}
