package it.sebi.service

import it.sebi.models.ContentTypeComparisonResultKind
import it.sebi.models.ContentTypeComparisonResultMapWithRelationships
import it.sebi.models.DbColumnMeta
import it.sebi.models.DbSchema
import it.sebi.models.DbTable
import it.sebi.models.DbTableMetadata
import it.sebi.models.StrapiContent
import it.sebi.models.StrapiContentMetadata
import it.sebi.models.StrapiContentTypeKind
import it.sebi.models.StrapiImage
import it.sebi.models.StrapiImageMetadata
import it.sebi.models.Attribute
import it.sebi.service.merge.ContentJsonUtils
import it.sebi.service.merge.filterByTargetSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Anomalies found by the independent verification on real PCC int/pre/prod data (2026-10-28). */
class RealDataAnomaliesTest {

    // ---- A. JSON attributes are opaque: their inner "id"/timestamps are content -------------------

    // A component row as read: per-instance "id" (metadata) + a JSON column whose items carry "id" (content).
    private fun medicineRow(withIds: Boolean) = buildJsonObject {
        put("id", 77)
        put("category_list_json", buildJsonObject {
            put("subcategories", buildJsonArray {
                add(buildJsonObject { if (withIds) put("id", 14); put("name", "Acidità") })
            })
        })
        put(JSON_KEYS, buildJsonArray { add(JsonPrimitive("category_list_json")) })
    }

    @Test
    fun `cleaning keeps keys inside JSON values and still drops the row id`() {
        val clean = cleanObject(medicineRow(withIds = true))
        assertFalse(clean.containsKey("id"))
        val item = clean["category_list_json"]!!.jsonObject["subcategories"]!!.jsonArray[0].jsonObject
        assertEquals(14, item["id"]!!.jsonPrimitive.content.toInt())
        assertNotEquals(cleanObject(medicineRow(true)), cleanObject(medicineRow(false)), "a JSON-only difference must be visible")
    }

    @Test
    fun `fingerprints and verification see differences inside JSON values`() {
        fun content(row: JsonObject) = StrapiContent(
            metadata = StrapiContentMetadata(id = 1, documentId = "d", uniqueKey = "d", locale = null),
            rawData = row, cleanData = cleanObject(row),
        )
        assertNotEquals(contentFingerprint(content(medicineRow(true)), emptyMap()), contentFingerprint(content(medicineRow(false)), emptyMap()))
        val (ok, reason) = VerificationDiff.classify(cleanObject(medicineRow(true)), cleanObject(medicineRow(false)))
        assertFalse(ok, reason)
    }

    @Test
    fun `JSON values are written exactly as read, without the marker`() {
        val comp = DbTable(
            name = "components_med_categories",
            metadata = DbTableMetadata("med.categories", StrapiContentTypeKind.Component, "components_med_categories", "c", "c", "c",
                false, false, false, false, listOf(DbColumnMeta("categoryListJson", false, "json", false, false))),
        )
        val out = ContentJsonUtils.processJsonElementNew(
            ContentTypeComparisonResultMapWithRelationships(), medicineRow(true), comp, DbSchema(listOf(comp)), mapOf(comp.name to comp), emptyList(),
        ).jsonObject
        assertFalse(out.containsKey(JSON_KEYS))
        assertFalse(out.containsKey("id"))
        val item = out["categoryListJson"]!!.jsonObject["subcategories"]!!.jsonArray[0].jsonObject
        assertEquals("14", item["id"]!!.jsonPrimitive.content)
    }

    // ---- C. emptied components / dynamic zones are cleared on the target --------------------------

    @Test
    fun `components and dynamic zones absent on the source are written as empty`() {
        fun col(name: String, type: String, repeatable: Boolean = false) =
            DbColumnMeta(name, false, type, false, repeatable, component = if (type == "component") "misc.c" else null)
        val type = DbTable(
            name = "collections",
            metadata = DbTableMetadata("api::c.c", StrapiContentTypeKind.CollectionType, "collections", "c", "cs", "C", true, false, true, true,
                listOf(col("title", "string"), col("dlp_tracking", "component"), col("buttons", "component", repeatable = true),
                    col("blocks", "dynamiczone"), col("kept", "component"))),
        )
        val raw = buildJsonObject { put("id", 1); put("title", "T"); put("kept", buildJsonArray { add(buildJsonObject { put("id", 9); put("x", "y") }) }) }
        val out = ContentJsonUtils.processJsonElementNew(
            ContentTypeComparisonResultMapWithRelationships(), raw, type, DbSchema(listOf(type)), mapOf(type.name to type), emptyList(),
        ).jsonObject
        assertEquals(JsonNull, out["dlp_tracking"])
        assertEquals(JsonArray(emptyList()), out["buttons"])
        assertEquals(JsonArray(emptyList()), out["blocks"])
        assertTrue(out.containsKey("kept"))
        assertFalse(out["kept"] is JsonNull)

        // Clearing an attribute the target doesn't have is dropped silently.
        val dropped = mutableListOf<String>()
        filterByTargetSchema(mapOf("title" to Attribute(type = "string")), emptyMap(), out, "collections", dropped)
        assertTrue(dropped.none { it.contains("dlp_tracking") || it.contains("buttons") || it.contains("blocks") }, "got $dropped")
    }

    // ---- B. byte-identical file copies --------------------------------------------------------------

