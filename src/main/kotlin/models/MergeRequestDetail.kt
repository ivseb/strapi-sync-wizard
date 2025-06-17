package it.sebi.models

import kotlinx.serialization.Serializable

@Serializable
data class MergeRequestDetail(
    val mergeRequest: MergeRequestWithInstancesDTO,
    val mergeRequestData:MergeRequestData?
)