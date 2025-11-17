package it.sebi.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * Data class representing a merge request document mapping
 */
data class MergeRequestDocumentMapping(
    val id: Int = 0,
    val sourceStrapiInstanceId: Int,
    val targetStrapiInstanceId: Int,
    val contentType: String,
    val sourceId: Int? = null,
    val sourceDocumentId: String? = null,
    val sourceLastUpdateDate: OffsetDateTime? = null,
    val sourceDocumentMD5: String? = null,
    val targetId: Int? = null,
    val targetDocumentId: String? = null,
    val targetLastUpdateDate: OffsetDateTime? = null,
    val targetDocumentMD5: String? = null,
    @Contextual val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val updatedAt: OffsetDateTime = OffsetDateTime.now()
)




/**
 * Data class representing a merge request
 */
@Serializable
data class MergeRequest(
    val id: Int = 0,
    val name: String,
    val description: String,
    val sourceInstanceId: Int,
    val targetInstanceId: Int,
    val status: MergeRequestStatus = MergeRequestStatus.CREATED,
    @Contextual val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val updatedAt: OffsetDateTime = OffsetDateTime.now()
)

/**
 * Data transfer object for creating/updating merge requests
 */
@Serializable
data class MergeRequestDTO(
    val id: Int? = null,
    val name: String,
    val description: String? = null,
    val sourceInstanceId: Int,
    val targetInstanceId: Int,
    val status: MergeRequestStatus? = null
)

/**
 * Data transfer object for merge request with instance details
 */
@Serializable
data class MergeRequestWithInstancesDTO(
    val id: Int,
    val name: String,
    val description: String,
    val sourceInstance: StrapiInstance,
    val targetInstance: StrapiInstance,
    val status: MergeRequestStatus,
    @Contextual val createdAt: OffsetDateTime,
    @Contextual val updatedAt: OffsetDateTime
)


