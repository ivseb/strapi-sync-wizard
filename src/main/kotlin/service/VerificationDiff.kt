package it.sebi.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure content-diff classification used by post-sync verification. Extracted (and unit-tested) so the
 * "is this a real mismatch or just a schema gap" decision can't silently regress.
 *
 * A merge cannot write fields that don't exist in the TARGET schema (they're auto-dropped on apply),
 * so an item that differs ONLY in such source-only fields is NOT a failure — it's the best the target
 * can represent. We classify those as `schema_gap` (consistent) and genuine value differences as
 * `mismatch` (inconsistent).
 */
object VerificationDiff {

    private val techFields = setOf(
        "id", "documentId", "document_id", "__links", "__order", "__component", "localizations",
        "createdAt", "updatedAt", "publishedAt", "created_at", "updated_at", "published_at",
        "createdBy", "updatedBy", "created_by_id", "updated_by_id", "locale"
    )

    internal fun flattenLeaves(el: JsonElement?, path: String, out: MutableMap<String, String?>) {
        when (el) {
            null, is JsonNull -> { if (path.isNotEmpty()) out[path] = null }
            is JsonObject -> el.forEach { (k, v) -> if (k !in techFields) flattenLeaves(v, if (path.isEmpty()) k else "$path.$k", out) }
            is JsonArray -> el.forEachIndexed { i, v -> flattenLeaves(v, "$path[$i]", out) }
            is JsonPrimitive -> out[path] = if (el.isString) el.content else el.toString()
        }
    }

    /**
     * @return (consistentDespiteDiff, reason). consistentDespiteDiff is true when the entries are
     * equal on all comparable leaves, or differ ONLY in leaves absent/null on the target (schema gap).
     */
    fun classify(src: JsonObject?, tgt: JsonObject?): Pair<Boolean, String> {
        if (src == null) return false to "no source content to compare"
        val s = HashMap<String, String?>(); flattenLeaves(src, "", s)
        val t = HashMap<String, String?>(); flattenLeaves(tgt, "", t)
        val diffs = (s.keys + t.keys).filter { s[it] != t[it] }
        if (diffs.isEmpty()) return true to "identical after normalization"
        val targetMissingOnly = diffs.all { t[it] == null }
        return if (targetMissingOnly)
            true to "${diffs.size} source-only field(s) not present on target (schema gap): ${diffs.sorted().take(5).joinToString()}"
        else
            false to "value mismatch: ${diffs.filter { t[it] != null }.sorted().take(5).joinToString()}"
    }
}
