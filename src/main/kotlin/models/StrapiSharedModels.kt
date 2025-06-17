package it.sebi.models



import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement


@Serializable
data class Attribute(
    val type: String,
    val configurable: Boolean = false,
    val required: Boolean = false,
    val unique: Boolean = false,
    val private: Boolean = false,
    val searchable: Boolean = false,
    val default: JsonElement? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val enum: List<String>? = null,
    val targetField: String? = null,
    val relation: String? = null,
    val target: String? = null,
    val inversedBy: String? = null,
    val mappedBy: String? = null,
    val targetAttribute: String? = null,
    val component: String? = null,
    val repeatable: Boolean? = null
)


@Serializable
data class ContentTypeBuilderOptions(
    val visible: Boolean
)


@Serializable
data class ContentManagerOptions(
    val visible: Boolean
)

@Serializable
data class PluginOptions(
    val contentManager: ContentManagerOptions? = null,
    val contentTypeBuilder: ContentTypeBuilderOptions? = null
)

@Serializable
enum class StrapiContentTypeKind(val value: String) {
    @SerialName("singleType")
    SingleType("singleType"),

    @SerialName("collectionType")
    CollectionType("collectionType")
}




@Serializable
data class Schema(
    val draftAndPublish: Boolean,
    val displayName: String,
    val singularName: String,
    val pluralName: String,
    val description: String,
    val kind: StrapiContentTypeKind,
    val collectionName: String,
    val attributes: Map<String, Attribute>,
    val visible: Boolean,
    val restrictRelationsTo: List<String>? = null,
    val pluginOptions: PluginOptions? = null,
) {
    val queryName = when (kind) {
        StrapiContentTypeKind.SingleType -> {
            singularName
        }
        StrapiContentTypeKind.CollectionType -> {
            pluralName

        }
    }

    val kebabCaseKind =  when (kind) {
        StrapiContentTypeKind.SingleType -> {
            "single-types"
        }
        StrapiContentTypeKind.CollectionType -> {
            "collection-types"

        }
    }
}

@Serializable
data class ComponentSchema(
    val displayName: String,
    val description: String = "",
    val icon: String = "",
    val collectionName: String,
    val attributes: Map<String, Attribute>
)
