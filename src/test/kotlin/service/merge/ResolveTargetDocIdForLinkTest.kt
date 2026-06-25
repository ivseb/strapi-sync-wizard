package it.sebi.service.merge

import it.sebi.models.ContentTypeComparisonResultKind
import it.sebi.models.ContentTypeComparisonResultMapWithRelationships
import it.sebi.models.ContentTypeFileComparisonResult
import it.sebi.models.MergeRequestDocumentMapping
import it.sebi.models.STRAPI_FILE_CONTENT_TYPE_NAME
import it.sebi.models.StrapiImage
import it.sebi.models.StrapiImageMetadata
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Regression tests for [ContentJsonUtils.resolveTargetDocIdForLink].
 *
 * Core invariant under test: numeric ids COLLIDE across the source and target Strapi instances
 * (both start at 1). The resolver must give SOURCE ids global priority over TARGET ids, otherwise a
 * source-id reference wrongly resolves to an unrelated target doc ("Missing mapping" errors).
 */
class ResolveTargetDocIdForLinkTest {

    private fun image(id: Int, documentId: String): StrapiImage = StrapiImage(
        metadata = StrapiImageMetadata(
            id = id,
            documentId = documentId,
            name = "file-$documentId.png",
            hash = "hash-$documentId",
            ext = ".png",
            mime = "image/png",
            size = 1.0,
            url = "/uploads/$documentId.png",
            previewUrl = null,
            provider = "local",
            folderPath = "/",
            folder = null,
            locale = null,
            updatedAt = OffsetDateTime.parse("2024-01-01T00:00:00Z"),
        ),
        rawData = JsonObject(emptyMap()),
    )

    private fun filePair(
        source: StrapiImage?,
        target: StrapiImage?,
        state: ContentTypeComparisonResultKind,
    ) = ContentTypeFileComparisonResult(sourceImage = source, targetImage = target, compareState = state)

    private fun comparison(files: List<ContentTypeFileComparisonResult>) =
        ContentTypeComparisonResultMapWithRelationships(files = files)

    private fun mapping(sourceDoc: String, targetDoc: String) = MergeRequestDocumentMapping(
        sourceStrapiInstanceId = 1,
        targetStrapiInstanceId = 2,
        contentType = STRAPI_FILE_CONTENT_TYPE_NAME,
        sourceDocumentId = sourceDoc,
        targetDocumentId = targetDoc,
    )

    @Test
    fun `source id wins over a colliding target id`() {
        // Pair A: source image id=1 (doc srcA) paired to target image id=5 (doc tgtA), IDENTICAL.
        val pairA = filePair(
            source = image(id = 1, documentId = "srcA"),
            target = image(id = 5, documentId = "tgtA"),
            state = ContentTypeComparisonResultKind.IDENTICAL,
        )
        // Pair B: a DIFFERENT file whose TARGET image also has id=1 (doc tgtB) — colliding target id.
        val pairB = filePair(
            source = image(id = 9, documentId = "srcB"),
            target = image(id = 1, documentId = "tgtB"),
            state = ContentTypeComparisonResultKind.DIFFERENT,
        )
        val cmp = comparison(listOf(pairA, pairB))

        val resolved = ContentJsonUtils.resolveTargetDocIdForLink(
            "files", STRAPI_FILE_CONTENT_TYPE_NAME, id = 1, cmp, emptyMap(),
        )

        // id=1 is a SOURCE id (srcA) → must resolve via source pairing to tgtA, NOT to the target tgtB
        // that happens to share numeric id 1.
        assertEquals("tgtA", resolved)
    }

    @Test
    fun `identical file pairing resolves without a mapping row`() {
        val pair = filePair(
            source = image(id = 1, documentId = "srcA"),
            target = image(id = 5, documentId = "tgtA"),
            state = ContentTypeComparisonResultKind.IDENTICAL,
        )
        val cmp = comparison(listOf(pair))

        val resolved = ContentJsonUtils.resolveTargetDocIdForLink(
            "files", STRAPI_FILE_CONTENT_TYPE_NAME, id = 1, cmp, emptyMap(),
        )

        assertEquals("tgtA", resolved)
    }

    @Test
    fun `mappingMap takes precedence over compare pairing`() {
        val pair = filePair(
            source = image(id = 1, documentId = "srcA"),
            target = image(id = 5, documentId = "tgtA"),
            state = ContentTypeComparisonResultKind.IDENTICAL,
        )
        val cmp = comparison(listOf(pair))

        // mappingMap routes srcA to a DIFFERENT target doc than the compare pairing (tgtA).
        val mappingMap = mapOf(
            STRAPI_FILE_CONTENT_TYPE_NAME to mapOf("srcA" to mapping("srcA", "tgtMapped")),
        )

        val resolved = ContentJsonUtils.resolveTargetDocIdForLink(
            "files", STRAPI_FILE_CONTENT_TYPE_NAME, id = 1, cmp, mappingMap,
        )

        assertEquals("tgtMapped", resolved)
    }

    @Test
    fun `source present with no pairing and no mapping throws`() {
        // ONLY_IN_SOURCE: source id=1 (srcA) but no target pairing and no mapping row.
        val pair = filePair(
            source = image(id = 1, documentId = "srcA"),
            target = null,
            state = ContentTypeComparisonResultKind.ONLY_IN_SOURCE,
        )
        val cmp = comparison(listOf(pair))

        assertFailsWith<IllegalStateException> {
            ContentJsonUtils.resolveTargetDocIdForLink(
                "files", STRAPI_FILE_CONTENT_TYPE_NAME, id = 1, cmp, emptyMap(),
            )
        }
    }

    @Test
    fun `null id returns null`() {
        val pair = filePair(
            source = image(id = 1, documentId = "srcA"),
            target = image(id = 5, documentId = "tgtA"),
            state = ContentTypeComparisonResultKind.IDENTICAL,
        )
        val cmp = comparison(listOf(pair))

        val resolved = ContentJsonUtils.resolveTargetDocIdForLink(
            "files", STRAPI_FILE_CONTENT_TYPE_NAME, id = null, cmp, emptyMap(),
        )

        assertNull(resolved)
    }

    @Test
    fun `target only id falls back to the target doc`() {
        // ONLY_IN_TARGET: id=7 matches only a target entry → it already is the target documentId.
        val pair = filePair(
            source = null,
            target = image(id = 7, documentId = "tgtOnly"),
            state = ContentTypeComparisonResultKind.ONLY_IN_TARGET,
        )
        val cmp = comparison(listOf(pair))

        val resolved = ContentJsonUtils.resolveTargetDocIdForLink(
            "files", STRAPI_FILE_CONTENT_TYPE_NAME, id = 7, cmp, emptyMap(),
        )

        assertEquals("tgtOnly", resolved)
    }
}
