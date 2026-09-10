package it.sebi.service.merge

import it.sebi.models.Attribute
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression tests for the recursive target-schema payload filter (ValidationError: Invalid key <field> at <component>). */
class FilterByTargetSchemaTest {

    private val cardComponent = mapOf(
        "title" to Attribute(type = "string"),
        "link" to Attribute(type = "string"),
    )
    private val typeAttrs = mapOf(
        "titleMain" to Attribute(type = "string"),
        "contextCta" to Attribute(type = "component", component = "card.card-assistance", repeatable = true),
        "zone" to Attribute(type = "dynamiczone"),
    )
    private val componentAttrs = mapOf("card.card-assistance" to cardComponent)

    @Test
    fun `drops source-only field nested inside a repeatable component`() {
        val payload = buildJsonObject {
            put("titleMain", "T")
            put("contextCta", buildJsonArray {
                add(buildJsonObject {
                    put("title", "Card")
                    put("link", "/x")
                    put("cta_service_tag", "TAG") // source-only: target component doesn't declare it
                })
            })
        }
        val dropped = mutableListOf<String>()
        val out = filterByTargetSchema(typeAttrs, componentAttrs, payload, "section_card_assistances", dropped)
        val item = out["contextCta"]!!.jsonArray[0].jsonObject
        assertFalse(item.containsKey("cta_service_tag"))
        assertEquals("Card", item["title"]!!.toString().trim('"'))
        assertTrue(dropped.any { it.endsWith("contextCta.cta_service_tag") })
    }

    @Test
    fun `drops unknown top-level field and keeps known ones`() {
        val payload = buildJsonObject { put("titleMain", "T"); put("newField", "x") }
        val dropped = mutableListOf<String>()
        val out = filterByTargetSchema(typeAttrs, componentAttrs, payload, "t", dropped)
        assertFalse(out.containsKey("newField"))
        assertTrue(out.containsKey("titleMain"))
    }

    @Test
    fun `drops dynamic zone item whose component type is unknown to target, filters known ones`() {
        val payload = buildJsonObject {
            put("zone", buildJsonArray {
                add(buildJsonObject { put("__component", "card.card-assistance"); put("title", "A"); put("extra", 1) })
                add(buildJsonObject { put("__component", "new.only-in-source"); put("whatever", 2) })
            })
        }
        val dropped = mutableListOf<String>()
        val out = filterByTargetSchema(typeAttrs, componentAttrs, payload, "t", dropped)
        val zone = out["zone"]!!.jsonArray
        assertEquals(1, zone.size)
        val kept = zone[0].jsonObject
        assertEquals("\"card.card-assistance\"", kept["__component"]!!.toString())
        assertFalse(kept.containsKey("extra"))
        assertTrue(dropped.any { it.contains("[new.only-in-source]") })
    }

    @Test
    fun `matches attribute names ignoring case and separators`() {
        // Payload keys can arrive snake_case while the schema declares camelCase.
        val payload = buildJsonObject { put("title_main", "T") }
        val out = filterByTargetSchema(typeAttrs, componentAttrs, payload, "t", mutableListOf())
        assertTrue(out.containsKey("title_main"))
    }

    @Test
    fun `unknown collection passthrough - component schema unavailable keeps value unchanged`() {
        val attrs = mapOf("ctx" to Attribute(type = "component", component = "missing.comp"))
        val payload = buildJsonObject { put("ctx", buildJsonObject { put("anything", 1) }) }
        val out = filterByTargetSchema(attrs, emptyMap(), payload, "t", mutableListOf())
        assertEquals(payload, JsonObject(out))
    }
}
