package service.merge

import io.ktor.server.config.ApplicationConfig
import it.sebi.JsonParser
import it.sebi.models.ContentTypeComparisonResultMapWithRelationships
import it.sebi.models.SchemaDbCompatibilityResult
import it.sebi.service.ComparisonPrefetchCache
import it.sebi.service.cleanObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.File

private data class CacheEntry<T>(
    val content: T,
    val timestamp: Long
)

class MergeRequestServiceFileUtils(
    val applicationConfig: ApplicationConfig,
) {
    private val cache = mutableMapOf<String, CacheEntry<Any>>()
    private val cacheTimeout = 3600000L // 1 hour in milliseconds
    val logger = LoggerFactory.getLogger(this::class.java)
    val dataFolder = applicationConfig.property("application.dataFolder").getString()

    init {
        // Initialize data folder asynchronously
        runBlocking {
            initializeDataFolder()
        }
    }



    private suspend fun initializeDataFolder() {
        withContext(Dispatchers.IO) {
            try {
                // Create data folder if it doesn't exist
                val folder = File(dataFolder)
                if (!folder.exists()) {
                    folder.mkdirs()
                }
            } catch (e: Exception) {
                logger.error("Error initializing data folder: ${e.message}", e)
                // We don't rethrow here as this is initialization code
                // and we don't want to prevent the service from starting
            }
        }
    }

    /**
     * Get the file path for storing schema compatibility results for a merge request
     */
    private suspend fun getSchemaCompatibilityFilePath(id: Int): String {
        return withContext(Dispatchers.IO) {
            val mergeRequestFolder = File(dataFolder, "merge_request_$id")
            if (!mergeRequestFolder.exists()) {
                try {
                    mergeRequestFolder.mkdirs()
                } catch (e: Exception) {
                    logger.error("Error creating directory for merge request $id: ${e.message}", e)
                    throw e
                }
            }
            File(mergeRequestFolder, "schema_compatibility.json").absolutePath
        }
    }

    suspend fun deleteFilesOfMergeRequest(id: Int) {
        withContext(Dispatchers.IO) {
            try {
                val mergeRequestFolder = File(dataFolder, "merge_request_$id")
                if (mergeRequestFolder.exists()) {
                    mergeRequestFolder.deleteRecursively()
                }
            } catch (e: Exception) {
                logger.error("Error deleting merge request folder for merge request $id: ${e.message}", e)
                // Continue with deleting the merge request from the database even if file deletion fails
            }
        }
    }

    private fun isCacheExpired(entry: CacheEntry<*>): Boolean {
        return System.currentTimeMillis() - entry.timestamp > cacheTimeout
    }

    suspend fun getSchemaCompatibilityFile(id: Int): SchemaDbCompatibilityResult? {
        return withContext(Dispatchers.IO) {
            val schemaFilePath = getSchemaCompatibilityFilePath(id)
            val cachedEntry = cache[schemaFilePath]

            if (cachedEntry != null && !isCacheExpired(cachedEntry)) {
                return@withContext cachedEntry.content as SchemaDbCompatibilityResult
            }

            val schemaFile = File(schemaFilePath)
            if (schemaFile.exists()) {
                try {
                    val fileContent = schemaFile.readText()
                    val fileStorage = JsonParser.decodeFromString<SchemaDbCompatibilityResult>(fileContent)
                    cache[schemaFilePath] = CacheEntry(fileStorage, System.currentTimeMillis())
                    fileStorage
                } catch (e: Exception) {
                    logger.error("Error reading schema compatibility file for merge request $id: ${e.message}", e)
                    null
                }
            } else {
                null
            }
        }
    }

    suspend fun saveSchemaCompatibilityFile(id: Int, schemaResult: SchemaDbCompatibilityResult) {
        withContext(Dispatchers.IO) {
            try {
                val schemaFilePath = getSchemaCompatibilityFilePath(id)
                val schemaResultFile = File(schemaFilePath)
                val jsonContent = JsonParser.encodeToString(schemaResult)
                schemaResultFile.writeText(jsonContent)
                cache[schemaFilePath] = CacheEntry(schemaResult, System.currentTimeMillis())
            } catch (e: Exception) {
                logger.error("Error saving schema compatibility file for merge request $id: ${e.message}", e)
                throw e
            }
        }
    }

    private suspend fun getContentComparisonFilePath(id: Int): String {
        return withContext(Dispatchers.IO) {
            val mergeRequestFolder = File(dataFolder, "merge_request_$id")
            if (!mergeRequestFolder.exists()) {
                try {
                    mergeRequestFolder.mkdirs()
                } catch (e: Exception) {
                    logger.error("Error creating directory for merge request $id: ${e.message}", e)
                    throw e
                }
            }
            File(mergeRequestFolder, "content_comparison.json").absolutePath
        }
    }

    private suspend fun getPrefetchCacheFilePath(id: Int): String = withContext(Dispatchers.IO) {
        val mergeRequestFolder = File(dataFolder, "merge_request_$id")
        if (!mergeRequestFolder.exists()) mergeRequestFolder.mkdirs()
        File(mergeRequestFolder, "prefetch_cache.json").absolutePath
    }

    suspend fun getPrefetchCache(id: Int): ComparisonPrefetchCache? = withContext(Dispatchers.IO) {
        val path = getPrefetchCacheFilePath(id)
        val f = File(path)
        if (!f.exists()) return@withContext null
        try {
            val content = f.readText()
            JsonParser.decodeFromString<ComparisonPrefetchCache>(content)
        } catch (e: Exception) {
            logger.error("Error reading prefetch cache for merge request $id: ${e.message}", e)
            null
        }
    }

    suspend fun savePrefetchCache(id: Int, cache: ComparisonPrefetchCache) = withContext(Dispatchers.IO) {
        try {
            val path = getPrefetchCacheFilePath(id)
            val f = File(path)
            val json = JsonParser.encodeToString(cache)
            f.writeText(json)
        } catch (e: Exception) {
            logger.error("Error saving prefetch cache for merge request $id: ${e.message}", e)
            throw e
        }
    }

    private fun enrichWithCleanData(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> {
            // First, recurse into children
            val processedChildren = element.mapValues { (_, v) -> enrichWithCleanData(v) }.toMutableMap()

            // Heuristic to detect StrapiContent: has metadata (object), rawData (object), and links (array)
            val hasMetadata = processedChildren["metadata"] is JsonObject
            val raw = processedChildren["rawData"] as? JsonObject
            val hasLinks = processedChildren["links"] is JsonArray
            if (hasMetadata && raw != null && hasLinks) {
                // Recompute cleanData from raw
                processedChildren["cleanData"] = cleanObject(raw)
            }
            JsonObject(processedChildren)
        }

        is JsonArray -> JsonArray(element.map { enrichWithCleanData(it) })
        else -> element
    }


    suspend fun getContentComparisonFile(id: Int): ContentTypeComparisonResultMapWithRelationships? {
        return withContext(Dispatchers.IO) {
            val schemaFilePath = getContentComparisonFilePath(id)
            val cachedEntry = cache[schemaFilePath]

            if (cachedEntry != null && !isCacheExpired(cachedEntry)) {
                return@withContext cachedEntry.content as ContentTypeComparisonResultMapWithRelationships
            }

            val schemaFile = File(schemaFilePath)
            if (schemaFile.exists()) {
                try {
                    val fileContent = schemaFile.readText()
                    val enrichedJson = enrichWithCleanData(Json.Default.parseToJsonElement(fileContent)).toString()
                    val fileStorage =
                        JsonParser.decodeFromString<ContentTypeComparisonResultMapWithRelationships>(enrichedJson)
                    cache[schemaFilePath] = CacheEntry(fileStorage, System.currentTimeMillis())
                    fileStorage
                } catch (e: Exception) {
                    logger.error("Error reading content comparison file for merge request $id: ${e.message}", e)
                    null
                }
            } else {
                null
            }
        }
    }

    private fun stripCleanData(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapNotNull { (k, v) ->
                if (k == "cleanData") null else k to stripCleanData(v)
            }.toMap()
        )

        is JsonArray -> JsonArray(element.map { stripCleanData(it) })
        else -> element
    }

    suspend fun saveContentComparisonFile(
        id: Int,
        contentComparison: ContentTypeComparisonResultMapWithRelationships
    ) {
        withContext(Dispatchers.IO) {
            try {
                val schemaFilePath = getContentComparisonFilePath(id)
                val schemaResultFile = File(schemaFilePath)
                val jsonContent = JsonParser.encodeToString(contentComparison)
                val stripped = stripCleanData(Json.Default.parseToJsonElement(jsonContent)).toString()
                schemaResultFile.writeText(stripped)
                cache[schemaFilePath] = CacheEntry(contentComparison, System.currentTimeMillis())
            } catch (e: Exception) {
                logger.error("Error saving content comparison file for merge request $id: ${e.message}", e)
                throw e
            }
        }
    }


}