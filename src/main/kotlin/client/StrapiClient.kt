package it.sebi.client

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.swagger.v3.core.util.Json
import it.sebi.JsonParser
import it.sebi.models.*
import it.sebi.utils.calculateMD5Hash
import it.sebi.utils.ErrorHttpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.System.getenv
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.sql.DriverManager
import javax.net.ssl.X509TrustManager
import kotlin.use

object StrapiClientTokenCache {
    private val cache = mutableMapOf<String, Pair<StrapiLoginData, Long>>()

    fun getCacheForClient(clientHash: String): Pair<StrapiLoginData, Long>? {
        return cache[clientHash]
    }

    fun setCacheForClient(clientHash: String, token: StrapiLoginData, timestamp: Long) {
        cache[clientHash] = Pair(token, timestamp)
    }

    fun clearCacheForClient(clientHash: String) {
        cache.remove(clientHash)
    }

    fun clearAllCache() {
        cache.clear()
    }
}


fun StrapiInstance.client(): StrapiClient = StrapiClient(this)

//fun StrapiInstance.getDbConnection() = run {
//    val host = this.dbHost?.takeIf { it.isNotBlank() }
//    val port = this.dbPort ?: 5432
//    val db = this.dbName?.takeIf { it.isNotBlank() }
//    val user = this.dbUser?.takeIf { it.isNotBlank() }
//    val pass = this.dbPassword?.takeIf { it.isNotBlank() }
//    val sslmode = this.dbSslMode?.takeIf { it.isNotBlank() } ?: "prefer"
//    require(host != null && db != null && user != null && pass != null) { "Missing DB connection data for instance '${this.name}'." }
//    val url = "jdbc:postgresql://$host:$port/$db?sslmode=$sslmode"
//    val schema = (this.dbSchema?.takeIf { it.isNotBlank() } ?: "public").replace("\"", "")
//    val conn = DriverManager.getConnection(url, user, pass)
//    conn.createStatement().use { it.execute("SET search_path TO \"$schema\"") }
//    conn
//}



private fun buildClient(proxyConfig: ProxyConfig?): HttpClient = HttpClient(CIO) {

    install(ContentNegotiation) {
        json(JsonParser)
    }
    install(Logging) {
        level = LogLevel.ALL
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 60000  // 60 secondi
        connectTimeoutMillis = 15000  // 15 secondi
        socketTimeoutMillis = 60000   // 60 secondi
    }
    install(HttpRequestRetry) {
        retryOnServerErrors(maxRetries = 3)
        retryOnException(maxRetries = 3, retryOnTimeout = true)
        exponentialDelay(
            base = 2.0,
            maxDelayMs = 10000
        )
    }


    expectSuccess = true
    engine {
        maxConnectionsCount = 50
        dispatcher = Dispatchers.IO
        pipelining = true

        proxy = proxyConfig
        https {
            trustManager = object : X509TrustManager {
                override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {
                }

                override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {
                }

                override fun getAcceptedIssuers(): Array<X509Certificate>? = null

            }
        }
    }
}

class ClientSelector {

    private val logger = LoggerFactory.getLogger(this::class.java)

    private val clientNoProxy = buildClient(null)
    private val clientHttpsProxy: HttpClient?
    private val clientHttpProxy: HttpClient?
    private val noProxyList: List<String>?

