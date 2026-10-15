package it.sebi.service

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import it.sebi.models.Attribute
import it.sebi.models.ResolvedRef

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

    /** Cross-instance identity of a reference (same key the UI diff uses): content/sync identity, never the per-instance documentId when an identity exists. */
    private fun refKey(r: ResolvedRef): String = r.contentHash ?: r.syncId?.let { "id:$it" } ?: "doc:${r.documentId}"

    /**
     * Reference paths (relation/media, positional — "cardCollection[2].card[0].serviceTags") whose
     * referenced identities differ between source and target, order-insensitive. `__links` is excluded
     * from [classify] because its raw documentIds are per-instance; this compares their identities.
     */
    fun referenceDiffs(srcRefs: Map<String, List<ResolvedRef>>, tgtRefs: Map<String, List<ResolvedRef>>): List<String> =
        (srcRefs.keys + tgtRefs.keys).filter { path ->
            srcRefs[path].orEmpty().map(::refKey).sorted() != tgtRefs[path].orEmpty().map(::refKey).sorted()
        }.sorted()

    /**
     * Whether the TARGET schema declares the attribute at a reference [path] (positional indexes are
     * ignored; component attributes are followed). Unknown component schemas and dynamic zones answer
     * true — conservative: a reference we can't prove unsupported is a real mismatch, not a schema gap.
     */
    fun targetHasPath(rootAttrs: Map<String, Attribute>?, componentAttrs: Map<String, Map<String, Attribute>>, path: String): Boolean {
        var attrs = rootAttrs ?: return true
        val segments = path.replace(Regex("\\[\\d+]"), "").split('.').filter { it.isNotEmpty() }
        segments.forEachIndexed { i, seg ->
            val norm = seg.replace("_", "").lowercase()
            val attr = attrs[seg] ?: attrs.entries.firstOrNull { it.key.replace("_", "").lowercase() == norm }?.value
                ?: return false
            if (i == segments.lastIndex) return true
            attrs = when (attr.type) {
                "component" -> attr.component?.let { componentAttrs[it] } ?: return true
                else -> return true // dynamic zone (per-item component type) or unexpected nesting
            }
        }
        return true
    }

    /**
     * [classify] plus references: a reference that differs on a path the target schema DOES declare is
     * a real mismatch (the relation was not written / not preserved); one the target can't store is a
     * schema gap like any other source-only field.
     */
    fun classifyWithReferences(
        src: JsonObject?, tgt: JsonObject?,
        srcRefs: Map<String, List<ResolvedRef>>, tgtRefs: Map<String, List<ResolvedRef>>,
        targetHasPath: (String) -> Boolean,
    ): Pair<Boolean, String> {
        val refDiffs = referenceDiffs(srcRefs, tgtRefs)
        val (missingRefs, gapRefs) = refDiffs.partition(targetHasPath)
        val (fieldsOk, fieldsReason) = classify(src, tgt)
        if (missingRefs.isNotEmpty())
            return false to "reference mismatch: ${missingRefs.take(5).joinToString()}" +
                (if (!fieldsOk) "; $fieldsReason" else "")
        if (gapRefs.isEmpty()) return fieldsOk to fieldsReason
        val gap = "${gapRefs.size} source-only reference(s) not present on target (schema gap): ${gapRefs.take(5).joinToString()}"
        return if (fieldsOk) true to (if (fieldsReason == "identical after normalization") gap else "$fieldsReason; $gap")
        else false to fieldsReason
    }
}
