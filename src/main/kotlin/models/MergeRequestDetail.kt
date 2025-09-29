package it.sebi.models

import kotlinx.serialization.Serializable

@Serializable
data class MergeRequestDetail(
    val mergeRequest: MergeRequestWithInstancesDTO,
    val isCompatible: Boolean? = null,
    val mergeRequestData:MergeRequestData?
)