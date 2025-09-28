package it.sebi.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import it.sebi.models.*
import it.sebi.service.MergeRequestService
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("it.sebi.routes.MergeRequestRoutes")

fun Route.configureMergeRequestRoutes(mergeRequestService: MergeRequestService) {
    route("/api/merge-requests") {
        // Get all merge requests with optional filtering, sorting, and pagination
        get {
            // Parse query parameters
            val completed = call.request.queryParameters["completed"]?.toBooleanStrictOrNull()
            val sortBy = call.request.queryParameters["sortBy"] ?: "updatedAt"
            val sortOrder = call.request.queryParameters["sortOrder"] ?: "DESC"
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 10

            val mergeRequests = mergeRequestService.getMergeRequestsWithInstances(
                completed = completed,
                sortBy = sortBy,
                sortOrder = sortOrder,
                page = page,
                pageSize = pageSize
            )
            call.respond(mergeRequests)
        }

        route("/{id}") {


            // Get a specific merge request
            get {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid ID format")

                val mergeRequest = mergeRequestService.getMergeRequestDetail(id)
                call.respond(mergeRequest)
            }


            // Update a merge request
            put {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid ID format")

                val mergeRequestDTO = call.receive<MergeRequestDTO>()
                val updated = mergeRequestService.updateMergeRequest(id, mergeRequestDTO)

                if (updated) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.NotFound, "Merge request not found")
                }
            }

            // Delete a merge request
            delete {
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid ID format")

                try {
                    val deleted = mergeRequestService.deleteMergeRequest(id)
                    if (deleted) {
                        call.respond(HttpStatusCode.NoContent)
                    } else {
                        call.respond(HttpStatusCode.NotFound, "Merge request not found")
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.NotFound, e.message ?: "Merge request not found")
                } catch (e: IllegalStateException) {
                    // This is thrown when trying to delete a completed merge request
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "Cannot delete merge request")
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, e.message ?: "An error occurred")
                }
            }
        }

        post {
            val mergeRequestDTO = call.receive<MergeRequestDTO>()
            val mergeRequest = mergeRequestService.createMergeRequest(mergeRequestDTO)
            call.respond(HttpStatusCode.Created, mergeRequest)
        }
        // Check schema compatibility
        post("/{id}/check-schema") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid ID format")

            // Get force parameter from query parameters, default to false
            val force = call.request.queryParameters["force"]?.toBoolean() ?: false

            try {
                val result = mergeRequestService.checkSchemaCompatibility(id, force)
                call.respond(SchemaCompatibilityResponse(result.isCompatible))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, e.message ?: "Merge request not found")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "An error occurred")
            }
        }

        // Compare content
        post("/{id}/compare") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid ID format")

            val modeParam = call.queryParameters["mode"]?.lowercase()
            val mode = when (modeParam) {
                "full" -> it.sebi.service.CompareMode.Full
                "cache" -> it.sebi.service.CompareMode.Cache
                "compare" -> it.sebi.service.CompareMode.Compare
                null -> {
                    // Backward compatibility with old 'force' flag
                    val force = call.queryParameters["force"]?.toBooleanStrictOrNull() ?: false
                    if (force) it.sebi.service.CompareMode.Full else it.sebi.service.CompareMode.Compare
                }
                else -> it.sebi.service.CompareMode.Compare
            }
            try {
                mergeRequestService.compareContent(id, mode)
                call.respond(HttpStatusCode.OK)
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
                call.respond(HttpStatusCode.NotFound, e.message ?: "Merge request not found")
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid state for comparison")
            } catch (e: java.net.SocketException) {
                // Handle connection reset errors specifically
                logger.error("Network error during content comparison for merge request $id", e)
                call.respond(
                    HttpStatusCode.InternalServerError, 
                    "Network error during content comparison: ${e.message}. This may be due to resource constraints. Try again later."
                )
            } catch (e: Exception) {
                // Log the error with more details
                logger.error("Error during content comparison for merge request $id", e)
                call.respond(
                    HttpStatusCode.InternalServerError, 
                    "Error during content comparison: ${e.message}"
                )
            }
        }

        // Manual mappings upsert (bulk)
        post("/{id}/mappings") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid ID format")
            val req = call.receive<ManualMappingsRequestDTO>()
            try {
                val data = mergeRequestService.upsertManualMappings(id, req.items)
                call.respond(HttpStatusCode.OK, ManualMappingsResponseDTO(success = true, data = data))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ManualMappingsResponseDTO(success = false, message = e.message ?: "Invalid request"))
            } catch (e: Exception) {
                logger.error("Error during manual mappings upsert for merge request $id", e)
                call.respond(HttpStatusCode.InternalServerError, ManualMappingsResponseDTO(success = false, message = e.message ?: "Server error"))
            }
        }

        // Manual mappings list (optionally filtered by contentType UID)
        get("/{id}/mappings") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid ID format")
            val contentType = call.request.queryParameters["contentType"]
            try {
                val res = mergeRequestService.getManualMappingsList(id, contentType)
                call.respond(HttpStatusCode.OK, res)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ManualMappingsListResponseDTO(success = false, message = e.message ?: "Invalid request"))
            } catch (e: Exception) {
                logger.error("Error fetching manual mappings for merge request $id", e)
                call.respond(HttpStatusCode.InternalServerError, ManualMappingsListResponseDTO(success = false, message = e.message ?: "Server error"))
            }
        }

        // Delete a single manual mapping by id
        delete("/{id}/mappings/{mappingId}") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid ID format")
            val mappingId = call.parameters["mappingId"]?.toIntOrNull()
                ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid mapping ID format")
            try {
                val data = mergeRequestService.deleteManualMapping(id, mappingId)
                call.respond(HttpStatusCode.OK, ManualMappingsResponseDTO(success = true, data = data))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ManualMappingsResponseDTO(success = false, message = e.message ?: "Invalid request"))
            } catch (e: Exception) {
                logger.error("Error deleting manual mapping $mappingId for merge request $id", e)
                call.respond(HttpStatusCode.InternalServerError, ManualMappingsResponseDTO(success = false, message = e.message ?: "Server error"))
            }
        }





        // Unified selection endpoint: supports single, list, and all via ids/all flag
        post("/{id}/selection") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid ID format")

            val selectionData = call.receive<UnifiedSelectionDTO>()

            if(selectionData.tableName == null && selectionData.selectAllKind == null) {
                call.respond(HttpStatusCode.BadRequest, "Table name must be provided if not selecting all")
                return@post
            }
            if(selectionData.selectAllKind != null && selectionData.ids != null) {
                call.respond(HttpStatusCode.BadRequest, "Cannot provide both ids and all")
                return@post
            }

            try {
                val response = mergeRequestService.processUnifiedSelection(
                    id,
                    selectionData
                )
                call.respond(HttpStatusCode.OK, response)
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.NotFound,
                    MergeResponse(success = false, message = e.message ?: "Merge request not found")
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    MergeResponse(success = false, message = e.message ?: ("An error occurred: " + e.message))
                )
            }
        }

        // Get selections
        get("/{id}/selections") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid ID format")

            try {
                val selections = mergeRequestService.getMergeRequestSelections(id)
                call.respond(HttpStatusCode.OK, selections)
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.NotFound,
                    MergeResponse(success = false, message = e.message ?: "Merge request not found")
                )
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    MergeResponse(success = false, message = e.message ?: "An error occurred")
                )
            }
        }

        // Sync plan (order preview)
        get("/{id}/sync-plan") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid ID format")
            try {
                val plan = mergeRequestService.getSyncPlan(id)
                call.respond(HttpStatusCode.OK, plan)
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.NotFound,
                    MergeResponse(success = false, message = e.message ?: "Merge request not found")
                )
            } catch (e: Exception) {
                logger.error("Error computing sync plan for merge request $id", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    MergeResponse(success = false, message = e.message ?: "An error occurred while computing sync plan")
                )
            }
        }

        // Complete merge request
        post("/{id}/complete") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid ID format")

            try {
                val success = mergeRequestService.completeMergeRequest(id)
                if (success) {
                    call.respond(
                        HttpStatusCode.OK,
                        MergeResponse(success = true, message = "Merge request completed successfully")
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        MergeResponse(success = false, message = "Failed to complete merge request")
                    )
                }
            } catch (e: Exception) {
                // Log the exception
                e.printStackTrace()

                // Return an error response
                call.respond(
                    HttpStatusCode.InternalServerError,
                    MergeResponse(success = false, message = "Error during merge request completion: ${e.message}")
                )
            }
        }
    }
}
