package it.sebi.service.merge

import it.sebi.models.Attribute
import it.sebi.models.ContentTypeComparisonResultKind
import it.sebi.models.ContentTypeComparisonResultMapWithRelationships
import it.sebi.models.ContentTypeComparisonResultWithRelationships
import it.sebi.models.DbColumnMeta
import it.sebi.models.DbSchema
import it.sebi.models.DbTable
import it.sebi.models.DbTableMetadata
import it.sebi.models.StrapiContent
import it.sebi.models.StrapiContentMetadata
import it.sebi.models.StrapiContentTypeKind
import it.sebi.models.StrapiLinkRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Write path of relations whose attribute name differs from the target type (serviceTags,
 * linkBottomServiceTag -> api::cta-service-tag): the payload must carry the ATTRIBUTE key with the
 * TARGET documentIds, top-level and inside a repeatable component.
 */
class RelationPayloadTest {

    private val tagUid = "api::cta-service-tag.cta-service-tag"

    private fun col(name: String, type: String, component: String? = null, repeatable: Boolean = false, target: String? = null) =
        DbColumnMeta(name = name, required = false, type = type, unique = false, repeatable = repeatable, component = component, target = target)

    private fun table(name: String, uid: String, kind: StrapiContentTypeKind, vararg columns: DbColumnMeta) = DbTable(
        name = name,
        metadata = DbTableMetadata(
            apiUid = uid, collectionType = kind, collectionName = name, singularName = name, pluralName = name,
            displayName = name, draftAndPublish = true, localized = false, contentManagerVisible = true,
            contentTypeBuilderVisible = true, columns = columns.toList(),
        ),
    )

    private val tags = table("cta_service_tags", tagUid, StrapiContentTypeKind.CollectionType, col("name", "string"))
    private val owner = table(
        "section_card_assistances", "api::section-card-assistance.section-card-assistance", StrapiContentTypeKind.CollectionType,
        col("titleMain", "string"),
        col("contextCta", "component", component = "card.card-assistance", repeatable = true),
        col("linkBottomServiceTag", "relation", target = tagUid),
    )
    private val card = table(
        "components_card_card_assistances", "card.card-assistance", StrapiContentTypeKind.Component,
        col("title", "string"), col("serviceTags", "relation", target = tagUid),
    )
    private val schema = DbSchema(listOf(tags, owner, card))

    private fun content(id: Int, doc: String) = StrapiContent(
        metadata = StrapiContentMetadata(id = id, documentId = doc, uniqueKey = doc, locale = null),
        rawData = JsonObject(emptyMap()), cleanData = JsonObject(emptyMap()),
    )

    // Source tag row 171 (doc src-tag) is paired with target doc tgt-tag.
    private val comparison = ContentTypeComparisonResultMapWithRelationships(
        collectionTypes = mapOf(
            "cta_service_tags" to listOf(
                ContentTypeComparisonResultWithRelationships(
                    id = "src-tag", tableName = "cta_service_tags", contentType = tagUid,
                    sourceContent = content(171, "src-tag"), targetContent = content(42, "tgt-tag"),
                    compareState = ContentTypeComparisonResultKind.IDENTICAL, kind = StrapiContentTypeKind.CollectionType,
                )
            )
        )
    )

    @Test
    fun `relations named differently from their target are written under the attribute name`() {
        val raw = buildJsonObject {
            put("id", 10)
            put("titleMain", "T")
            put("contextCta", buildJsonArray { add(buildJsonObject { put("id", 5); put("title", "Card") }) })
        }
        val links = listOf(
            StrapiLinkRef(field = "contextCta.serviceTags", sourceId = 5, targetTable = "cta_service_tags", targetId = 171),
            StrapiLinkRef(field = "linkBottomServiceTag", sourceId = 10, targetTable = "cta_service_tags", targetId = 171),
        )

        val out = ContentJsonUtils.processJsonElementNew(
            comparison, raw, owner, schema, schema.tables.associateBy { it.name }, links,
        ).jsonObject

        fun setOf(o: JsonObject, key: String) = o[key]!!.jsonObject["set"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("tgt-tag"), setOf(out, "linkBottomServiceTag"))
        val item = out["contextCta"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("tgt-tag"), setOf(item, "serviceTags"))
        assertFalse(out.containsKey("ctaServiceTag"))
        assertFalse(item.containsKey("ctaServiceTag"))

        // ...and the target-schema filter keeps them (it dropped the old invented key).
        val targetAttrs = mapOf(
            "titleMain" to Attribute(type = "string"),
            "contextCta" to Attribute(type = "component", component = "card.card-assistance", repeatable = true),
            "linkBottomServiceTag" to Attribute(type = "relation", target = tagUid),
        )
        val componentAttrs = mapOf("card.card-assistance" to mapOf(
            "title" to Attribute(type = "string"), "serviceTags" to Attribute(type = "relation", target = tagUid),
        ))
        val dropped = mutableListOf<String>()
        val filtered = filterByTargetSchema(targetAttrs, componentAttrs, out, "section_card_assistances", dropped)
        assertTrue(dropped.isEmpty(), "nothing should be dropped, got $dropped")
        assertEquals(listOf("tgt-tag"), setOf(filtered["contextCta"]!!.jsonArray[0].jsonObject, "serviceTags"))
    }

    @Test
    fun `a dropped relation write is reported as a relation`() {
        val payload = buildJsonObject {
            put("ctaServiceTag", buildJsonObject { put("set", buildJsonArray { add(JsonPrimitive("x")) }) })
            put("note", "scalar")
        }
        val dropped = mutableListOf<String>()
        filterByTargetSchema(mapOf("titleMain" to Attribute(type = "string")), emptyMap(), payload, "t", dropped)
        assertTrue("t.ctaServiceTag$RELATION_SUFFIX" in dropped, "got $dropped")
        assertTrue("t.note" in dropped, "got $dropped")
        assertTrue(isRelationPayload(buildJsonObject { put("connect", buildJsonArray { }) }))
        assertFalse(isRelationPayload(buildJsonObject { put("set", buildJsonArray { }); put("title", "x") }))
    }
}
