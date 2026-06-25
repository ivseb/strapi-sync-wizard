package it.sebi.service

import it.sebi.models.ContentTypeComparisonResultKind
import it.sebi.models.DbColumnMeta
import it.sebi.models.DbTableMetadata
import it.sebi.models.StrapiContent
import it.sebi.models.StrapiContentMetadata
import it.sebi.models.StrapiContentTypeKind
import it.sebi.models.StrapiDraftChannel
import it.sebi.models.StrapiLinkRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Strapi v5 Draft & Publish fidelity: pure (no DB/IO) tests for the read-merge (mergeDraftChannels)
 * and the two-channel comparison (compareSingleType). Locks in the four publish states and the
 * collapse rule so the common published-only path stays unchanged.
 */
class DraftChannelTest {

    private val meta = DbTableMetadata(
        apiUid = "api::thing.thing",
        collectionType = StrapiContentTypeKind.CollectionType,
        collectionName = "things", singularName = "thing", pluralName = "things", displayName = "Thing",
        draftAndPublish = true, localized = false, contentManagerVisible = true, contentTypeBuilderVisible = true,
        columns = listOf(DbColumnMeta("title", required = false, type = "string", unique = false, repeatable = false)),
    )

    /** A raw DB row as fetched: to_jsonb(t) carries every column, incl. published_at (null for drafts). */
    private fun row(documentId: String, title: String, published: Boolean, id: Int): Pair<JsonObject, List<StrapiLinkRef>> {
        val o = buildJsonObject {
            put("id", id)
            put("document_id", documentId)
            put("title", title)
            if (published) put("published_at", "2024-01-01T00:00:00.000Z")
        }
        return o to emptyList()
    }

    // -- mergeDraftChannels ---------------------------------------------------

    @Test
    fun `published only yields one content with no draft overlay`() {
        val out = mergeDraftChannels(listOf(row("d1", "Hello", published = true, id = 2)), meta)
        assertEquals(1, out.size)
        assertNull(out[0].draft)
        assertFalse(out[0].metadata.isDraftOnly)
    }

    @Test
    fun `published plus identical draft collapses away the draft`() {
        val rows = listOf(
            row("d1", "Hello", published = true, id = 2),
            row("d1", "Hello", published = false, id = 1),
        )
        val out = mergeDraftChannels(rows, meta)
        assertEquals(1, out.size)
        assertNull(out[0].draft) // identical -> collapsed, common clean case
        assertFalse(out[0].metadata.isDraftOnly)
    }

    @Test
    fun `published plus divergent draft keeps a draft overlay (modified)`() {
        val rows = listOf(
            row("d1", "Published v1", published = true, id = 2),
            row("d1", "Draft v2", published = false, id = 1),
        )
        val out = mergeDraftChannels(rows, meta)
        assertEquals(1, out.size)
        assertEquals("Published v1", out[0].cleanData["title"]?.toString()?.removeSurrounding("\""))
        assertNotNull(out[0].draft)
        assertEquals("Draft v2", out[0].draft!!.cleanData["title"]?.toString()?.removeSurrounding("\""))
        assertFalse(out[0].metadata.isDraftOnly)
    }

    @Test
    fun `draft only row is flagged isDraftOnly with the draft as primary body`() {
        val out = mergeDraftChannels(listOf(row("d1", "Only a draft", published = false, id = 1)), meta)
        assertEquals(1, out.size)
        assertTrue(out[0].metadata.isDraftOnly)
        assertNull(out[0].draft)
        assertEquals("Only a draft", out[0].cleanData["title"]?.toString()?.removeSurrounding("\""))
    }

    // -- compareSingleType two-channel ---------------------------------------

    private fun content(
        documentId: String, title: String, draftTitle: String? = null, isDraftOnly: Boolean = false,
    ): StrapiContent {
        val clean = buildJsonObject { put("title", title) }
        return StrapiContent(
            metadata = StrapiContentMetadata(id = 1, documentId = documentId, uniqueKey = documentId, locale = null, isDraftOnly = isDraftOnly),
            rawData = clean,
            cleanData = clean,
            draft = draftTitle?.let { StrapiDraftChannel(buildJsonObject { put("title", it) }, buildJsonObject { put("title", it) }) },
        )
    }

    private fun compare(s: StrapiContent?, t: StrapiContent?): ContentTypeComparisonResultKind =
        compareSingleType(
            "things", "api::thing.thing", s, t, StrapiContentTypeKind.CollectionType,
            emptyMap(), mutableMapOf(),
        ).compareState

    @Test
    fun `clean published identical on both sides is IDENTICAL`() {
        assertEquals(ContentTypeComparisonResultKind.IDENTICAL, compare(content("d1", "Same"), content("d1", "Same")))
    }

    @Test
    fun `divergent published is DIFFERENT`() {
        assertEquals(ContentTypeComparisonResultKind.DIFFERENT, compare(content("d1", "A"), content("d1", "B")))
    }

    @Test
    fun `same published but divergent draft overlay is DIFFERENT (modified)`() {
        val src = content("d1", "Pub", draftTitle = "Draft v2")
        val tgt = content("d1", "Pub") // target clean, no divergent draft
        assertEquals(ContentTypeComparisonResultKind.DIFFERENT, compare(src, tgt))
    }

    @Test
    fun `same published and same divergent draft on both sides is IDENTICAL`() {
        val src = content("d1", "Pub", draftTitle = "Draft v2")
        val tgt = content("d1", "Pub", draftTitle = "Draft v2")
        assertEquals(ContentTypeComparisonResultKind.IDENTICAL, compare(src, tgt))
    }

    @Test
    fun `draft-only source against published target is DIFFERENT (state differs)`() {
        val src = content("d1", "Body", isDraftOnly = true)
        val tgt = content("d1", "Body", isDraftOnly = false)
        assertEquals(ContentTypeComparisonResultKind.DIFFERENT, compare(src, tgt))
    }

    @Test
    fun `draft-only on both sides with same body is IDENTICAL`() {
        val src = content("d1", "Body", isDraftOnly = true)
        val tgt = content("d1", "Body", isDraftOnly = true)
        assertEquals(ContentTypeComparisonResultKind.IDENTICAL, compare(src, tgt))
    }
}
