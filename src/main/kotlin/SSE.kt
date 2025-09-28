package it.sebi

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

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
        sse("/api/sync-progress/{id}") {
            heartbeat {
                period = 3.seconds
                event = ServerSentEvent("heartbeat")
            }
            val mergeRequestId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid merge request ID")

            val channel = Channel<SyncProgressUpdate>()
            SyncProgressService.registerConnection(mergeRequestId, channel)

            channel.consumeAsFlow().collect { update ->
                println("SEND MESSAGE: $update")
                try {
                    send(ServerSentEvent(JsonParser.encodeToString(SyncProgressUpdate.serializer(), update)))
                    println("SENT MESSAGE: $update")
                } catch (e:Throwable) {
                    log.error("Error sending SSE message for merge request $mergeRequestId: ${e.message}", e)
                    throw e
                }
            }


        }
        get("/api/sync-progress_old/{id}") {
            println("START")
            val mergeRequestId = call.parameters["id"]?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid merge request ID")

            // Set headers for SSE
            call.response.cacheControl(CacheControl.NoCache(null))
            call.response.header("Connection", "keep-alive")
            call.response.header("Content-Type", "text/event-stream")
            call.response.header("X-Accel-Buffering", "no") // Disable buffering for Nginx

            try {
                // Create a channel for this connection
                val channel = Channel<SyncProgressUpdate>()
                
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
                            log.info("Sending SSE message for merge request $mergeRequestId: $message")
                            write("event: progress\n")
                            write("data: $message\n\n")
                            flush()
                        }
                    } catch (e: ClosedSendChannelException) {
                        application.log.info("Channel closed for merge request $mergeRequestId")
                        // Client disconnected
                        SyncProgressService.unregisterConnection(mergeRequestId, channel)
                    } catch (e: CancellationException) {
                        // Request was cancelled
                        application.log.info("Request cancelled for merge request $mergeRequestId")
                        SyncProgressService.unregisterConnection(mergeRequestId, channel)
                    } catch (e: Throwable) {
                        // Other errors
                        log.error("Error processing SSE connection for merge request $mergeRequestId", e)
                        SyncProgressService.unregisterConnection(mergeRequestId, channel)
                        throw e
                    } finally {
                        log.info("Connection closed for merge request $mergeRequestId")
                        SyncProgressService.unregisterConnection(mergeRequestId, channel)
                    }
                }
            } catch (e: Exception) {
                log.error("Error establishing SSE connection for merge request $mergeRequestId", e)
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
    private val connections = ConcurrentHashMap<Int, MutableList<Channel<SyncProgressUpdate>>>()

    /**
     * Register a channel for a merge request
     */
    fun registerConnection(mergeRequestId: Int, channel: Channel<SyncProgressUpdate>) {
        connections.getOrPut(mergeRequestId) { mutableListOf() }.add(channel)
    }

    /**
     * Unregister a channel for a merge request
     */
    fun unregisterConnection(mergeRequestId: Int, channel: Channel<SyncProgressUpdate>) {
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

        println("SEND MESSAGE: $update")
        connections[update.mergeRequestId]?.forEach { channel ->
            try {
                if (!channel.isClosedForSend) {
                    channel.send(update)
                }
            } catch (e: Exception) {
                println("Error sending message to channel: ${e.message}\n"+ e.stackTraceToString() + "\n" + update)
                // If sending fails, the channel might be closed
                if (!channel.isClosedForSend) {
                    channel.close()
                }
            }
        }
    }
}