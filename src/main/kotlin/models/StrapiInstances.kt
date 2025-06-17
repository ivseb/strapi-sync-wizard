package it.sebi.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class StrapiInstance(
    val id: Int = 0,
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val apiKey: String,
    @Contextual val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val updatedAt: OffsetDateTime = OffsetDateTime.now()
)

/**
 * Secure version of StrapiInstance that excludes sensitive fields (password and apiKey)
 */
@Serializable
data class StrapiInstanceSecure(
    val id: Int = 0,
    val name: String,
    val url: String,
    val username: String,
    @Contextual val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val updatedAt: OffsetDateTime = OffsetDateTime.now()
)

@Serializable
data class StrapiInstanceDTO(
    val id: Int? = null,
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val apiKey: String
)
