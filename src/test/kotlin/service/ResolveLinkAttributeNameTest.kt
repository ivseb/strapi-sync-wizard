package it.sebi.service

import it.sebi.models.DbColumnMeta
import it.sebi.models.DbSchema
import it.sebi.models.DbTable
import it.sebi.models.DbTableMetadata
import it.sebi.models.StrapiContentTypeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression tests for [resolveLinkAttributeName]: a `*_lnk` join table must map to the relation's
 * ATTRIBUTE name, not to its target FK column. Deriving it from `cta_service_tag_id` produced
 * `ctaServiceTag` for `serviceTags` / `linkBottomServiceTag`, which the target schema dropped on write —
 * the relations were silently lost while the merge reported success.
 */
class ResolveLinkAttributeNameTest {

    private val tagUid = "api::cta-service-tag.cta-service-tag"

    private fun relation(name: String, target: String?) =
        DbColumnMeta(name = name, required = false, type = "relation", unique = false, repeatable = false, target = target)

    private fun scalar(name: String) =
        DbColumnMeta(name = name, required = false, type = "string", unique = false, repeatable = false)

    private fun table(name: String, uid: String, kind: StrapiContentTypeKind, vararg columns: DbColumnMeta) = DbTable(
        name = name,
        metadata = DbTableMetadata(
            apiUid = uid, collectionType = kind, collectionName = name, singularName = name, pluralName = name,
            displayName = name, draftAndPublish = false, localized = false, contentManagerVisible = true,
            contentTypeBuilderVisible = true, columns = columns.toList(),
        ),
    )

    private val schema = DbSchema(
        listOf(
            table("cta_service_tags", tagUid, StrapiContentTypeKind.CollectionType, scalar("name")),
            table(
                "section_card_assistances", "api::section-card-assistance.section-card-assistance",
                StrapiContentTypeKind.CollectionType, scalar("titleMain"), relation("linkBottomServiceTag", tagUid),
            ),
            table(
                "components_health_section_card_health_sections", "health-section.card-health-section",
                StrapiContentTypeKind.Component, scalar("title"), relation("serviceTags", tagUid),
            ),
            table(
                "components_card_card_assistances", "card.card-assistance",
                StrapiContentTypeKind.Component, relation("serviceTags", tagUid),
            ),
            // Two relations to the same target: only the table-name suffix can tell them apart.
            table(
                "components_multi_tag_owners", "multi.tag-owner",
                StrapiContentTypeKind.Component, relation("tags", tagUid), relation("serviceTags", tagUid),
            ),
            // Metadata cached before `target` was kept on DbColumnMeta.
            table("legacy_owners", "api::legacy.legacy", StrapiContentTypeKind.CollectionType, relation("primaryTag", null)),
            table(
                "open_motor_claims", "api::open-motor-claim.open-motor-claim",
                StrapiContentTypeKind.SingleType, relation("stepZero", "api::step-zero.step-zero"),
            ),
            table("step_zeros", "api::step-zero.step-zero", StrapiContentTypeKind.CollectionType, scalar("title")),
        )
    )

    @Test
    fun `top-level relation named differently from its target resolves from the table name`() {
        assertEquals(
            "linkBottomServiceTag",
            resolveLinkAttributeName("section_card_assistances", "section_card_assistances_link_bottom_service_tag_lnk", "cta_service_tags", schema),
        )
    }

    @Test
    fun `component relation resolves on an unshortened join table`() {
        assertEquals(
            "serviceTags",
            resolveLinkAttributeName("components_card_card_assistances", "components_card_card_assistances_service_tags_lnk", "cta_service_tags", schema),
        )
    }

    @Test
    fun `component relation resolves on a hash-shortened join table`() {
        assertEquals(
            "serviceTags",
            resolveLinkAttributeName(
                "components_health_section_card_health_sections",
                "components_health_section_card_he117b2_service_tags_lnk",
                "cta_service_tags",
                schema,
            ),
        )
    }

    @Test
    fun `several relations to the same target are disambiguated by the table-name suffix`() {
        assertEquals("serviceTags", resolveLinkAttributeName("components_multi_tag_owners", "components_multi_tag_o1a2b3_service_tags_lnk", "cta_service_tags", schema))
        assertEquals("tags", resolveLinkAttributeName("components_multi_tag_owners", "components_multi_tag_o1a2b3_tags_lnk", "cta_service_tags", schema))
    }

    @Test
    fun `relation without a known target still resolves by suffix`() {
        assertEquals("primaryTag", resolveLinkAttributeName("legacy_owners", "legacy_owners_primary_tag_lnk", "cta_service_tags", schema))
        assertEquals("primaryTag", resolveLinkAttributeName("legacy_owners", "legacy_ow9f9f9_primary_tag_lnk", "cta_service_tags", schema))
    }

    @Test
    fun `names that already matched the target keep resolving to the attribute`() {
        assertEquals("stepZero", resolveLinkAttributeName("open_motor_claims", "open_motor_claims_step_zero_lnk", "step_zeros", schema))
    }

    @Test
    fun `unknown owner or no matching relation returns null`() {
        assertNull(resolveLinkAttributeName("not_a_table", "not_a_table_x_lnk", "cta_service_tags", schema))
        assertNull(resolveLinkAttributeName("cta_service_tags", "cta_service_tags_whatever_lnk", "step_zeros", schema))
    }

    @Test
    fun `strapiSnakeCase matches Strapi join-table naming`() {
        assertEquals("link_bottom_service_tag", strapiSnakeCase("linkBottomServiceTag"))
        assertEquals("service_tags", strapiSnakeCase("serviceTags"))
        assertEquals("health_convention_collections", strapiSnakeCase("health_convention_collections"))
    }
}
