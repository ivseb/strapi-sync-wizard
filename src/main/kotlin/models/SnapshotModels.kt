package it.sebi.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

@Serializable
data class SnapshotDTO(
    val id: Int,
    val mergeRequestId: Int,
    val snapshotSchemaName: String,
    @Contextual val createdAt: OffsetDateTime
)

@Serializable
data class SnapshotActivityDTO(
    val id: Int,
    val mergeRequestId: Int,
    val activityType: String,
    val status: String,
    val snapshotSchemaName: String?,
    val message: String?,
    @Contextual val createdAt: OffsetDateTime
)
