package it.sebi.service

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Post-sync verification diff classification. Locks down the distinction between a real value
 * mismatch (inconsistent) and a schema gap (source-only fields the target can't store — consistent).
 */
class VerificationDiffTest {

    @Test
    fun `identical content is consistent`() {
        val a = buildJsonObject { put("title", "Hello"); put("n", 3) }
        val b = buildJsonObject { put("title", "Hello"); put("n", 3) }
        val (ok, reason) = VerificationDiff.classify(a, b)
        assertTrue(ok)
        assertEquals("identical after normalization", reason)
    }

    @Test
    fun `differs only in source-only field missing on target is schema-gap (consistent)`() {
        // target lacks dlp_action entirely (older schema) -> not a failure
        val src = buildJsonObject {
            put("title", "X")
            putArray("dlp_tracking") {
                add(buildJsonObject { put("dlp_action", "uuid-1"); put("dlp_service", "uuid-2") })
            }
        }
        val tgt = buildJsonObject {
            put("title", "X")
            putArray("dlp_tracking") { add(buildJsonObject { }) }
        }
        val (ok, reason) = VerificationDiff.classify(src, tgt)
        assertTrue(ok, "schema-gap should be consistent")
        assertTrue(reason.contains("schema gap"), "reason was: $reason")
    }

    @Test
    fun `real value mismatch is inconsistent`() {
        val src = buildJsonObject { put("title", "New value") }
        val tgt = buildJsonObject { put("title", "Old value") }
        val (ok, reason) = VerificationDiff.classify(src, tgt)
        assertFalse(ok)
        assertTrue(reason.startsWith("value mismatch"), "reason was: $reason")
    }

    @Test
    fun `technical fields are ignored`() {
        val src = buildJsonObject { put("title", "X"); put("updatedAt", "2026-01-01"); put("documentId", "aaa") }
        val tgt = buildJsonObject { put("title", "X"); put("updatedAt", "2020-09-09"); put("documentId", "bbb") }
        val (ok, _) = VerificationDiff.classify(src, tgt)
        assertTrue(ok, "differences only in technical/identity fields must not count")
    }

    @Test
    fun `null source is inconsistent`() {
        val (ok, _) = VerificationDiff.classify(null, buildJsonObject { put("title", "X") })
        assertFalse(ok)
    }

    @Test
    fun `mixed schema-gap and real mismatch is inconsistent`() {
        val src = buildJsonObject { put("title", "New"); put("onlyInSource", "v") }
        val tgt = buildJsonObject { put("title", "Old") } // title differs (real) + onlyInSource missing
        val (ok, reason) = VerificationDiff.classify(src, tgt)
        assertFalse(ok, "a real value diff alongside a schema gap is still a mismatch")
        assertTrue(reason.startsWith("value mismatch"), "reason was: $reason")
    }

    // helper: build a JSON array property
    private fun kotlinx.serialization.json.JsonObjectBuilder.putArray(key: String, build: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit) {
        put(key, buildJsonArray(build))
    }
}
