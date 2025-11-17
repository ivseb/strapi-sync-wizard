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
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import it.sebi.JsonParser
import it.sebi.client.StrapiClient
import java.sql.DriverManager

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

        // Export bundle (schema + prefetch) as a single ZIP file
        get("/{id}/export/bundle") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, "Invalid ID format"); return@get }
            val instance = repository.getInstance(id)
            if (instance == null) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@get }
            try {
                val dbSchema = it.sebi.service.buildDbSchemaForInstance(instance)
                if (dbSchema == null) { call.respond(HttpStatusCode.BadRequest, "Schema not available for this instance"); return@get }
                val cache = exportSourcePrefetch(instance, dbSchema)

                val baos = ByteArrayOutputStream()
                ZipOutputStream(baos).use { zos ->
                    // schema.json
                    run {
                        val json = JsonParser.encodeToString(InstanceSchemaExport.serializer(), InstanceSchemaExport(schema = dbSchema))
                        val entry = ZipEntry("schema.json")
                        zos.putNextEntry(entry)
                        zos.write(json.toByteArray())
                        zos.closeEntry()
                    }
                    // prefetch.json
                    run {
                        val json = JsonParser.encodeToString(it.sebi.service.ComparisonPrefetchCache.serializer(), cache)
                        val entry = ZipEntry("prefetch.json")
                        zos.putNextEntry(entry)
                        zos.write(json.toByteArray())
                        zos.closeEntry()
                    }
                }

                val bytes = baos.toByteArray()
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=instance_${id}_bundle.zip")
                call.respondBytes(bytes, ContentType.Application.Zip)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error exporting bundle")
            }
        }

        // Test DB connection for an instance
        post("/{id}/test-db") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, "Invalid ID format"); return@post }
            val instance = repository.getInstance(id)
            if (instance == null) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@post }

            if (instance.dbHost == null || instance.dbPort == null || instance.dbName == null || instance.dbUser == null || instance.dbPassword == null) {
                call.respond(HttpStatusCode.OK, ConnectionTestResponse(false, "DB settings are incomplete for this instance"))
                return@post
            }
            val dbUrl = "jdbc:postgresql://${instance.dbHost}:${instance.dbPort}/${instance.dbName}?${if (!instance.dbSchema.isNullOrBlank()) "currentSchema=${instance.dbSchema}" else ""}${if (!instance.dbSslMode.isNullOrBlank()) "&sslmode=${instance.dbSslMode}" else ""}"
            try {
                DriverManager.getConnection(dbUrl, instance.dbUser, instance.dbPassword).use { conn ->
                    val ok = conn.isValid(2)
                    if (ok) call.respond(HttpStatusCode.OK, ConnectionTestResponse(true, "DB connection successful"))
                    else call.respond(HttpStatusCode.OK, ConnectionTestResponse(false, "DB connection failed"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.OK, ConnectionTestResponse(false, "DB connection error: ${e.message}"))
            }
        }

        // Test Strapi login with username/password
        post("/{id}/test-login") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, "Invalid ID format"); return@post }
            val instance = repository.getInstance(id)
            if (instance == null) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@post }
            try {
                val client = StrapiClient(instance)
                client.getLoginToken()
                call.respond(HttpStatusCode.OK, ConnectionTestResponse(true, "Login successful"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.OK, ConnectionTestResponse(false, "Login failed: ${e.message}"))
            }
        }

        // Test API access via Access Token (apiKey)
        post("/{id}/test-token") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, "Invalid ID format"); return@post }
            val instance = repository.getInstance(id)
            if (instance == null) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@post }
            try {
                val client = StrapiClient(instance)
                client.getContentTypes()
                call.respond(HttpStatusCode.OK, ConnectionTestResponse(true, "API token works"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.OK, ConnectionTestResponse(false, "API token failed: ${e.message}"))
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