    init {
        val httpProxy = getenv("HTTP_PROXY")
        logger.info("HTTP_PROXY: $httpProxy")
        val httpsProxy = getenv("HTTPS_PROXY")
        logger.info("HTTPS_PROXY: $httpsProxy")
        noProxyList = getenv("NO_PROXY")?.let { noProxyString -> noProxyString.split(",").map { it.trim() } }
        logger.info("NO_PROXY: ${noProxyList?.joinToString(", ") { "\"$it\"" } ?: "null"}")
        clientHttpProxy = if (httpProxy != null) {
            logger.info("Using HTTP proxy $httpProxy")
            buildClient(ProxyBuilder.http(httpProxy))
        } else {
            null
        }
        clientHttpsProxy = if (httpsProxy != null) {
            logger.info("Using HTTPS proxy $httpsProxy")
            buildClient(ProxyBuilder.http(httpsProxy))
        } else {
            null
        }
    }

    fun getClientForUrl(url: String): HttpClient {
        logger.info("Getting client for URL $url")
        val urlObj = try {
            java.net.URL(url)
        } catch (e: Exception) {
            return clientHttpsProxy ?: clientNoProxy
        }
        val proxy by lazy {
            if (urlObj.protocol == "https") {
                logger.info("URL $urlObj matches https_proxy list, returning client with proxy ")
                clientHttpsProxy
            } else {
                logger.info("URL $urlObj matches http_proxy list, returning client with proxy ")
                clientHttpProxy
            }
        }

        return if (noProxyList?.any { urlObj.host.contains(it) } == true) {
            logger.info("URL $urlObj matches no_proxy list, returning client without proxy")
            clientNoProxy
        } else {
            logger.info("URL $urlObj does not match no_proxy list, returning client with proxy ")
            proxy ?: clientNoProxy
        }
    }

}


