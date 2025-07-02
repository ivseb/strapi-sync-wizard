package it.sebi

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Data class representing a sync progress update
 */
@Serializable
data class SyncProgressUpdate(
    val mergeRequestId: Int,
    val totalItems: Int,
    val processedItems: Int,
    val currentItem: String,
    val currentItemType: String,
    val currentOperation: String,
    val status: String,
    val message: String? = null
)

/**
 * Configure Server-Sent Events for the application
 */
fun Application.configureSSE() {
    routing {
        get("/api/sync-progress/{id}") {
            val mergeRequestId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid merge request ID")

            // Set headers for SSE
            call.response.cacheControl(CacheControl.NoCache(null))
            call.response.header("Connection", "keep-alive")
            call.response.header("Content-Type", "text/event-stream")
            call.response.header("X-Accel-Buffering", "no") // Disable buffering for Nginx

            try {
                // Create a channel for this connection
                val channel = Channel<String>()
                
                // Register this connection to receive updates for the specified merge request
                SyncProgressService.registerConnection(mergeRequestId, channel)
                
                // Start responding with SSE
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    try {
                        // Send initial connection established event
                        write("event: connected\n")
                        write("data: {\"connected\": true}\n\n")
                        flush()
                        
                        // Process messages from the channel
                        while (!channel.isClosedForReceive) {
                            val message = channel.receive()
                            write("event: progress\n")
                            write("data: $message\n\n")
                            flush()
                        }
                    } catch (e: ClosedSendChannelException) {
                        // Client disconnected
                        SyncProgressService.unregisterConnection(mergeRequestId, channel)
                    } catch (e: CancellationException) {
                        // Request was cancelled
                        SyncProgressService.unregisterConnection(mergeRequestId, channel)
                    } catch (e: Throwable) {
                        // Other errors
                        SyncProgressService.unregisterConnection(mergeRequestId, channel)
                        throw e
                    } finally {
                        SyncProgressService.unregisterConnection(mergeRequestId, channel)
                    }
                }
            } catch (e: Exception) {
                // Handle exceptions
                call.respond(HttpStatusCode.InternalServerError, "Error establishing SSE connection: ${e.message}")
            }
        }
    }
}

/**
 * Singleton service for tracking and reporting sync progress
 */
object SyncProgressService {
    // Map of merge request ID to list of channels
    private val connections = ConcurrentHashMap<Int, MutableList<Channel<String>>>()

    /**
     * Register a channel for a merge request
     */
    fun registerConnection(mergeRequestId: Int, channel: Channel<String>) {
        connections.getOrPut(mergeRequestId) { mutableListOf() }.add(channel)
    }

    /**
     * Unregister a channel for a merge request
     */
    fun unregisterConnection(mergeRequestId: Int, channel: Channel<String>) {
        connections[mergeRequestId]?.remove(channel)
        if (connections[mergeRequestId]?.isEmpty() == true) {
            connections.remove(mergeRequestId)
        }
        // Close the channel if it's not already closed
        if (!channel.isClosedForSend) {
            channel.close()
        }
    }

    /**
     * Send a progress update to all connected clients for a merge request
     */
    suspend fun sendProgressUpdate(update: SyncProgressUpdate) {
        val json = Json.encodeToString(update)
        connections[update.mergeRequestId]?.forEach { channel ->
            try {
                if (!channel.isClosedForSend) {
                    channel.send(json)
                }
            } catch (e: Exception) {
                // If sending fails, the channel might be closed
                if (!channel.isClosedForSend) {
                    channel.close()
                }
            }
        }
    }
}