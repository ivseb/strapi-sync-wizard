package it.sebi.repository

import it.sebi.client.StrapiClient
import it.sebi.database.dbQuery
import it.sebi.models.StrapiInstance
import it.sebi.models.StrapiInstanceDTO
import it.sebi.models.StrapiInstanceSecure
import it.sebi.tables.StrapiInstancesTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime

class StrapiInstanceRepository {

    /**
     * Get all instances with sensitive data (password and apiKey)
     * This should only be used internally, not exposed to frontend
     */
    suspend fun getAllInstances(): List<StrapiInstance> = dbQuery {
        StrapiInstancesTable.selectAll()
            .map { it.toStrapiInstance() }
    }

    /**
     * Get all instances without sensitive data (password and apiKey)
     * This is safe to expose to frontend
     */
    suspend fun getAllInstancesSecure(): List<StrapiInstanceSecure> = dbQuery {
        StrapiInstancesTable.selectAll()
            .map { it.toStrapiInstanceSecure() }
    }

    /**
     * Get instance by ID with sensitive data (password and apiKey)
     * This should only be used internally or for edit form
     */
    suspend fun getInstance(id: Int): StrapiInstance? = dbQuery {
        StrapiInstancesTable.selectAll().where { StrapiInstancesTable.id eq id }
            .map { it.toStrapiInstance() }
            .singleOrNull()
    }

    /**
     * Get instance by ID without sensitive data (password and apiKey)
     * This is safe to expose to frontend
     */
    suspend fun getInstanceSecure(id: Int): StrapiInstanceSecure? = dbQuery {
        StrapiInstancesTable.selectAll().where { StrapiInstancesTable.id eq id }
            .map { it.toStrapiInstanceSecure() }
            .singleOrNull()
    }

    suspend fun addInstance(instance: StrapiInstanceDTO): StrapiInstance = dbQuery {
        val insertStatement = StrapiInstancesTable.insert {
            it[name] = instance.name
            it[url] = instance.url
            it[username] = instance.username
            it[password] = instance.password
            it[apiKey] = instance.apiKey
        }

        insertStatement.resultedValues?.singleOrNull()?.toStrapiInstance()
            ?: throw IllegalStateException("Insert failed")
    }

    suspend fun updateInstance(id: Int, instance: StrapiInstanceDTO): Boolean = dbQuery {
        StrapiInstancesTable.update({ StrapiInstancesTable.id eq id }) {
            it[name] = instance.name
            it[url] = instance.url
            it[username] = instance.username
            if (instance.password.trim().isNotEmpty())
                it[password] = instance.password
            if (instance.apiKey.trim().isNotEmpty())
                it[apiKey] = instance.apiKey
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    suspend fun deleteInstance(id: Int): Boolean = dbQuery {
        StrapiInstancesTable.deleteWhere { StrapiInstancesTable.id eq id } > 0
    }

    suspend fun testConnection(url: String, apiKey: String, username: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val client = StrapiClient("test", url, apiKey, username, password)
                // Try to get content types to test the connection
                client.getLoginToken()
                client.getContentTypes()
                true
            } catch (e: Exception) {
                println("Error connecting to Strapi: ${e.message}")
                println(e.stackTraceToString())
                false
            }
        }

    private fun ResultRow.toStrapiInstance() = StrapiInstance(
        id = this[StrapiInstancesTable.id].value,
        name = this[StrapiInstancesTable.name],
        url = this[StrapiInstancesTable.url],
        username = this[StrapiInstancesTable.username],
        password = this[StrapiInstancesTable.password],
        apiKey = this[StrapiInstancesTable.apiKey],
        createdAt = this[StrapiInstancesTable.createdAt],
        updatedAt = this[StrapiInstancesTable.updatedAt]
    )

    /**
     * Convert a ResultRow to a StrapiInstanceSecure, excluding sensitive fields (password and apiKey)
     */
    private fun ResultRow.toStrapiInstanceSecure() = StrapiInstanceSecure(
        id = this[StrapiInstancesTable.id].value,
        name = this[StrapiInstancesTable.name],
        url = this[StrapiInstancesTable.url],
        username = this[StrapiInstancesTable.username],
        createdAt = this[StrapiInstancesTable.createdAt],
        updatedAt = this[StrapiInstancesTable.updatedAt]
    )
}