class StrapiClient(
    val name: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val username: String,
    private val password: String
) {

    companion object {
        private val loginMutex = Mutex()
        private val logger = LoggerFactory.getLogger(StrapiClient::class.java)
    }

    constructor(strapiInstance: StrapiInstance) : this(
        strapiInstance.name,
        strapiInstance.url,
        strapiInstance.apiKey,
        strapiInstance.username,
        strapiInstance.password
    )

    val clientHash = calculateMD5Hash(baseUrl + apiKey + username + password)

    val selector = ClientSelector()

    // Context for error logging during merge operations
    var role: String = "unknown" // "source" or "target"
    var currentMergeRequestId: Int? = null
    var dataRootFolder: String? = null

    private suspend fun logHttpRequest(
        callType: String,
        method: String,
        url: String,
        authHeader: String?,
        requestHeaders: Map<String, String> = emptyMap(),
        requestBody: String? = null,
        resp: HttpResponse? = null,
        error: Throwable? = null,
        identifier: String? = null,
        responseStatus: String? = null,
        responseBody: String? = null
    ) {
        val mrId = currentMergeRequestId
        val dataRoot = dataRootFolder
        if (mrId == null || dataRoot.isNullOrBlank()) return
        val statusStr = responseStatus ?: try {
            resp?.let { "${it.status.value} ${it.status.description}" }
        } catch (_: Exception) { null }
        val respHeaders: Map<String, String>? = try {
            resp?.headers?.entries()?.associate { it.key to it.value.joinToString(",") }
        } catch (_: Exception) { null }
        val bodyStr = responseBody ?: try {
            resp?.bodyAsText()
        } catch (_: Exception) { null }
        val errMsg = error?.localizedMessage
        ErrorHttpLogger.writeHttpLog(
            dataRootFolder = dataRoot,
            mergeRequestId = mrId,
            role = role,
            callType = callType,
            method = method,
            url = url,
            authHeader = authHeader,
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            responseStatus = statusStr,
            responseHeaders = respHeaders,
            responseBody = bodyStr,
            errorMessage = errMsg,
            identifier = identifier
        )
    }


    suspend fun getLoginToken(): StrapiLoginData = loginMutex.withLock {
        val now = System.currentTimeMillis()
        val cacheTimeout = 20 * 60 * 1000 // 20 minutes in milliseconds

        StrapiClientTokenCache.getCacheForClient(clientHash)?.let { (token, timestamp) ->
            if (now - timestamp < cacheTimeout) {
                return token
            }
        }

        val url = "$baseUrl/admin/login"
        logger.info("Getting login token for client $username $password from $url")
        try {
            val bodyStr = buildJsonObject {
                put("email", username)
                put("password", password)
            }.toString()
            val response = selector.getClientForUrl(url).post(url) {
                setBody(bodyStr)
                headers {
                    append(HttpHeaders.ContentType, "application/json")
                }
            }.body<StrapiLoginResponse>()
            StrapiClientTokenCache.setCacheForClient(clientHash, response.data, now)
            return response.data
        } catch (e: ClientRequestException) {
            logHttpRequest(
                callType = "admin_login",
                method = "POST",
                url = url,
                authHeader = null,
                requestHeaders = mapOf("Content-Type" to "application/json"),
                requestBody = "{\"email\":\"$username\",\"password\":\"$password\"}",
                resp = e.response,
                error = e
            )
            throw e
        } catch (e: ServerResponseException) {
            logHttpRequest(
                callType = "admin_login",
                method = "POST",
                url = url,
                authHeader = null,
                requestHeaders = mapOf("Content-Type" to "application/json"),
                requestBody = "{\"email\":\"$username\",\"password\":\"$password\"}",
                resp = e.response,
                error = e
            )
            throw e
        }
    }

    suspend fun getContentTypes(): List<StrapiContentType> {
        val url = "$baseUrl/api/content-type-builder/content-types"
        val response: ContentTypeResponse = selector.getClientForUrl(url).get(url) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }.body()

        return response.data
    }

    suspend fun getComponentSchema(): List<StrapiComponent> {
        val url = "$baseUrl/api/content-type-builder/components"
        val response: ComponentResponse = selector.getClientForUrl(url).get(url) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiKey")
            }
        }.body()

        return response.data
    }

    /**
     * Get files from the upload API
     */
    suspend fun getFiles(): List<StrapiImage> {
        val token = getLoginToken().token
        val allResults = mutableListOf<StrapiImage>()
        var currentPage = 1

        do {
            val url = "$baseUrl/upload/files"
            val response: JsonObject = try {
                selector.getClientForUrl(url).get(url) {
                    headers { append(HttpHeaders.Authorization, "Bearer $token") }
                    parameter("sort", "createdAt:DESC")
                    parameter("page", currentPage)
                    parameter("pageSize", 50)
                }.body()
            } catch (e: ClientRequestException) {
                logHttpRequest(
                    callType = "get_files",
                    method = "GET",
                    url = url,
                    authHeader = "Bearer $token",
                    resp = e.response,
                    error = e
                )
                throw e
            } catch (e: ServerResponseException) {
                logHttpRequest(
                    callType = "get_files",
                    method = "GET",
                    url = url,
                    authHeader = "Bearer $token",
                    resp = e.response,
                    error = e
                )
                throw e
            }

            val strapiImages = response["results"]?.jsonArray?.map { imageRaw ->
                val strapiImageMetadata: StrapiImageMetadata = JsonParser.decodeFromJsonElement(imageRaw)
                StrapiImage(strapiImageMetadata, imageRaw.jsonObject)

            }
            val pageCount =
                response["pagination"]?.jsonObject?.let { pagination -> pagination["pageCount"]?.jsonPrimitive?.int }
                    ?: 0


            strapiImages?.let { allResults.addAll(it) }
            currentPage++
        } while (currentPage <= pageCount)

        return allResults.toList()
    }

    suspend fun getFolders(): List<StrapiFolder> {

        val token = getLoginToken().token
        val url = "$baseUrl/upload/folders"
        val response: FolderResponse = try {
            selector.getClientForUrl(url).get(url) {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }.body()
        } catch (e: ClientRequestException) {
            logHttpRequest(
                callType = "get_folders",
                method = "GET",
                url = url,
                authHeader = "Bearer $token",
                resp = e.response,
                error = e
            )
            throw e
        } catch (e: ServerResponseException) {
            logHttpRequest(
                callType = "get_folders",
                method = "GET",
                url = url,
                authHeader = "Bearer $token",
                resp = e.response,
                error = e
            )
            throw e
        }
        val folders = response.data
        val folderByPathId = folders.associateBy { it.pathId }


        val folderFix = folders.map { folder ->
            val parts = folder.path.split("/").filter { it.isNotEmpty() }
            val mapped = parts.mapNotNull { pathElement ->
                val pathId = pathElement.toInt()
                if (pathId == folder.pathId) null
                else
                    folderByPathId[pathId]?.name
            }
            val fullPath = if (mapped.isEmpty())
                "/" + folder.name
            else {
                val prefix = mapped.joinToString("/", prefix = "/", postfix = "/")
                prefix + folder.name
            }
            folder.copy(pathFull = fullPath)
        }

        return folderFix

    }

    /**
     * Create a folder in Strapi
     * @param name The name of the folder
     * @param parent The parent of the folder
     * @return The created folder
     */
    suspend fun createFolder(name: String, parent: Int?): StrapiFolder {
        val token = getLoginToken().token
        val url = "$baseUrl/upload/folders"
        val bodyObj = JsonObject(mapOf(
            "name" to JsonPrimitive(name),
            "parent" to (parent?.let { JsonPrimitive(parent) } ?: JsonNull)
        ))
        val response = try {
            selector.getClientForUrl(url).post(url) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $token")
                    append(HttpHeaders.ContentType, "application/json")
                }
                setBody(bodyObj)
            }.body<JsonObject>()
        } catch (e: ClientRequestException) {
            logHttpRequest(
                callType = "folder_create",
                method = "POST",
                url = url,
                authHeader = "Bearer $token",
                requestHeaders = mapOf("Content-Type" to "application/json"),
                requestBody = bodyObj.toString(),
                resp = e.response,
                error = e
            )
            throw e
        } catch (e: ServerResponseException) {
            logHttpRequest(
                callType = "folder_create",
                method = "POST",
                url = url,
                authHeader = "Bearer $token",
                requestHeaders = mapOf("Content-Type" to "application/json"),
                requestBody = bodyObj.toString(),
                resp = e.response,
                error = e
            )
            throw e
        }

        // Extract the folder data from the response
        val folderData = response["data"]?.jsonObject ?: throw IllegalStateException("Failed to create folder")
        return StrapiFolder(
            id = folderData["id"]?.jsonPrimitive?.int ?: 0,
            documentId = folderData["documentId"]?.jsonPrimitive?.content ?: "",
            name = folderData["name"]?.jsonPrimitive?.content ?: "",
            pathId = folderData["pathId"]?.jsonPrimitive?.int ?: 0,
            path = folderData["path"]?.jsonPrimitive?.content ?: ""
        )
    }

    suspend fun downloadFile(strapiImage: StrapiImage): File {

        val file = withContext(Dispatchers.IO) {
            File.createTempFile(strapiImage.metadata.name, strapiImage.metadata.ext)
        }
        val url = strapiImage.metadata.url

        try {
            val (finalUrl, resp) = if (url.startsWith("http")) {
                val r = selector.getClientForUrl(url).get(url)
                url to r
            } else {
                val u = strapiImage.downloadUrl(baseUrl)
                val r = selector.getClientForUrl(u).get(u) {
                    headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
                }
                u to r
            }
            file.writeBytes(resp.bodyAsBytes())
            return file
        } catch (e: ClientRequestException) {
            val dlUrl = if (url.startsWith("http")) url else strapiImage.downloadUrl(baseUrl)
            logHttpRequest(
                callType = "file_download",
                method = "GET",
                url = dlUrl,
                authHeader = if (dlUrl.startsWith("http")) null else "Bearer $apiKey",
                resp = e.response,
                error = e
            )
            throw e
        } catch (e: ServerResponseException) {
            val dlUrl = if (url.startsWith("http")) url else strapiImage.downloadUrl(baseUrl)
            logHttpRequest(
                callType = "file_download",
                method = "GET",
                url = dlUrl,
                authHeader = if (dlUrl.startsWith("http")) null else "Bearer $apiKey",
                resp = e.response,
                error = e
            )
            throw e
        }

    }

    suspend fun uploadFile(
        id: Int?,
        fileName: String,
        file: File,
        mimeType: String,
        caption: String? = null,
        alternativeText: String? = null,
        folder: Int? = null
    ): StrapiFileUploadResponse {
        // Crea il client HTTP
        val token = getLoginToken().token

        val fileInfo = buildJsonObject {
            put("name", fileName)
            caption?.let { put("caption", it) }
            alternativeText?.let { put("alternativeText", it) }
            folder?.let { put("folder", it) }
        }.toString()
        val url = "$baseUrl/upload" + (id?.let { "?id=$it" } ?: "")

        // Esegui la richiesta
        val response = try {
            selector.getClientForUrl(url).submitFormWithBinaryData(
                url = url,
                formData = formData {
                    append("files", file.readBytes(), Headers.build {
                        append(HttpHeaders.ContentType, mimeType)
                        append(HttpHeaders.ContentDisposition, "form-data; name=\"files\"; filename=\"$fileName\"")
                    })
                    append("fileInfo", fileInfo)
                }
            ) {
                headers { append(HttpHeaders.Authorization, "Bearer $token") }
            }
        } catch (e: ClientRequestException) {
            logHttpRequest(
                callType = "file_upload",
                method = "POST",
                url = url,
                authHeader = "Bearer $token",
                requestHeaders = mapOf("Content-Type" to "multipart/form-data"),
                requestBody = "multipart form fields: files(binary:$fileName;$mimeType), fileInfo=$fileInfo",
                resp = e.response,
                error = e
            )
            throw e
        } catch (e: ServerResponseException) {
            logHttpRequest(
                callType = "file_upload",
                method = "POST",
                url = url,
                authHeader = "Bearer $token",
                requestHeaders = mapOf("Content-Type" to "multipart/form-data"),
                requestBody = "multipart form fields: files(binary:$fileName;$mimeType), fileInfo=$fileInfo",
                resp = e.response,
                error = e
            )
            throw e
        }

        // Gestisci la risposta qui
        val responseBody = response.body<JsonElement>()
        val uploadedFile = when (responseBody) {
            is JsonArray -> responseBody.jsonArray[0].jsonObject
            is JsonObject -> responseBody.jsonObject
            else -> throw IllegalStateException("Unexpected response body type: ${responseBody::class.simpleName}")
        }

        val targetDocumentId = uploadedFile["documentId"]?.jsonPrimitive?.content!!
        val targetId = uploadedFile["id"]?.jsonPrimitive?.content?.toInt()!!


        return StrapiFileUploadResponse(targetId, targetDocumentId)
    }


    /**
     * Delete a file
     */
    suspend fun deleteFile(fileId: String): JsonObject {
        return try {
            val url = "$baseUrl/api/upload/files/$fileId"
            selector.getClientForUrl(url).delete(url) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                }
            }.body()
        } catch (e: io.ktor.client.plugins.ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) {
                JsonObject(emptyMap())
            } else {
                logHttpRequest(
                    callType = "file_delete",
                    method = "DELETE",
                    url = "$baseUrl/api/upload/files/$fileId",
                    authHeader = "Bearer $apiKey",
                    resp = e.response,
                    error = e
                )
                throw e
            }
        } catch (e: ServerResponseException) {
            logHttpRequest(
                callType = "file_delete",
                method = "DELETE",
                url = "$baseUrl/api/upload/files/$fileId",
                authHeader = "Bearer $apiKey",
                resp = e.response,
                error = e
            )
            throw e
        }
    }


    /**
     * Recursively process component parameters
     * @param parameters The parameters to add to
     * @param parentPath The parent path for nested parameters
     * @param componentUid The component UID
     * @param componentByUid Map of component UIDs to component objects
     */
    private fun processComponentParameters(
        parameters: ParametersBuilder,
        parentPath: String,
        componentUid: String?,
        componentByUid: Map<String, StrapiComponent>
    ) {
        if (componentUid == null) return

        val component = componentByUid[componentUid] ?: return

        // Process nested components
        for ((nestedFieldName, nestedAttribute) in component.schema.attributes) {
            if (nestedAttribute.type == "component") {
                // Add parameter for this nested component
                val nestedPath = "$parentPath[$nestedFieldName]"
                logger.debug("Adding nested parameter for $nestedPath")
                parameters.append("populate$nestedPath[populate]", "*")

                // Recursively process nested component
                processComponentParameters(
                    parameters,
                    "$nestedPath",
                    nestedAttribute.component,
                    componentByUid
                )
            }
        }
    }




    suspend fun createContentEntry(contentType: String, data: JsonObject, kind: StrapiContentTypeKind): JsonObject {
        val url = "$baseUrl/api/$contentType"

        logger.info("Creating content entry for $contentType with kind $kind")
        logger.info("Content entry data: {}", data)

        val method = if (kind == StrapiContentTypeKind.SingleType) HttpMethod.Put else HttpMethod.Post
        val bodyStr = buildJsonObject { put("data", data) }.toString()
        return try {
            selector.getClientForUrl(url).request(url) {
                this.method = method
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(io.ktor.http.ContentType.Application.Json)
                }
                setBody(bodyStr)
            }.body<JsonObject>()
        } catch (e: ClientRequestException) {
            logHttpRequest(
                callType = "content_create",
                method = method.value,
                url = url,
                authHeader = "Bearer $apiKey",
                requestHeaders = mapOf("Content-Type" to "application/json"),
                requestBody = bodyStr,
                resp = e.response,
                error = e
            )
            throw e
        } catch (e: ServerResponseException) {
            logHttpRequest(
                callType = "content_create",
                method = method.value,
                url = url,
                authHeader = "Bearer $apiKey",
                requestHeaders = mapOf("Content-Type" to "application/json"),
                requestBody = bodyStr,
                resp = e.response,
                error = e
            )
            throw e
        }
    }

    suspend fun updateContentEntry(
        contentType: String,
        id: String,
        data: JsonObject,
        kind: StrapiContentTypeKind
    ): JsonObject {
        val url = if (kind == StrapiContentTypeKind.SingleType) {
            "$baseUrl/api/$contentType"
        } else {
            "$baseUrl/api/$contentType/$id"
        }

        logger.debug("Updating content entry for $contentType with kind $kind")
        logger.debug("Update URL: $url")
        logger.debug("Update data: {}", data)


        return selector.getClientForUrl(url).put(url) {
            headers {
                append(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(io.ktor.http.ContentType.Application.Json)
            }
            setBody(buildJsonObject {
                put("data", data)
            })
        }.body()
    }

    suspend fun upsertContentEntry(
        contentType: String,
        data: JsonObject,
        kind: StrapiContentTypeKind,
        sourceDocumentId:String,
        targetDocumentId:String? = null
    ): JsonObject {
        val url = if (kind == StrapiContentTypeKind.SingleType) {
            "$baseUrl/api/$contentType"
        } else {
            "$baseUrl/api/$contentType"+ (targetDocumentId?.let { "/$it" } ?: "")
        }

        logger.debug("Updating content entry for $contentType with kind $kind")
        logger.debug("Update URL: $url")
        logger.debug("Update data: {}", data)


        val method = if (kind == StrapiContentTypeKind.SingleType || targetDocumentId != null) HttpMethod.Put else HttpMethod.Post
        val bodyStr = buildJsonObject { put("data", data) }.toString()
        val response: JsonObject = try {
            selector.getClientForUrl(url).request(url) {
                this.method = method
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(io.ktor.http.ContentType.Application.Json)
                }
                setBody(bodyStr)
            }.body()
        } catch (e: ClientRequestException) {
            logHttpRequest(
                callType = "content_upsert",
                method = method.value,
                url = url,
                authHeader = "Bearer $apiKey",
                requestHeaders = mapOf("Content-Type" to "application/json"),
                requestBody = bodyStr,
                resp = e.response,
                error = e,
                identifier = sourceDocumentId
            )
            throw e
        } catch (e: ServerResponseException) {
            logHttpRequest(
                callType = "content_upsert",
                method = method.value,
                url = url,
                authHeader = "Bearer $apiKey",
                requestHeaders = mapOf("Content-Type" to "application/json"),
                requestBody = bodyStr,
                resp = e.response,
                error = e,
                identifier = sourceDocumentId
            )
            throw e
        }

        // Log successful request
        logHttpRequest(
            callType = "content_upsert",
            method = method.value,
            url = url,
            authHeader = "Bearer $apiKey",
            requestHeaders = mapOf("Content-Type" to "application/json"),
            requestBody = bodyStr,
            responseBody = response.toString(),
            responseStatus = "200 OK", 
            identifier = sourceDocumentId
        )
        return response
    }

    suspend fun deleteContentEntry(contentType: String, id: String, kind: String = "collectionType"): Boolean {
        val url = if (kind == "singleType") {
            "$baseUrl/api/$contentType"
        } else {
            "$baseUrl/api/$contentType/$id"
        }

        val success = try {
            selector.getClientForUrl(url).delete(url) {
                headers { append(HttpHeaders.Authorization, "Bearer $apiKey") }
            }.status.isSuccess()
        } catch (e: ClientRequestException) {
            logHttpRequest(
                callType = "content_delete",
                method = "DELETE",
                url = url,
                authHeader = "Bearer $apiKey",
                resp = e.response,
                error = e,
                identifier = id // Per i DELETE l'id è tipicamente il documentId o l'id numerico a seconda di come viene chiamato
            )
            throw e
        } catch (e: ServerResponseException) {
            logHttpRequest(
                callType = "content_delete",
                method = "DELETE",
                url = url,
                authHeader = "Bearer $apiKey",
                resp = e.response,
                error = e,
                identifier = id
            )
            throw e
        }
        
        // Log successful deletion
        if (success) {
            logHttpRequest(
                callType = "content_delete",
                method = "DELETE",
                url = url,
                authHeader = "Bearer $apiKey",
                responseStatus = "200 OK",
                identifier = id
            )
        }
        return success
    }


    fun cleanupStrapiJson(element: JsonElement): JsonElement {
        // Lista dei campi tecnici da rimuovere
        val technicalFields = setOf(
            "id",
            "url",
            "formats",
            "related",
            "createdAt",
            "updatedAt",
            "publishedAt",
            "provider",
            "provider_metadata",
            "hash",
            "ext",
            "mime",
            "size",
            "previewUrl",
            "path",
            "sizeInBytes",
            "__component",
            "createdBy",
            "updatedBy",
            "pathId",
            "isUrlSigned",
            "width",
            "height",
            "localizations",
            "folderPath"
        )

        return when (element) {

            is JsonObject -> {
                JsonObject(
                    element.entries
                        .filter { (key, value) -> key !in technicalFields && value !is JsonNull && (value !is JsonObject || value.isNotEmpty()) }
                        .associate { (key, value) -> key to cleanupStrapiJson(value) }
                )
            }

            is JsonArray -> {
                JsonArray(element.map { cleanupStrapiJson(it) }.sortedBy { it.toString() })
            }

            else -> element
        }
    }


    private fun JsonObject.generateHash(fieldsToIgnore: List<String> = listOf()): String {
        fun filterRecursively(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.filterKeys { it !in fieldsToIgnore }
                .toSortedMap()
                .mapValues { (_, v) -> filterRecursively(v) })

            is JsonArray -> JsonArray(element.map { filterRecursively(it) })
            else -> element
        }

        val filteredObject = filterRecursively(this) as JsonObject
        val jsonString = filteredObject.toString()
        val bytes = MessageDigest.getInstance("SHA-256").digest(jsonString.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }

    }
}
