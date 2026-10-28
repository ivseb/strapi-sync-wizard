package it.sebi.service.merge

import it.sebi.models.Attribute
import it.sebi.models.ContentTypeComparisonResultKind
import it.sebi.models.ContentTypeComparisonResultMapWithRelationships
import it.sebi.models.ContentTypeComparisonResultWithRelationships
import it.sebi.models.DbColumnMeta
import it.sebi.models.DbForeignKey
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

    // section_card_assistances owns linkBottomServiceTag through this join table (first FK = owner).
    private val ownerLnk = DbTable(
        name = "section_card_assistances_link_bottom_service_tag_lnk",
        foreignKeys = listOf(
            DbForeignKey(name = "fk", columns = listOf("section_card_assistance_id"), referencedTable = "section_card_assistances"),
            DbForeignKey(name = "ifk", columns = listOf("cta_service_tag_id"), referencedTable = "cta_service_tags"),
        ),
    )
    private val page = table(
        "pages", "api::page.page", StrapiContentTypeKind.SingleType,
        col("title", "string"), col("hero", "media"),
        col("ownedTags", "relation", target = tagUid),
        col("inverseTags", "relation", target = tagUid), // mappedBy side: no join table owned by pages
        col("createdBy", "relation", target = "admin::user"),
    )
    private val pageLnk = DbTable(
        name = "pages_owned_tags_lnk",
        foreignKeys = listOf(
            DbForeignKey(name = "fk", columns = listOf("page_id"), referencedTable = "pages"),
            DbForeignKey(name = "ifk", columns = listOf("cta_service_tag_id"), referencedTable = "cta_service_tags"),
        ),
    )
    private val fullSchema = DbSchema(listOf(tags, owner, card, ownerLnk, page, pageLnk))

    private fun write(schemaTable: DbTable, raw: JsonObject, links: List<StrapiLinkRef>) = ContentJsonUtils.processJsonElementNew(
        comparison, raw, schemaTable, fullSchema, fullSchema.tables.associateBy { it.name }, links,
    ).jsonObject

    @Test
    fun `references the source leaves empty are written as empty`() {
        val out = write(page, buildJsonObject { put("id", 1); put("title", "T") }, emptyList())
        val empty = buildJsonObject { put("set", buildJsonArray { }) }
        assertEquals(empty, out["ownedTags"], "owned relation must be cleared, got $out")
        assertEquals(empty, out["hero"], "media must be cleared, got $out")
        // Never clear what the reader can't see: inverse side, technical relations.
        assertFalse(out.containsKey("inverseTags"))
        assertFalse(out.containsKey("createdBy"))
    }

    @Test
    fun `a relation with links is not cleared even when they don't resolve`() {
        val unresolvable = StrapiLinkRef(field = "linkBottomServiceTag", sourceId = 10, targetTable = "cta_service_tags", targetId = 999)
        val out = write(owner, buildJsonObject { put("id", 10); put("titleMain", "T") }, listOf(unresolvable))
        assertFalse(out.containsKey("linkBottomServiceTag"), "got $out")
    }

    @Test
    fun `components get no clearing writes`() {
        val raw = buildJsonObject {
            put("id", 10); put("titleMain", "T")
            put("contextCta", buildJsonArray { add(buildJsonObject { put("id", 5); put("title", "Card") }) })
        }
        val out = write(owner, raw, emptyList())
        assertEquals(buildJsonObject { put("set", buildJsonArray { }) }, out["linkBottomServiceTag"])
        assertFalse(out["contextCta"]!!.jsonArray[0].jsonObject.containsKey("serviceTags"))
    }

    @Test
    fun `a link to a source draft row resolves by its documentId, not by its numeric id`() {
        // Draft row 300 of tag src-tag: its id is not in the comparison (published rows only) and, as a
        // TARGET id, would collide with an unrelated target entry.
        val cmp = ContentTypeComparisonResultMapWithRelationships(collectionTypes = mapOf("cta_service_tags" to listOf(
            comparison.collectionTypes.getValue("cta_service_tags").first(),
            ContentTypeComparisonResultWithRelationships(
                id = "src-other", tableName = "cta_service_tags", contentType = tagUid,
                sourceContent = content(500, "src-other"), targetContent = content(300, "tgt-other"),
                compareState = ContentTypeComparisonResultKind.IDENTICAL, kind = StrapiContentTypeKind.CollectionType,
            ),
        )))
        val draftLink = StrapiLinkRef(field = "linkBottomServiceTag", sourceId = 10, targetTable = "cta_service_tags", targetId = 300,
            targetDocumentId = "src-tag")
        val out = ContentJsonUtils.processJsonElementNew(
            cmp, buildJsonObject { put("id", 10) }, owner, fullSchema, fullSchema.tables.associateBy { it.name }, listOf(draftLink),
        ).jsonObject
        assertEquals(listOf("tgt-tag"), out["linkBottomServiceTag"]!!.jsonObject["set"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `clearing an attribute the target lacks is dropped without a warning`() {
        val payload = buildJsonObject { put("gone", buildJsonObject { put("set", buildJsonArray { }) }); put("title", "x") }
        val dropped = mutableListOf<String>()
        val out = filterByTargetSchema(mapOf("title" to Attribute(type = "string")), emptyMap(), payload, "t", dropped)
        assertFalse(out.containsKey("gone"))
        assertTrue(dropped.isEmpty(), "got $dropped")
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
