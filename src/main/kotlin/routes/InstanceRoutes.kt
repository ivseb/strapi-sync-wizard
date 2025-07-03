package it.sebi.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import it.sebi.models.StrapiInstanceDTO
import it.sebi.repository.StrapiInstanceRepository
import kotlinx.serialization.Serializable

@Serializable
data class ConnectionTestResponse(val connected: Boolean, val message: String)

@Serializable
data class AdminPasswordRequest(val password: String)

@Serializable
data class AdminPasswordResponse(val success: Boolean, val message: String)

fun Route.configureInstanceRoutes(repository: StrapiInstanceRepository) {
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

            val deleted = repository.deleteInstance(id)

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