    private fun image(id: Int, doc: String, name: String, sha: String, syncId: String? = null, dhash: String = "fp-$sha") = StrapiImage(
        metadata = StrapiImageMetadata(
            id = id, documentId = doc, name = name, hash = "h$id", ext = ".svg", mime = "image/svg+xml",
            size = 2.0, url = "/u/$doc.svg", previewUrl = null, provider = "local", folderPath = "/",
            folder = null, locale = null, updatedAt = OffsetDateTime.parse("2024-01-01T00:00:00Z"),
            calculatedSha = sha, calculatedHash = dhash, syncId = syncId,
        ),
        rawData = JsonObject(emptyMap()),
    )

    private fun prefetch(src: List<StrapiImage>, tgt: List<StrapiImage>) = ComparisonPrefetch(
        sourceFiles = src, targetFiles = tgt, sourceFileRelations = emptyList(), targetFileRelations = emptyList(),
        sourceCMPSMap = mutableMapOf(), targetCMPSMap = mutableMapOf(), sourceTableMap = mutableMapOf(), targetTableMap = mutableMapOf(),
        srcCompDef = mutableMapOf(), tgtCompDef = mutableMapOf(), sourceRowsByUid = emptyMap(), targetRowsByUid = emptyMap(),
        collectedRelationships = emptySet(),
    )

    @Test
    fun `an asset present twice on the target is paired, not re-uploaded and not deleted`() {
        // pre: one shield icon; prod: the same bytes uploaded twice (both used by coverages).
        val src = listOf(image(297, "pre-shield", "lifesavings_shield_blue.svg", "4fac", syncId = "S1"))
        val tgt = listOf(
            image(243, "prod-shield-a", "lifesavings_shield_blue.svg", "4fac"),
            image(250, "prod-shield-b", "lifesavings_shield_blue.svg", "4fac"),
        )
        val res = compareFilesFromPrefetch(prefetch(src, tgt), emptyMap())
        assertTrue(res.none { it.compareState == ContentTypeComparisonResultKind.ONLY_IN_SOURCE }, "no re-upload: $res")
        assertTrue(res.none { it.compareState == ContentTypeComparisonResultKind.ONLY_IN_TARGET }, "no copy offered for deletion: $res")
        assertEquals(setOf("prod-shield-a", "prod-shield-b"), res.mapNotNull { it.targetImage?.metadata?.documentId }.toSet())
        assertTrue(res.all { it.compareState == ContentTypeComparisonResultKind.IDENTICAL })
        // The real pair comes first: link resolution and reconcile use it.
        assertEquals("prod-shield-a", res.first().targetImage!!.metadata.documentId)
    }

    @Test
    fun `groups of identical bytes pair positionally, same names first`() {
        val src = listOf(image(1, "s-b", "b.svg", "x"), image(2, "s-a", "a.svg", "x"))
        val tgt = listOf(image(3, "t-a", "a.svg", "x"), image(4, "t-b", "b.svg", "x"), image(5, "t-c", "c.svg", "x"))
        val res = compareFilesFromPrefetch(prefetch(src, tgt), emptyMap())
        val pairs = res.map { it.sourceImage?.metadata?.documentId to it.targetImage?.metadata?.documentId }
        assertEquals(listOf("s-a" to "t-a", "s-b" to "t-b"), pairs.take(2))
        assertEquals(3, res.size) // t-c attached as a duplicate copy
        assertEquals(ContentTypeComparisonResultKind.IDENTICAL, res.last().compareState)
    }

    // 256-bit dHashes (64 hex): base, 1 bit off, 3 bits off, unrelated.
    private val h0 = "0".repeat(64)
    private val h1 = "0".repeat(63) + "1"
    private val h3 = "0".repeat(63) + "7"
    private val hFar = "f".repeat(64)

    @Test
    fun `re-encoded copies sharing a name pair with the visually nearest one`() {
        // pre: one illustration; prod: the same picture uploaded three times (two byte-identical copies,
        // one re-encoded differently) — none byte-identical to pre.
        val src = listOf(image(1, "pre-bm", "benessere-mentale-illustration.png", "489c", dhash = h0))
        val tgt = listOf(
            image(10, "prod-far", "benessere-mentale-illustration.png", "79ff", dhash = h3),
            image(11, "prod-near-a", "benessere-mentale-illustration.png", "b879", dhash = h1),
            image(12, "prod-near-b", "benessere-mentale-illustration.png", "b879", dhash = h1),
        )
        val res = compareFilesFromPrefetch(prefetch(src, tgt), emptyMap())
        assertEquals("prod-near-a", res.first().targetImage!!.metadata.documentId, "nearest copy first: $res")
        assertEquals(setOf("prod-far", "prod-near-a", "prod-near-b"), res.mapNotNull { it.targetImage?.metadata?.documentId }.toSet())
        assertTrue(res.all { it.compareState == ContentTypeComparisonResultKind.IDENTICAL }, "$res")
    }

    @Test
    fun `same name but a different picture is not paired`() {
        val src = listOf(image(1, "s", "logo.png", "a", dhash = h0))
        val tgt = listOf(image(2, "t1", "logo.png", "b", dhash = hFar), image(3, "t2", "logo.png", "c", dhash = hFar))
        val res = compareFilesFromPrefetch(prefetch(src, tgt), emptyMap())
        assertEquals(listOf(ContentTypeComparisonResultKind.ONLY_IN_SOURCE), res.filter { it.sourceImage != null }.map { it.compareState })
    }

    @Test
    fun `files with different bytes are still left one-sided`() {
        val res = compareFilesFromPrefetch(prefetch(listOf(image(1, "s", "a.svg", "x")), listOf(image(2, "t", "b.svg", "y"))), emptyMap())
        assertEquals(
            setOf(ContentTypeComparisonResultKind.ONLY_IN_SOURCE, ContentTypeComparisonResultKind.ONLY_IN_TARGET),
            res.map { it.compareState }.toSet(),
        )
    }
}
