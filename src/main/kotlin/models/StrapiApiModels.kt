package it.sebi.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime


@Serializable
data class StrapiContentType(
    val uid: String,
    val plugin: String? = null,
    val apiID: String,
    val schema: Schema
)

@Serializable
data class StrapiComponent(
    val uid: String,
    val category: String,
    val apiId: String,
    val schema: ComponentSchema
)

@Serializable
data class ContentTypeResponse(
    val data: List<StrapiContentType>,
)

@Serializable
data class ComponentResponse(
    val data: List<StrapiComponent>,
)

@Serializable
data class StrapiContentMetadata(
    val id: Int?,
    val documentId: String,
    val uniqueKey: String,
    val locale: String?,
    // Stable, cross-instance identity (Phase 1). Null when not yet assigned/reconciled.
    val syncId: String? = null,
    /**
     * Draft & Publish state (Strapi v5). When true, the document has NO published row — the primary
     * body (rawData/cleanData) holds the DRAFT version (draft-only / explicitly unpublished). When
     * false, the primary body is the published version (the normal case, and all non-D&P types).
     */
    val isDraftOnly: Boolean = false
)

@Serializable
data class StrapiLinkRef(
    val field: String,
    val sourceId: Int,
    val targetTable: String,
    val targetId: Int?,
    val order: Double? = null,
    val id: Int? = null,
    val lnkTable: String? = null,
    val isInverse: Boolean = false
)

@Serializable
data class StrapiContent(
    val metadata: StrapiContentMetadata,
    val rawData: JsonObject,
    val cleanData: JsonObject,
    val links: List<StrapiLinkRef> = emptyList(),
    /**
     * Divergent draft overlay (Strapi v5 "modified" state): present ONLY when the document is
     * published AND its working draft differs from the published version. Null in every other case
     * (clean published, draft-only, or non-D&P types) so the common path is unchanged and collapses.
     */
    val draft: StrapiDraftChannel? = null,
) {
    /** The published body for comparison: null when the document is draft-only (no published row). */
    fun publishedClean(): JsonObject? = if (metadata.isDraftOnly) null else cleanData

    /** The working/draft body for comparison: the divergent draft if present, else the primary body. */
    fun draftClean(): JsonObject = draft?.cleanData ?: cleanData
}

/**
 * The draft channel of a document when it diverges from the published version. Carries its own
 * raw/clean bodies and links because the draft entity row has independent components/relations.
 */
@Serializable
data class StrapiDraftChannel(
    val rawData: JsonObject,
    val cleanData: JsonObject,
    val links: List<StrapiLinkRef> = emptyList(),
)


/**
 * Response from Strapi API for login
 */
@Serializable
data class StrapiLoginResponse(
    val data: StrapiLoginData
)

/**
 * Login data from Strapi API
 */
@Serializable
data class StrapiLoginData(
    val token: String,
    val user: StrapiUser
)

/**
 * Represents a Strapi user
 */
@Serializable
data class StrapiUser(
    val id: Int,
    val documentId: String,
    val firstname: String,
    val lastname: String,
    val username: String?,
    val email: String,
    val isActive: Boolean,
    val blocked: Boolean,
    @SerialName("preferedLanguage") val preferredLanguage: String?,
    val createdAt: String,
    val updatedAt: String,
    val publishedAt: String,
    val locale: String?
)
