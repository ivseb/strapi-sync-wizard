package it.sebi.routes

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import it.sebi.models.*
import it.sebi.service.MergeRequestService

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

            val force = call.queryParameters["force"]?.toBooleanStrictOrNull() ?: false
            try {
                mergeRequestService.compareContent(id, force)
                call.respond(HttpStatusCode.OK)
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
                call.respond(HttpStatusCode.NotFound, e.message ?: "Merge request not found")
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Invalid state for comparison")
            } catch (e: Exception) {
                throw e
            }
        }

        // Get all merge request data after compare
        get("/{id}/all-data") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid ID format")

            try {
                val allData = mergeRequestService.getAllMergeRequestData(id)
                call.respond(allData)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, e.message ?: "Merge request not found")
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "An error occurred")
            }
        }

        // Endpoints for files, collection-content-types, and single-content-types have been removed
        // All data is now provided by the /all-data endpoint


        // Update a single selection
        post("/{id}/selection") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid ID format")

            // Get selection data from request body using the new DTO
            val selectionData = call.receive<SingleSelectionDTO>()

            val direction = try {
                Direction.valueOf(selectionData.direction)
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid direction")
            }

            try {
                val response = mergeRequestService.updateSingleSelection(
                    id,
                    selectionData.contentType,
                    selectionData.documentId,
                    direction,
                    selectionData.isSelected
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
                    MergeResponse(success = false, message = e.message ?: "An error occurred")
                )
            }
        }

        // Update all selections for a specific content type and direction
        post("/{id}/bulk-selection") {
            val id = call.parameters["id"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid ID format")

            // Get bulk selection data from request body
            val bulkSelectionData = call.receive<BulkSelectionDTO>()

            val direction = try {
                Direction.valueOf(bulkSelectionData.direction)
            } catch (e: IllegalArgumentException) {
                return@post call.respond(HttpStatusCode.BadRequest, "Invalid direction")
            }

            try {
                val response = mergeRequestService.updateAllSelections(
                    id,
                    bulkSelectionData.contentType,
                    direction,
                    bulkSelectionData.documentIds,
                    bulkSelectionData.isSelected
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
                    MergeResponse(success = false, message = e.message ?: "An error occurred")
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
