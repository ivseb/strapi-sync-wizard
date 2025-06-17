package it.sebi.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkConstructor
import it.sebi.JsonParser
import it.sebi.client.StrapiClient
import it.sebi.models.*
import it.sebi.repository.MergeRequestDocumentMappingRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.assertNotNull

/**
 * Test for SyncService
 * This test uses MockK to mock the dependencies of SyncService
 * and uses real JSON files from source and target folders for testing
 */
class SyncServiceTest {

    private lateinit var mockRepository: MergeRequestDocumentMappingRepository
    private lateinit var syncService: SyncService
    private lateinit var schemaData: SchemaCompatibilityResult
    private lateinit var sourceInstance: StrapiInstance
    private lateinit var targetInstance: StrapiInstance
    private lateinit var mergeRequest: MergeRequestWithInstancesDTO

    @Before
    fun setup() {
        // Load schema from file
        val schemaJson = File("src/test/resources/merge_request_1/schema_compatibility.json").readText()
        schemaData = Json.decodeFromString(schemaJson)

        // Create test data
        sourceInstance = StrapiInstance(
            id = 1,
            name = "Source Instance",
            url = "http://source-instance.com:1337",
            apiKey = "source-api-key",
            username = "source-username",
            password = "source-password"
        )

        targetInstance = StrapiInstance(
            id = 2,
            name = "Target Instance",
            url = "http://target-instance.com",
            apiKey = "target-api-key",
            username = "target-username",
            password = "target-password"
        )

        mergeRequest = MergeRequestWithInstancesDTO(
            id = 1,
            name = "Test Merge Request",
            description = "Test description",
            sourceInstance = sourceInstance,
            targetInstance = targetInstance,
            status = MergeRequestStatus.CREATED,
            createdAt = OffsetDateTime.now(),
            updatedAt = OffsetDateTime.now()
        )

        // Mock the repository
        mockRepository = mockk(relaxed = true)

        // Create the SyncService with the mock repository
        syncService = SyncService(mockRepository)
    }

