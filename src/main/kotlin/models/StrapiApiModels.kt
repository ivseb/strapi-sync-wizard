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
    val slug:String? = null,
)

@Serializable
data class StrapiContent(
    val metadata: StrapiContentMetadata,
    val rawData: JsonObject,
    val cleanData: JsonObject,
)

/**
 * Represents an entry element in Strapi
 */
@Serializable
data class EntryElement(val obj: StrapiContent, val hash: String)


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
