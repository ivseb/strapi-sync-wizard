package it.sebi.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the status of a merge request
 */
@Serializable
enum class MergeRequestStatus {
    @SerialName("CREATED")
    CREATED,        // Initial state, just created

    @SerialName("SCHEMA_CHECKED")
    SCHEMA_CHECKED, // Schema compatibility has been verified

    @SerialName("COMPARED")
    COMPARED,       // Content comparison has been performed

    @SerialName("MERGED_FILES")
    MERGED_FILES,   // Files have been merged

    @SerialName("MERGED_SINGLES")
    MERGED_SINGLES, // Single content types have been merged

    @SerialName("MERGED_COLLECTIONS")
    MERGED_COLLECTIONS, // Collection content types have been merged

    @SerialName("IN_PROGRESS")
    IN_PROGRESS,    // Merge has been started and is running

    @SerialName("COMPLETED")
    COMPLETED,      // Merge has been completed

    @SerialName("FAILED")
    FAILED          // Merge has failed
}

/**
 * Represents the direction of a merge request file
 */
@Serializable
enum class Direction {
    @SerialName("TO_CREATE")
    TO_CREATE,

    @SerialName("TO_UPDATE")
    TO_UPDATE,

    @SerialName("TO_DELETE")
    TO_DELETE
}