    /**
     * Test for compareContentWithRelationships function
     * This test mocks the StrapiClient to return data from JSON files
     */
    @Test
    fun testCompareContentWithRelationships() {
        runBlocking {
            // Mock StrapiClient creation and methods
            mockkConstructor(StrapiClient::class)

            // Set up a counter to alternate between source and target
            var clientCounter = 0

            // Set up name property for source and target clients
            coEvery {
                anyConstructed<StrapiClient>().name
            } answers {
                // Use a counter to alternate between source and target
                // First call will be source, second will be target, and so on
                clientCounter++
                val clientType = if (clientCounter % 2 == 1) "source" else "target"

                println("[DEBUG_LOG] Client counter: $clientCounter, returning: $clientType")

                clientType
            }

            // Mock getContentEntries for source client
            coEvery {
                anyConstructed<StrapiClient>().getContentEntries(any(), any())
            } answers { call ->
                val contentType = call.invocation.args[0] as StrapiContentType
                val fileName = when (contentType.uid) {
                    "api::about.about" -> "api_about.json"
                    "api::article.article" -> "api_articles.json"
                    "api::author.author" -> "api_authors.json"
                    "api::category.category" -> "api_categories.json"
                    "api::global.global" -> "api_global.json"
                    "api::page.page" -> "api_pages.json"
                    "api::product.product" -> "api_products.json"
                    "api::store.store" -> "api_stores.json"
                    else -> return@answers emptyList<EntryElement>()
                }

                // Determine if this is a source or target client based on the URL
                val isSource = this@answers.invocation.self.toString().contains("source")
                val folder = if (isSource) "src/test/resources/source" else "src/test/resources/target"

                // Load the JSON file
                val file = File("$folder/$fileName")
                if (!file.exists()) {
                    println("[DEBUG_LOG] File not found: $folder/$fileName")
                    return@answers emptyList<EntryElement>()
                }

                try {
                    val content = file.readText()
                    val jsonObject = JsonParser.parseToJsonElement(content).jsonObject
                    val data = when {
                        jsonObject["data"] is JsonArray -> jsonObject["data"]?.jsonArray
                        jsonObject["data"] is JsonObject -> buildJsonArray { add(jsonObject["data"]!!.jsonObject) }
                        else -> listOf()

                    }


                    data?.map { entry ->
                        val id = entry.jsonObject["id"]!!.jsonPrimitive.content.toInt()
                        val documentId = entry.jsonObject["documentId"]!!.jsonPrimitive.content
                        val cont =
                            StrapiContent(StrapiContentMetadata(id, documentId), entry.jsonObject, entry.jsonObject)
                        EntryElement(cont, "hash-${id}")
                    } ?: return@answers emptyList<EntryElement>()
                } catch (e: Exception) {
                    println("[DEBUG_LOG] Error parsing file $folder/$fileName: ${e.message}")
                    emptyList<EntryElement>()
                }
            }

            // Counters to track calls
            var sourceCallCount = 0
            var targetCallCount = 0

            // Mock getFiles
            coEvery {
                anyConstructed<StrapiClient>().getFiles()
            } answers {
                // Determine if this is a source or target client based on the name property
                val mockClient = this.invocation.self as StrapiClient
                val clientToString = mockClient.toString()
                val clientName = mockClient.name
                val isSource = clientName == "source"
                val folder = if (isSource) "src/test/resources/source" else "src/test/resources/target"

                // Increment counters
                if (isSource) {
                    sourceCallCount++
                    println("[DEBUG_LOG] SOURCE CLIENT CALL #$sourceCallCount")
                } else {
                    targetCallCount++
                    println("[DEBUG_LOG] TARGET CLIENT CALL #$targetCallCount")
                }

                println("[DEBUG_LOG] Client toString: $clientToString")
                println("[DEBUG_LOG] Client name: $clientName, isSource: $isSource, folder: $folder")

                // Load the JSON file
                val file = File("$folder/upload_files_page1.json")
                if (!file.exists()) {
                    println("[DEBUG_LOG] File not found: $folder/upload_files_page1.json")
                    return@answers emptyList<StrapiImage>()
                }

                try {
                    val content = file.readText()
                    val jsonObject = Json.parseToJsonElement(content).jsonObject
                    val results = jsonObject["results"]?.jsonArray ?: return@answers emptyList<StrapiImage>()

                    // Create mock StrapiImage objects
                    results.map { json ->
                        StrapiImage(JsonParser.decodeFromJsonElement(json), json.jsonObject)
                    }
                } catch (e: Exception) {
                    println("[DEBUG_LOG] Error parsing file $folder/upload_files_page1.json: ${e.message}")
                    emptyList<StrapiImage>()
                }
            }

            // Run the test
            val result = syncService.compareContentWithRelationships(mergeRequest, schemaData.sourceContentTypes,
                listOf()
            )

            // Verify the result
            assertNotNull(result)
            assertNotNull(result.files)
            assertNotNull(result.singleTypes)
            assertNotNull(result.collectionTypes)
            assertNotNull(result.contentTypeRelationships)

            // Verify that the mocked methods were called
            coVerify(atLeast = 1) {
                anyConstructed<StrapiClient>().getContentEntries(any(), any())
            }

            // Verify that getFiles() is called on both source and target clients
            var sourceClientCalled = false
            var targetClientCalled = false

            // Use a custom verification to check each client
            coVerify {
                anyConstructed<StrapiClient>().getFiles()
            }

            // Print the call counts
            println("[DEBUG_LOG] Source client calls: $sourceCallCount")
            println("[DEBUG_LOG] Target client calls: $targetCallCount")

            // Assert that both clients were called
            assert(sourceCallCount > 0) { "Source client getFiles() was not called" }
            assert(targetCallCount > 0) { "Target client getFiles() was not called" }
        }
    }
}
