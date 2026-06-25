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

@Serializable
data class IdentityBackfillResponse(val instanceId: Int, val inserted: Int)

fun Route.configureInstanceRoutes(
    repository: StrapiInstanceRepository,
    mergeRequestRepository: MergeRequestRepository? = null,
    mergeRequestSelectionsRepository: MergeRequestSelectionsRepository? = null,
    postgresSnapshotService: it.sebi.service.PostgresSnapshotService? = null,
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

        // Identity layer (Phase 1): backfill the sync_identity sidecar for this instance.
        // Assigns a fresh INSTANCE-LOCAL sync_id to every existing entry lacking one.
        // Cross-instance linking is done separately by reconciliation.
        post("/{id}/identity/backfill") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, "Invalid ID format"); return@post }
            val instance = repository.getInstance(id)
            if (instance == null) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@post }
            if (instance.isVirtual) { call.respond(HttpStatusCode.BadRequest, "Cannot backfill identity on a virtual instance"); return@post }
            try {
                val dbSchema = it.sebi.service.buildDbSchemaForInstance(instance)
                if (dbSchema == null) { call.respond(HttpStatusCode.BadRequest, "Schema not available for this instance"); return@post }
                it.sebi.service.identity.SyncIdentityService.ensureTable(instance)
                val inserted = it.sebi.service.identity.SyncIdentityService.backfill(instance, dbSchema)
                call.respond(HttpStatusCode.OK, IdentityBackfillResponse(instanceId = id, inserted = inserted))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error during identity backfill")
            }
        }

        // Scan the instance media library for duplicate files (+ reference counts)
        get("/{id}/media/duplicates") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, "Invalid ID format"); return@get }
            val instance = repository.getInstance(id)
            if (instance == null) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@get }
            if (instance.isVirtual) { call.respond(HttpStatusCode.BadRequest, "Cannot scan media on a virtual instance"); return@get }
            try {
                call.respond(HttpStatusCode.OK, it.sebi.service.media.MediaDeduplicationService.scanDuplicates(instance))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error scanning media duplicates")
            }
        }

        // Stream a single media file's raw bytes (server-side proxy, so the UI can preview files
        // hosted on a CDN the browser can't reach directly — SVG, PDF, images, …).
        get("/{id}/media/file/raw") {
            val id = call.parameters["id"]?.toIntOrNull()
            val fileId = call.request.queryParameters["fileId"]?.toIntOrNull()
            if (id == null || fileId == null) { call.respond(HttpStatusCode.BadRequest, "Invalid id/fileId"); return@get }
            val instance = repository.getInstance(id)
            if (instance == null || instance.isVirtual) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@get }
            try {
                val res = it.sebi.service.media.MediaDeduplicationService.downloadFileBytes(instance, fileId)
                if (res == null) { call.respond(HttpStatusCode.NotFound, "File not found"); return@get }
                val (bytes, mime) = res
                call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=3600")
                call.respondBytes(bytes, try { ContentType.parse(mime) } catch (e: Exception) { ContentType.Application.OctetStream })
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error fetching file")
            }
        }

        // Where a single media file is used (resolved references)
        get("/{id}/media/file/references") {
            val id = call.parameters["id"]?.toIntOrNull()
            val fileId = call.request.queryParameters["fileId"]?.toIntOrNull()
            if (id == null || fileId == null) { call.respond(HttpStatusCode.BadRequest, "Invalid id/fileId"); return@get }
            val instance = repository.getInstance(id)
            if (instance == null || instance.isVirtual) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@get }
            try {
                call.respond(HttpStatusCode.OK, it.sebi.service.media.MediaDeduplicationService.getFileReferences(instance, fileId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error resolving references")
            }
        }

        // Apply media deduplication (repoint references to a canonical file, delete redundant copies)
        post("/{id}/media/dedup") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, "Invalid ID format"); return@post }
            val instance = repository.getInstance(id)
            if (instance == null) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@post }
            if (instance.isVirtual) { call.respond(HttpStatusCode.BadRequest, "Cannot deduplicate media on a virtual instance"); return@post }
            val apply = call.request.queryParameters["apply"]?.toBoolean() ?: false
            val deleteBinaries = call.request.queryParameters["deleteBinaries"]?.toBoolean() ?: false
            try {
                val body = call.receive<it.sebi.service.media.DedupRequest>()
                call.respond(HttpStatusCode.OK, it.sebi.service.media.MediaDeduplicationService.applyDedup(instance, body, apply, deleteBinaries))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid deduplication request")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error applying media deduplication")
            }
        }

        // ---- Content (collection-type) deduplication ----

        // Summary of all collection tables that have duplicate entries
        get("/{id}/content/duplicates") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, "Invalid ID format"); return@get }
            val instance = repository.getInstance(id)
            if (instance == null) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@get }
            if (instance.isVirtual) { call.respond(HttpStatusCode.BadRequest, "Cannot scan content on a virtual instance"); return@get }
            try {
                call.respond(HttpStatusCode.OK, it.sebi.service.content.ContentDeduplicationService.scanAllContentDuplicates(instance))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error scanning content duplicates")
            }
        }

        // Detailed duplicate groups for one collection table
        get("/{id}/content/duplicates/{table}") {
            val id = call.parameters["id"]?.toIntOrNull()
            val table = call.parameters["table"]
            if (id == null || table.isNullOrBlank()) { call.respond(HttpStatusCode.BadRequest, "Invalid id/table"); return@get }
            val instance = repository.getInstance(id)
            if (instance == null) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@get }
            if (instance.isVirtual) { call.respond(HttpStatusCode.BadRequest, "Cannot scan content on a virtual instance"); return@get }
            try {
                call.respond(HttpStatusCode.OK, it.sebi.service.content.ContentDeduplicationService.scanContentDuplicates(instance, table))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid table")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error scanning content table")
            }
        }

        // Where a single content entry is used (resolved references across *_lnk tables)
        get("/{id}/content/references") {
            val id = call.parameters["id"]?.toIntOrNull()
            val table = call.request.queryParameters["table"]
            val entryId = call.request.queryParameters["entryId"]?.toIntOrNull()
            if (id == null || table.isNullOrBlank() || entryId == null) { call.respond(HttpStatusCode.BadRequest, "Invalid id/table/entryId"); return@get }
            val instance = repository.getInstance(id)
            if (instance == null || instance.isVirtual) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@get }
            try {
                call.respond(HttpStatusCode.OK, it.sebi.service.content.ContentDeduplicationService.getContentReferences(instance, table, entryId))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error resolving content references")
            }
        }

        // Apply content deduplication (repoint references to a canonical entry, delete redundant entries)
        post("/{id}/content/dedup") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) { call.respond(HttpStatusCode.BadRequest, "Invalid ID format"); return@post }
            val instance = repository.getInstance(id)
            if (instance == null) { call.respond(HttpStatusCode.NotFound, "Instance not found"); return@post }
            if (instance.isVirtual) { call.respond(HttpStatusCode.BadRequest, "Cannot deduplicate content on a virtual instance"); return@post }
            val apply = call.request.queryParameters["apply"]?.toBoolean() ?: false
            try {
                val body = call.receive<it.sebi.service.content.ContentDedupRequest>()
                call.respond(HttpStatusCode.OK, it.sebi.service.content.ContentDeduplicationService.applyContentDedup(instance, body, apply))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid deduplication request")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error applying content deduplication")
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

        // Per-instance snapshot management: list / restore / delete snapshots whose merge request
        // targets this instance.
        get("/{id}/snapshots") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid ID format")
            if (postgresSnapshotService == null) { call.respond(HttpStatusCode.OK, emptyList<it.sebi.models.InstanceSnapshotDTO>()); return@get }
            try {
                call.respond(HttpStatusCode.OK, postgresSnapshotService.getSnapshotsForInstance(id))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Error listing snapshots")
            }
        }

        post("/{id}/snapshots/{snapshotId}/restore") {
            val snapshotId = call.parameters["snapshotId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid snapshot ID")
            if (postgresSnapshotService == null) { call.respond(HttpStatusCode.ServiceUnavailable, "Snapshots unavailable"); return@post }
            try {
                postgresSnapshotService.restoreSnapshotById(snapshotId)
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("success" to false, "message" to (e.message ?: "Error restoring snapshot")))
            }
        }

        delete("/{id}/snapshots/{snapshotId}") {
            val snapshotId = call.parameters["snapshotId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid snapshot ID")
            if (postgresSnapshotService == null) { call.respond(HttpStatusCode.ServiceUnavailable, "Snapshots unavailable"); return@delete }
            try {
                val ok = postgresSnapshotService.deleteSnapshotById(snapshotId)
                if (ok) call.respond(HttpStatusCode.OK, mapOf("success" to true))
                else call.respond(HttpStatusCode.NotFound, mapOf("success" to false, "message" to "Snapshot not found"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("success" to false, "message" to (e.message ?: "Error deleting snapshot")))
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
