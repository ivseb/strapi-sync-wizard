package it.sebi.utils

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes reproducible .http files for failed HTTP requests during merge operations.
 * Files are stored under: <dataRoot>/merge_request_<id>/errors/<role>/<call_type>/<yyyy-MM-dd>/<timestamp>_<call_type>.http
 */
object ErrorHttpLogger {

    private val dateFolderFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val tsFmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

    fun writeHttpLog(
        dataRootFolder: String,
        mergeRequestId: Int,
        role: String, // "source" | "target"
        callType: String,
        method: String,
        url: String,
        authHeader: String?, // full value e.g. "Bearer <token>"
        requestHeaders: Map<String, String> = emptyMap(),
        requestBody: String? = null,
        responseStatus: String? = null, // e.g. "400 Bad Request"
        responseHeaders: Map<String, String>? = null,
        responseBody: String? = null,
        errorMessage: String? = null,
        identifier: String? = null // e.g. documentId
    ) {
        try {
            val now = LocalDateTime.now()
            val dateFolder = now.format(dateFolderFmt)
            val ts = now.format(tsFmt)
            val mrFolder = File(dataRootFolder, "merge_request_$mergeRequestId")
            
            // Structure: merge_request_<id>/logs/<role>/<identifier or call_type>/<yyyy-MM-dd>/<timestamp>_<method>.http
            val idDir = identifier?.replace(":", "_") ?: callType.lowercase()
            val outDir = File(mrFolder, "logs/${role.lowercase()}/$idDir/$dateFolder")
            if (!outDir.exists()) outDir.mkdirs()
            val outFile = File(outDir, "${ts}_${method.lowercase()}.http")

            val sb = StringBuilder()
            // Request section
            sb.appendLine("$method $url")
            val headers = linkedMapOf<String, String>()
            if (!authHeader.isNullOrBlank()) headers["Authorization"] = authHeader
            requestHeaders.forEach { (k, v) -> headers.putIfAbsent(k, v) }
            // Default content type for JSON bodies if none provided
            if (!requestBody.isNullOrBlank() && headers.keys.none { it.equals("Content-Type", true) }) {
                headers["Content-Type"] = "application/json"
            }
            headers.forEach { (k, v) -> sb.appendLine("$k: $v") }
            sb.appendLine()
            requestBody?.let {
                sb.appendLine(it)
            }

            // Response section
            sb.appendLine()
            sb.appendLine("### Response")
            responseStatus?.let { sb.appendLine(it) }
            responseHeaders?.forEach { (k, v) -> sb.appendLine("$k: $v") }
            if (!responseBody.isNullOrBlank()) {
                sb.appendLine()
                sb.appendLine(responseBody)
            }
            errorMessage?.let {
                sb.appendLine()
                sb.appendLine("### Error Message")
                sb.appendLine(it)
            }

            outFile.writeText(sb.toString())
        } catch (_: Exception) {
            // Swallow logging errors; do not break main flow
        }
    }
}
