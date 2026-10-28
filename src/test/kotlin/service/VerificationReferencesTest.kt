package it.sebi.service

import it.sebi.models.Attribute
import it.sebi.models.ResolvedRef
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Post-sync verification must check references too: `__links` is excluded from the field diff, so a
 * relation that was never written used to be certified as "identical after normalization".
 */
class VerificationReferencesTest {

    // Same logical tag on both sides: different per-instance documentId, same identity.
    private fun ref(doc: String, identity: String) = ResolvedRef(documentId = doc, label = identity, contentHash = identity)

    private val body = buildJsonObject { put("title", "Card") }

    private val typeAttrs = mapOf(
        "cardCollection" to Attribute(type = "component", component = "health-section.health-section-card", repeatable = true),
        "header_background" to Attribute(type = "media"),
    )
    private val componentAttrs = mapOf(
        "health-section.health-section-card" to mapOf("card" to Attribute(type = "component", component = "health-section.card-health-section", repeatable = true)),
        "health-section.card-health-section" to mapOf("serviceTags" to Attribute(type = "relation")),
    )
    private val hasPath: (String) -> Boolean = { VerificationDiff.targetHasPath(typeAttrs, componentAttrs, it) }

    @Test
    fun `same identities with different documentIds are not a difference`() {
        val src = mapOf("cardCollection[0].card[0].serviceTags" to listOf(ref("s1", "id:A"), ref("s2", "id:B")))
        val tgt = mapOf("cardCollection[0].card[0].serviceTags" to listOf(ref("t1", "id:A"), ref("t2", "id:B")))
        assertTrue(VerificationDiff.referenceDiffs(src, tgt).isEmpty())
        assertEquals(true to "identical after normalization", VerificationDiff.classifyWithReferences(body, body, src, tgt, hasPath))
    }

    @Test
    fun `same references in a different order are a mismatch`() {
        val src = mapOf("cardCollection[0].card[0].serviceTags" to listOf(ref("s1", "id:A"), ref("s2", "id:B")))
        val tgt = mapOf("cardCollection[0].card[0].serviceTags" to listOf(ref("t2", "id:B"), ref("t1", "id:A")))
        assertEquals(listOf("cardCollection[0].card[0].serviceTags"), VerificationDiff.referenceDiffs(src, tgt))
    }

    @Test
    fun `relation missing on target is a mismatch when the target schema declares it`() {
        val src = mapOf("cardCollection[0].card[1].serviceTags" to listOf(ref("s1", "id:A")))
        val (ok, reason) = VerificationDiff.classifyWithReferences(body, body, src, emptyMap(), hasPath)
        assertFalse(ok)
        assertTrue(reason.startsWith("reference mismatch: cardCollection[0].card[1].serviceTags"), reason)
    }

    @Test
    fun `reference moved to another component item is a mismatch`() {
        val src = mapOf(
            "cardCollection[0].card[0].serviceTags" to listOf(ref("s1", "id:A")),
            "cardCollection[0].card[1].serviceTags" to listOf(ref("s2", "id:B")),
        )
        val tgt = mapOf(
            "cardCollection[0].card[0].serviceTags" to listOf(ref("t2", "id:B")),
            "cardCollection[0].card[1].serviceTags" to listOf(ref("t1", "id:A")),
        )
        assertEquals(
            listOf("cardCollection[0].card[0].serviceTags", "cardCollection[0].card[1].serviceTags"),
            VerificationDiff.referenceDiffs(src, tgt),
        )
        assertFalse(VerificationDiff.classifyWithReferences(body, body, src, tgt, hasPath).first)
    }

    @Test
    fun `reference the target schema cannot store is a schema gap`() {
        val src = mapOf("cardCollection[0].card[0].newOnlyInSource" to listOf(ref("s1", "id:A")))
        val (ok, reason) = VerificationDiff.classifyWithReferences(body, body, src, emptyMap(), hasPath)
        assertTrue(ok)
        assertTrue(reason.contains("schema gap"), reason)
    }

    @Test
    fun `targetHasPath follows components and ignores positions`() {
        assertTrue(VerificationDiff.targetHasPath(typeAttrs, componentAttrs, "cardCollection[3].card[0].serviceTags"))
        assertTrue(VerificationDiff.targetHasPath(typeAttrs, componentAttrs, "header_background"))
        assertFalse(VerificationDiff.targetHasPath(typeAttrs, componentAttrs, "cardCollection[0].card[0].ctaServiceTag"))
        assertFalse(VerificationDiff.targetHasPath(typeAttrs, componentAttrs, "unknownTop"))
        // Unknown target type / component schema: conservative (treated as present -> mismatch).
        assertTrue(VerificationDiff.targetHasPath(null, componentAttrs, "anything"))
        assertTrue(VerificationDiff.targetHasPath(mapOf("c" to Attribute(type = "component", component = "x.missing")), emptyMap(), "c[0].rel"))
    }
}
