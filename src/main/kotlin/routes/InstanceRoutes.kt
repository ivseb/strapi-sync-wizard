package it.sebi.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import it.sebi.models.StrapiInstanceDTO
import it.sebi.repository.MergeRequestRepository
import it.sebi.repository.MergeRequestSelectionsRepository
import it.sebi.repository.StrapiInstanceRepository
import kotlinx.serialization.Serializable
import it.sebi.models.*
import it.sebi.service.exportSourcePrefetch

@Serializable
data class ConnectionTestResponse(val connected: Boolean, val message: String)

@Serializable
data class AdminPasswordRequest(val password: String)

@Serializable
data class AdminPasswordResponse(val success: Boolean, val message: String)

@Serializable
data class InstanceSchemaExport(val schema: DbSchema)

fun Route.configureInstanceRoutes(
    repository: StrapiInstanceRepository,
    mergeRequestRepository: MergeRequestRepository? = null,
    mergeRequestSelectionsRepository: MergeRequestSelectionsRepository? = null,
) {
    route("/api/instances") {
        // Get all instances (secure, without sensitive data)
        get {
            val instances = repository.getAllInstancesSecure()
            call.respond(instances)
        }

        // Get instance by ID (secure, without sensitive data)
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid ID format")
                return@get
            }

            val instance = repository.getInstanceSecure(id)
            if (instance == null) {
                call.respond(HttpStatusCode.NotFound, "Instance not found")
                return@get
            }

            call.respond(instance)
        }

        // Create new instance
        post {
            val instanceDto = call.receive<StrapiInstanceDTO>()
            val instance = repository.addInstance(instanceDto)
            call.respond(HttpStatusCode.Created, instance)
        }

        // Update instance
        put("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid ID format")
                return@put
            }

            val instanceDto = call.receive<StrapiInstanceDTO>()
            val updated = repository.updateInstance(id, instanceDto)

            if (updated) {
                call.respond(HttpStatusCode.OK, "Instance updated")
            } else {
                call.respond(HttpStatusCode.NotFound, "Instance not found")
            }
        }

        // Delete instance
        delete("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid ID format")
                return@delete
            }

            val deleted = if (mergeRequestRepository != null && 
                             mergeRequestSelectionsRepository != null
                             ) {
                // Use cascade delete if all repositories are provided
                try {
                    repository.deleteInstanceCascade(
                        id,
                        mergeRequestRepository,
                        mergeRequestSelectionsRepository
                    )
                } catch (e: Exception) {
                    application.log.error("Error during cascade delete", e)
                    call.respond(HttpStatusCode.InternalServerError, "Error deleting instance: ${e.message}")
                    return@delete
                }
            } else {
                // Fall back to simple delete if any repository is missing
                repository.deleteInstance(id)
            }

            if (deleted) {
                call.respond(HttpStatusCode.OK, "Instance deleted")
            } else {
                call.respond(HttpStatusCode.NotFound, "Instance not found")
            }
        }

        // Test connection to Strapi instance
        post("/test-connection") {
            @Serializable
            data class ConnectionTestRequest(
                val url: String,
                val apiKey: String,
                val username: String,
                val password: String
            )

            val request = call.receive<ConnectionTestRequest>()
            val isConnected = repository.testConnection(request.url, request.apiKey, request.username, request.password)

            if (isConnected) {
                call.respond(
                    HttpStatusCode.OK,
                    ConnectionTestResponse(connected = true, message = "Connection successful")
                )
            } else {
                call.respond(
                    HttpStatusCode.OK,
                    ConnectionTestResponse(connected = false, message = "Could not connect to Strapi instance")
                )
            }
        }

        // Export schema (DbSchema) for async mode
        get("/{id}/export/schema") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid ID format"); return@get
            }
            val instance = repository.getInstance(id)
            if (instance == null) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@get }
            try {
                val dbSchema = it.sebi.service.buildDbSchemaForInstance(instance)
                if (dbSchema == null) { call.respond(HttpStatusCode.BadRequest, "Schema not available for this instance"); return@get }
                call.respond(HttpStatusCode.OK, InstanceSchemaExport(schema = dbSchema))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error exporting schema")
            }
        }

        // Export source-only prefetch for async mode
        get("/{id}/export/prefetch") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, "Invalid ID format"); return@get }
            val instance = repository.getInstance(id)
            if (instance == null) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@get }
            try {
                val dbSchema = it.sebi.service.buildDbSchemaForInstance(instance)
                if (dbSchema == null) { call.respond(HttpStatusCode.BadRequest, "Schema not available for this instance"); return@get }
                val cache = exportSourcePrefetch(instance, dbSchema)
                call.respond(HttpStatusCode.OK, cache)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error exporting prefetch")
            }
        }

        // Get instance by ID with sensitive data (password and apiKey) after admin password verification
        post("/{id}/full") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid ID format")
                return@post
            }

            val request = call.receive<AdminPasswordRequest>()
            val configuredPassword = call.application.environment.config.property("application.adminPassword").getString()

            if (request.password != configuredPassword) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AdminPasswordResponse(success = false, message = "Invalid admin password")
                )
                return@post
            }

            val instance = repository.getInstance(id)
            if (instance == null) {
                call.respond(HttpStatusCode.NotFound, "Instance not found")
                return@post
            }

            call.respond(instance)
        }
    }
}
