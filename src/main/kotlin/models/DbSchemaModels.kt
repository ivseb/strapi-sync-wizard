package it.sebi.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SchemaDbCompatibilityResult(
    val isCompatible: Boolean,
    val missingTablesInTarget: List<String> = emptyList(),
    val missingTablesInSource: List<String> = emptyList(),
    val tableDifferences: List<TableDifference> = emptyList(),
    // When compatible, include the parsed schema from one DB (typically the source)
    val extractedSchema: DbSchema? = null
)

@Serializable
data class TableDifference(
    val table: String,
    val missingColumnsInTarget: List<String> = emptyList(),
    val missingColumnsInSource: List<String> = emptyList(),
    val differentColumns: List<ColumnDifference> = emptyList(),
    val missingIndexesInTarget: List<String> = emptyList(),
    val missingIndexesInSource: List<String> = emptyList(),
    val differentIndexes: List<IndexDifference> = emptyList(),
    val missingForeignKeysInTarget: List<String> = emptyList(),
    val missingForeignKeysInSource: List<String> = emptyList(),
    val differentForeignKeys: List<ForeignKeyDifference> = emptyList()
)

@Serializable
data class ColumnDifference(
    val column: String,
    val sourceSignature: String,
    val targetSignature: String
)

@Serializable
data class IndexDifference(
    val index: String,
    val sourceSignature: String,
    val targetSignature: String
)

@Serializable
data class ForeignKeyDifference(
    val foreignKey: String,
    val sourceSignature: String,
    val targetSignature: String
)

// Full DB schema models (parsed from strapi_database_schema.schema JSON)
@Serializable
data class DbSchema(
    val tables: List<DbTable> = emptyList()
)
@Serializable
data class DbColumnMeta(
    val name: String,
    val required: Boolean,
    val type: String,
    val unique: Boolean,
    val repeatable: Boolean,
    val component: String? = null,
)

@Serializable
data class DbTableMetadata(
    val apiUid: String,
    val collectionType: StrapiContentTypeKind,
    val collectionName: String,
    val singularName: String,
    val pluralName: String,
    val displayName: String,
    val draftAndPublish: Boolean,
    val localized: Boolean,
    val contentManagerVisible: Boolean,
    val contentTypeBuilderVisible: Boolean,
    val columns: List<DbColumnMeta>,


    ) {
    val queryName = when (collectionType) {
        StrapiContentTypeKind.SingleType -> {
            singularName
        }

        StrapiContentTypeKind.CollectionType -> {
            pluralName

        }
        StrapiContentTypeKind.Files -> {
            "files"
        }
        StrapiContentTypeKind.Component -> {
            "components"
        }
    }

    val kebabCaseKind = when (collectionType) {
        StrapiContentTypeKind.SingleType -> {
            "single-types"
        }

        StrapiContentTypeKind.CollectionType -> {
            "collection-types"

        }
        StrapiContentTypeKind.Files -> {
            "files"
        }
        StrapiContentTypeKind.Component -> {
            "components"
        }
    }
}

@Serializable
data class DbTable(
    val name: String,
    val columns: List<DbColumn> = emptyList(),
    val indexes: List<DbIndex> = emptyList(),
    val foreignKeys: List<DbForeignKey> = emptyList(),
    val metadata: DbTableMetadata? = null,
)

@Serializable
data class DbColumn(
    val name: String,
    val type: String? = null,
    val args: JsonElement? = null,
    val defaultTo: JsonElement? = null,
    val notNullable: Boolean? = null,
    val unsigned: Boolean? = null
)

@Serializable
data class DbIndex(
    val name: String,
    val columns: List<String> = emptyList(),
    val type: String? = null
)

@Serializable
data class DbForeignKey(
    val name: String,
    val columns: List<String> = emptyList(),
    val referencedTable: String? = null,
    val referencedColumns: List<String> = emptyList(),
    val onDelete: String? = null
)
