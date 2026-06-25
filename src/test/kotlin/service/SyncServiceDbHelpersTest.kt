package it.sebi.service

import it.sebi.models.StrapiContent
import it.sebi.models.StrapiContentMetadata
import it.sebi.models.StrapiImage
import it.sebi.models.StrapiImageMetadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure (no DB/IO) helper tests for SyncServiceDb top-level functions. */
class SyncServiceDbHelpersTest {

    private fun content(
        documentId: String,
        clean: JsonObject,
        syncId: String? = null,
    ) = StrapiContent(
        metadata = StrapiContentMetadata(id = 1, documentId = documentId, uniqueKey = documentId, locale = null, syncId = syncId),
        rawData = clean,
        cleanData = clean,
    )

    private fun image(id: Int, documentId: String, name: String, sha: String? = null, syncId: String? = null) = StrapiImage(
        metadata = StrapiImageMetadata(
            id = id, documentId = documentId, name = name, hash = "h", ext = ".png", mime = "image/png",
            size = 1.0, url = "/u/$documentId.png", previewUrl = null, provider = "local", folderPath = "/",
            folder = null, locale = null, updatedAt = OffsetDateTime.parse("2024-01-01T00:00:00Z"),
            calculatedSha = sha, syncId = syncId,
        ),
        rawData = JsonObject(emptyMap()),
    )

    // contentLabelOf ----------------------------------------------------------

    @Test
    fun `contentLabelOf prefers a label-named field`() {
        val c = content("doc1", buildJsonObject {
            put("body", "some long body text value")
            put("title", "Hello World")
        })
        assertEquals("Hello World", contentLabelOf(c))
    }

    @Test
    fun `contentLabelOf finds label nested inside components`() {
        val c = content("doc1", buildJsonObject {
            put("section", buildJsonObject { put("heading", "Nested Heading") })
        })
        assertEquals("Nested Heading", contentLabelOf(c))
    }

    @Test
    fun `contentLabelOf falls back to any short text then documentId`() {
        val onlyText = content("doc1", buildJsonObject { put("body", "Plain text") })
        assertEquals("Plain text", contentLabelOf(onlyText))

        val empty = content("doc-fallback", buildJsonObject {})
        assertEquals("doc-fallback", contentLabelOf(empty))
    }

    // contentFingerprint ------------------------------------------------------

    @Test
    fun `contentFingerprint ignores technical fields and ordering`() {
        val a = content("docA", buildJsonObject {
            put("id", 1)
            put("title", "T")
            put("documentId", "docA")
            put("updatedAt", "2024-01-01")
        })
        val b = content("docB", buildJsonObject {
            put("title", "T")
            put("id", 99)
            put("documentId", "docB")
            put("updatedAt", "2025-06-01")
        })
        // Same business content, different identity/timestamps → same fingerprint.
        assertEquals(contentFingerprint(a, emptyMap()), contentFingerprint(b, emptyMap()))
    }

    @Test
    fun `contentFingerprint normalizes links to sync identity`() {
        val withLinks = content("docA", buildJsonObject {
            put("title", "T")
            put("__links", buildJsonObject {
                put("rel", buildJsonArray { add(JsonPrimitive("targetDoc")) })
            })
        })
        val syncByDoc = mapOf("targetDoc" to "SYNC-7")
        val fp = contentFingerprint(withLinks, syncByDoc)
        assertTrue(fp.contains("SYNC-7"), "fingerprint should embed resolved sync id, was: $fp")
        // A different document content but pointing at the same identity yields identical link canon.
        val other = content("docB", buildJsonObject {
            put("title", "T")
            put("__links", buildJsonObject {
                put("rel", buildJsonArray { add(JsonPrimitive("targetDoc")) })
            })
        })
        assertEquals(fp, contentFingerprint(other, syncByDoc))
    }

    // buildIdentityMap --------------------------------------------------------

    @Test
    fun `buildIdentityMap prefers syncId then sha then name for files`() {
        val files = listOf(
            image(1, "fDoc1", "a.png", sha = "SHA1", syncId = "SID1"),
            image(2, "fDoc2", "b.png", sha = "SHA2", syncId = null),
            image(3, "fDoc3", "c.png", sha = null, syncId = null),
        )
        val m = buildIdentityMap(emptyMap(), files, emptyMap())
        assertEquals("id:SID1", m["fDoc1"])
        assertEquals("file:SHA2", m["fDoc2"])
        assertEquals("file:c.png", m["fDoc3"])
    }

    @Test
    fun `buildIdentityMap uses syncId for content else content fingerprint`() {
        val withSync = content("cDoc1", buildJsonObject { put("title", "X") }, syncId = "CID1")
        val noSync = content("cDoc2", buildJsonObject { put("title", "Y") })
        val m = buildIdentityMap(mapOf("api::a.a" to listOf(withSync, noSync)), emptyList(), emptyMap())
        assertEquals("id:CID1", m["cDoc1"])
        assertTrue(m["cDoc2"]!!.startsWith("c:"))
    }

    // buildRefResolver --------------------------------------------------------

    @Test
    fun `buildRefResolver describes files and content`() {
        val files = listOf(image(5, "fDoc", "pic.png", syncId = "FSID"))
        val rows = mapOf("api::article.article" to listOf(content("cDoc", buildJsonObject { put("title", "Hi") })))
        val resolver = buildRefResolver(rows, files, identityByDoc = mapOf("fDoc" to "id:FSID"))

        val fileRef = resolver["fDoc"]!!
        assertTrue(fileRef.isFile)
        assertEquals("pic.png", fileRef.label)
        assertEquals(5, fileRef.fileId)
        assertEquals("id:FSID", fileRef.contentHash)

        val contentRef = resolver["cDoc"]!!
        assertTrue(!contentRef.isFile)
        assertEquals("Hi", contentRef.label)
        assertEquals("article", contentRef.refType)
    }

    // normalizeLinksBySyncId --------------------------------------------------

    @Test
    fun `normalizeLinksBySyncId replaces doc ids with sorted identities`() {
        val obj = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
            "__links" to buildJsonObject {
                put("rel", buildJsonArray {
                    add(JsonPrimitive("docB"))
                    add(JsonPrimitive("docA"))
                    add(JsonPrimitive("docUnknown"))
                })
            }
        )
        normalizeLinksBySyncId(obj, mapOf("docA" to "SID-A", "docB" to "SID-B"))

        val rel = (obj["__links"] as JsonObject)["rel"]!!.jsonArray.map { it.jsonPrimitive.content }
        // Sorted; unknown doc ids fall back to "doc:<id>".
        assertEquals(listOf("SID-A", "SID-B", "doc:docUnknown"), rel)
    }

    @Test
    fun `normalizeLinksBySyncId is a no-op when there are no links`() {
        val obj = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("title" to JsonPrimitive("X"))
        normalizeLinksBySyncId(obj, emptyMap())
        assertTrue(!obj.containsKey("__links"))
        assertEquals("X", (obj["title"] as JsonPrimitive).content)
    }
}
