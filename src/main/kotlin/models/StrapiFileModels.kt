package it.sebi.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.OffsetDateTime


val STRAPI_FILE_CONTENT_TYPE_NAME = "plugin::upload.file"

@Serializable
data class StrapiFolder(
    val id: Int,
    val documentId: String,
    val name: String,
    val pathId: Int,
    val path: String,
    val pathFull:String? = null
)

@Serializable
data class FolderResponse(
    val data: List<StrapiFolder>
)

@Serializable
data class ImageResponse(
    val results: List<StrapiImageMetadata>,
    val pagination: Pagination
)

@Serializable
data class StrapiImageMetadata(
    val id: Int,
    val documentId: String,
    val alternativeText:String? = null,
    val caption: String? = null,
    val name: String,
    val hash: String,
    val ext: String,
    val mime: String,
    val size: Double,
    val url: String,
    val previewUrl: String?,
    val provider: String,
    val folderPath: String,
    val locale: String?,
    @Contextual val updatedAt: OffsetDateTime,
)

@Serializable
data class StrapiImage(
    val metadata: StrapiImageMetadata,
    val rawData: JsonObject
)

fun StrapiImage.downloadUrl(strapiBaseUrl: String): String = if(metadata.provider=="local") {
    strapiBaseUrl.trimEnd('/') + this.metadata.url
} else{
    this.metadata.url
}


data class StrapiFileUploadResponse(
    val id:Int,
    val documentId: String
)



@Serializable
data class Pagination(
    val page: Int,
    val pageSize: Int,
    val pageCount: Int,
    val total: Int
)