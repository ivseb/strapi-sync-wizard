package it.sebi.service

import it.sebi.client.client
import it.sebi.database.dbQuery
import it.sebi.models.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.slf4j.LoggerFactory
import java.sql.DriverManager

/**
 * DB-based schema compatibility check for Strapi instances.
 * Reads latest JSON from strapi_database_schema.schema in each instance DB and compares structures.
 */
suspend fun SyncService.checkSchemaCompatibilityDb(
    sourceInstance: StrapiInstance,
    targetInstance: StrapiInstance
): SchemaDbCompatibilityResult {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    logger.info("Starting DB schema compatibility check between '${sourceInstance.name}' and '${targetInstance.name}'")

    val sourceJson = fetchStrapiSchemaJsonFromDb(sourceInstance)
    val targetJson = fetchStrapiSchemaJsonFromDb(targetInstance)

    if (sourceJson == null || targetJson == null) {
        val msg = buildString {
            if (sourceJson == null) append("Source schema JSON missing; ")
            if (targetJson == null) append("Target schema JSON missing; ")
        }
        logger.warn("$msg Returning incompatible result.")
        return SchemaDbCompatibilityResult(isCompatible = false)
    }

    val sourceDef = parseStrapiDbSchema(sourceJson)
    val targetDef = parseStrapiDbSchema(targetJson)

    val sourceTables = sourceDef.keys
    val targetTables = targetDef.keys

    val missingInTarget = sourceTables.minus(targetTables).sorted()
    val missingInSource = targetTables.minus(sourceTables).sorted()

    val commonTables = sourceTables.intersect(targetTables)

    val tableDiffs = mutableListOf<TableDifference>()

    for (table in commonTables) {
        val s = sourceDef.getValue(table)
        val t = targetDef.getValue(table)

        val colMissingInTarget = s.columns.keys.minus(t.columns.keys).sorted()
        val colMissingInSource = t.columns.keys.minus(s.columns.keys).sorted()

        val commonCols = s.columns.keys.intersect(t.columns.keys)
        val differentCols = commonCols.mapNotNull { c ->
            val sv = s.columns.getValue(c)
            val tv = t.columns.getValue(c)
            if (sv != tv) ColumnDifference(c, sv, tv) else null
        }

        val idxMissingInTarget = s.indexes.keys.minus(t.indexes.keys).sorted()
        val idxMissingInSource = t.indexes.keys.minus(s.indexes.keys).sorted()

        val commonIdx = s.indexes.keys.intersect(t.indexes.keys)
        val differentIdx = commonIdx.mapNotNull { i ->
            val sv = s.indexes.getValue(i)
            val tv = t.indexes.getValue(i)
            if (sv != tv) IndexDifference(i, sv, tv) else null
        }

        val fkMissingInTarget = s.foreignKeys.keys.minus(t.foreignKeys.keys).sorted()
        val fkMissingInSource = t.foreignKeys.keys.minus(s.foreignKeys.keys).sorted()

        val commonFk = s.foreignKeys.keys.intersect(t.foreignKeys.keys)
        val differentFk = commonFk.mapNotNull { f ->
            val sv = s.foreignKeys.getValue(f)
            val tv = t.foreignKeys.getValue(f)
            if (sv != tv) ForeignKeyDifference(f, sv, tv) else null
        }

        if (colMissingInTarget.isNotEmpty() || colMissingInSource.isNotEmpty() ||
            differentCols.isNotEmpty() || idxMissingInTarget.isNotEmpty() || idxMissingInSource.isNotEmpty() ||
            differentIdx.isNotEmpty() || fkMissingInTarget.isNotEmpty() || fkMissingInSource.isNotEmpty() ||
            differentFk.isNotEmpty()
        ) {
            tableDiffs.add(
                TableDifference(
                    table = table,
                    missingColumnsInTarget = colMissingInTarget,
                    missingColumnsInSource = colMissingInSource,
                    differentColumns = differentCols,
                    missingIndexesInTarget = idxMissingInTarget,
                    missingIndexesInSource = idxMissingInSource,
                    differentIndexes = differentIdx,
                    missingForeignKeysInTarget = fkMissingInTarget,
                    missingForeignKeysInSource = fkMissingInSource,
                    differentForeignKeys = differentFk
                )
            )
        }
    }

    val isCompatible = missingInTarget.isEmpty() && missingInSource.isEmpty() && tableDiffs.isEmpty()

    val extracted: DbSchema? = if (isCompatible) {
        // Build enriched schema from source DB JSON and Strapi metadata
        val contentTypesMeta: Map<String, StrapiContentType> =
            sourceInstance.client().getContentTypes().associateBy { it.schema.collectionName }
        val componentMeta: Map<String, StrapiComponent> =
            sourceInstance.client().getComponentSchema().associateBy { it.schema.collectionName }
//        val metadataList = fetchStrapiMetadataJsonFromDb(sourceInstance)
//        val metaByCollection = metadataList.associateBy { it.collectionName }
        parseDbSchemaModel(sourceJson, contentTypesMeta, componentMeta)
    } else null

    val result = SchemaDbCompatibilityResult(
        isCompatible = isCompatible,
        missingTablesInTarget = missingInTarget,
        missingTablesInSource = missingInSource,
        tableDifferences = tableDiffs.sortedBy { it.table },
        extractedSchema = extracted
    )

    logger.info(
        "DB schema compatibility check completed: compatible=$isCompatible, missingInTarget=${missingInTarget.size}, missingInSource=${missingInSource.size}, diffs=${tableDiffs.size}"
    )
    return result
}

private data class TableDef(
    val columns: Map<String, String>,
    val indexes: Map<String, String>,
    val foreignKeys: Map<String, String>
)

private fun parseStrapiDbSchema(json: String): Map<String, TableDef> {
    val root = Json.parseToJsonElement(json).jsonObject
    val tables = root["tables"]?.jsonArray ?: return emptyMap()

    fun colSig(obj: JsonObject): String {
        fun v(name: String): String? = obj[name]?.let {
            if (it is JsonPrimitive && it.isString) it.content else it.toString()
        }
        return buildString {
            append("type=").append(v("type"))
            append("|args=").append(v("args"))
            append("|defaultTo=").append(v("defaultTo"))
            append("|notNullable=").append(v("notNullable"))
            append("|unsigned=").append(v("unsigned"))
        }
    }

    fun idxSig(obj: JsonObject): String {
        val cols = obj["columns"]?.jsonArray?.joinToString(",") { it.jsonPrimitive.contentOrNull ?: it.toString() }
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        return "columns=$cols|type=$type"
    }

    fun fkSig(obj: JsonObject): String {
        val cols = obj["columns"]?.jsonArray?.joinToString(",") { it.jsonPrimitive.contentOrNull ?: it.toString() }
        val refTable = obj["referencedTable"]?.jsonPrimitive?.contentOrNull
        val refCols =
            obj["referencedColumns"]?.jsonArray?.joinToString(",") { it.jsonPrimitive.contentOrNull ?: it.toString() }
        val onDelete = obj["onDelete"]?.jsonPrimitive?.contentOrNull
        return "columns=$cols|refTable=$refTable|refCols=$refCols|onDelete=$onDelete"
    }

    val map = mutableMapOf<String, TableDef>()
    for (t in tables) {
        val to = t.jsonObject
        val name = to["name"]?.jsonPrimitive?.content ?: continue
        if (name.startsWith("strapi_")) continue

        val colMap = mutableMapOf<String, String>()
        (to["columns"] as? JsonArray)?.forEach { c ->
            val co = c.jsonObject
            val cn = co["name"]?.jsonPrimitive?.content
            if (cn != null) colMap[cn] = colSig(co)
        }

        val idxMap = mutableMapOf<String, String>()
        (to["indexes"] as? JsonArray)?.forEach { i ->
            val io = i.jsonObject
            val iname = io["name"]?.jsonPrimitive?.content
            if (iname != null) idxMap[iname] = idxSig(io)
        }

        val fkMap = mutableMapOf<String, String>()
        (to["foreignKeys"] as? JsonArray)?.forEach { fk ->
            val fko = fk.jsonObject
            val fkName = fko["name"]?.jsonPrimitive?.content
            if (fkName != null) fkMap[fkName] = fkSig(fko)
        }

        map[name] = TableDef(columns = colMap, indexes = idxMap, foreignKeys = fkMap)
    }
    return map.filterNot { it.key.startsWith("strapi_") }
}

private fun parseDbSchemaModel(
    json: String,
    contentTypesMeta: Map<String, StrapiContentType>,
    componentMetas: Map<String, StrapiComponent>
): DbSchema {
    val root = Json.parseToJsonElement(json).jsonObject
    val tables = root["tables"]?.jsonArray ?: return DbSchema(emptyList())

    val parsed = mutableListOf<DbTable>()
    for (t in tables) {
        val to = t.jsonObject
        val name = to["name"]?.jsonPrimitive?.content ?: continue
        if (name.startsWith("strapi_")) continue

        val columns = mutableListOf<DbColumn>()
        (to["columns"] as? JsonArray)?.forEach { c ->
            val co = c.jsonObject
            val cn = co["name"]?.jsonPrimitive?.content ?: return@forEach
            columns.add(
                DbColumn(
                    name = cn,
                    type = co["type"]?.jsonPrimitive?.contentOrNull,
                    args = co["args"],
                    defaultTo = co["defaultTo"],
                    notNullable = co["notNullable"]?.jsonPrimitive?.booleanOrNull,
                    unsigned = co["unsigned"]?.jsonPrimitive?.booleanOrNull
                )
            )
        }

        val indexes = mutableListOf<DbIndex>()
        (to["indexes"] as? JsonArray)?.forEach { i ->
            val io = i.jsonObject
            val iname = io["name"]?.jsonPrimitive?.content ?: return@forEach
            val cols = io["columns"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            indexes.add(
                DbIndex(
                    name = iname,
                    columns = cols,
                    type = io["type"]?.jsonPrimitive?.contentOrNull
                )
            )
        }

        val foreignKeys = mutableListOf<DbForeignKey>()
        (to["foreignKeys"] as? JsonArray)?.forEach { fk ->
            val fko = fk.jsonObject
            val fkName = fko["name"]?.jsonPrimitive?.content ?: return@forEach
            val cols = fko["columns"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val refTable = fko["referencedTable"]?.jsonPrimitive?.contentOrNull
            val refCols =
                fko["referencedColumns"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val onDelete = fko["onDelete"]?.jsonPrimitive?.contentOrNull
            foreignKeys.add(
                DbForeignKey(
                    name = fkName,
                    columns = cols,
                    referencedTable = refTable,
                    referencedColumns = refCols,
                    onDelete = onDelete
                )
            )
        }

        val componentMetaData = componentMetas[name]?.let { cm ->

            val columns: List<DbColumnMeta> = cm.schema.attributes.map { (key, value) ->
                DbColumnMeta(
                    key,
                    value.required,
                    value.type,
                    value.unique,
                    value.repeatable ?: false,
                    value.component,
                )
            }
            DbTableMetadata(
                cm.uid,
                StrapiContentTypeKind.Component,
                cm.schema.collectionName,
                cm.apiId,
                cm.category,
                cm.category,
                false,
                false,
                false,
                false,
                columns


            )
        }

        val contentMetaData = contentTypesMeta[name]?.let { cm ->

            val columns: List<DbColumnMeta> = cm.schema.attributes.map { (key, value) ->
                DbColumnMeta(
                    key,
                    value.required,
                    value.type,
                    value.unique,
                    value.repeatable ?: false,
                    value.component,
                )
            }
            DbTableMetadata(
                cm.uid,
                cm.schema.kind,
                cm.schema.collectionName,
                cm.schema.singularName,
                cm.schema.pluralName,
                cm.schema.displayName,
                cm.schema.draftAndPublish,
                cm.schema.pluginOptions?.i18n?.localized ?: false,
                cm.schema.pluginOptions?.contentManager?.visible ?: false,
                cm.schema.pluginOptions?.contentTypeBuilder?.visible ?: false,
                columns


            )
        }



        parsed.add(
            DbTable(
                name = name,
                columns = columns,
                indexes = indexes,
                foreignKeys = foreignKeys,
                metadata = contentMetaData ?: componentMetaData
            )
        )
    }

    return DbSchema(parsed)
}

private fun fetchStrapiSchemaJsonFromDb(instance: StrapiInstance): String? {
    val logger = LoggerFactory.getLogger("SyncServiceDb")

    val host = instance.dbHost?.takeIf { it.isNotBlank() }
    val port = instance.dbPort ?: 5432
    val db = instance.dbName?.takeIf { it.isNotBlank() }
    val user = instance.dbUser?.takeIf { it.isNotBlank() }
    val pass = instance.dbPassword?.takeIf { it.isNotBlank() }
    val sslmode = instance.dbSslMode?.takeIf { it.isNotBlank() } ?: "prefer"

    if (host == null || db == null || user == null || pass == null) {
        logger.error("Missing DB connection data for instance '${instance.name}'. Host/DB/User/Password are required.")
        return null
    }

    val url = "jdbc:postgresql://$host:$port/$db?sslmode=$sslmode"

    val schema = (instance.dbSchema?.takeIf { it.isNotBlank() } ?: "public").replace("\"", "")

    return try {
        DriverManager.getConnection(url, user, pass).use { conn ->
            conn.createStatement().use { st ->
                // Set search path to ensure we read from the desired schema
                st.execute("SET search_path TO \"$schema\"")
            }
            conn.createStatement().use { st ->
                st.executeQuery("SELECT schema FROM strapi_database_schema ORDER BY id DESC LIMIT 1").use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }
    } catch (e: Exception) {
        logger.error("Error reading strapi_database_schema from '${instance.name}': ${e.message}", e)
        null
    }
}

private suspend fun fetchStrapiMetadataJsonFromDb(instance: StrapiInstance): List<DbTableMetadata> {
    val logger = LoggerFactory.getLogger("SyncServiceDb")

    return dbQuery(instance.database) {
        exec(
            """
             with tables_list as (select json_array_elements(c.schema -> 'tables') tab
                                  from strapi_database_schema c),
                  cols as (select tab ->> 'name' as tab_name, tab -> 'columns' as col
                           from tables_list),
                  cols_metadata as (select c.tab_name,
                                           c.col ->> 'name' as col_name,
                                           c.col            as col_metadata
                                    from cols c),
                  setting_metadata as (select CONCAT('components_', REPLACE(REPLACE(s.value::jsonb ->> 'uid', '-', '_'), '.', '_'),
                                                     's')                   AS candidate,
                                              s.value::jsonb ->> 'uid'      as uid,
                                              s.value::jsonb -> 'metadatas' as metadata

                                       from strapi_core_store_settings s)
             select sm.uid as api_uid,
                    'component' as collection_type,
                    cm.tab_name as collection_name,
                    sm.uid as display_name,
                    '' as singular_name,
                    '' as plural_name,
                    false as draft_and_publish,
                    false as localized,
                    false as content_manager_visible,
                    false as content_type_builder_visible,

                    (SELECT json_object_agg(
                                    k,
                                    json_build_object(
                                            'db_col', col ->> 'name',
                                            'type',
                                            CASE
                                                WHEN col ->> 'type' = 'increments' THEN 'integer'
                                                WHEN col ->> 'type' = 'text' THEN 'string'
                                                WHEN col ->> 'type' = 'string' THEN 'string'
                                                WHEN col ->> 'type' = 'integer' THEN 'integer'
                                                WHEN col ->> 'type' = 'boolean' THEN 'boolean'
                                                WHEN col ->> 'type' = 'datetime' THEN 'datetime'
                                                WHEN col ->> 'type' = 'timestamp' THEN 'datetime'
                                                ELSE col ->> 'type'
                                                END,
                                            'required', COALESCE((col ->> 'notNullable')::boolean, false),
                                            'unique',
                                            CASE
                                                WHEN col -> 'args' -> 0 ->> 'primary' = 'true' THEN true
                                                WHEN col -> 'args' -> 0 ->> 'primaryKey' = 'true' THEN true
                                                ELSE false
                                                END,
                                            'repeatable', false,
                                            'component', null
                                    )
                            )
                     FROM jsonb_object_keys(sm.metadata) k
                              LEFT JOIN json_array_elements(cm.col_metadata) col
                                        ON lower(replace(k, '_', '')) = lower(replace(col ->> 'name', '_', ''))
                    )::jsonb as columns_data


             from setting_metadata sm
                      inner join cols_metadata cm
                                 on sm.candidate = cm.tab_name
             union
             SELECT j.key                                                                          as api_uid,
                    j.value ->> 'kind'                                                             as collection_type,
                    j.value ->> 'collectionName'                                                   as collection_name,
                    j.value -> 'info' ->> 'displayName'                                            as display_name,
                    j.value -> 'info' ->> 'singularName'                                           as singular_name,
                    j.value -> 'info' ->> 'pluralName'                                             as plural_name,
                    coalesce(j.value -> 'options' ->> 'draftAndPublish', 'false')::bool            as draft_and_publish,
                    coalesce(j.value -> 'pluginOptions' -> 'i18n' ->> 'localized', 'false') ::bool as localized,
                    coalesce(j.value -> 'pluginOptions' -> 'content-manager' ->> 'visible',
                             'false')::bool                                                        as content_manager_visible,
                    coalesce(j.value -> 'pluginOptions' -> 'content-type-builder' ->> 'visible',
                             'false') ::bool                                                       as content_type_builder_visible,

                    (SELECT json_object_agg(
                                    attr.key,
                                    json_build_object(
                                            'type', attr.value ->> 'type',
                                            'required', coalesce((attr.value ->> 'required')::boolean, false),
                                            'unique', coalesce((attr.value ->> 'unique')::boolean, false),
                                            'repeatable', coalesce((attr.value ->> 'repeatable')::boolean, false),
                                            'component', attr.value ->> 'component'
                                    )
                            )
                     FROM json_each(j.value -> 'attributes') attr)    ::jsonb                             as columns_data
             FROM strapi_core_store_settings s,
                  json_each(s.value::json) j
             WHERE s.key = 'strapi_content_types_schema';



            """.trimIndent(), explicitStatementType = StatementType.SELECT
        ) { rs ->
            val results = mutableListOf<DbTableMetadata>()
            try {


                while (rs.next()) {
                    val columns = rs.getString("columns_data")?.let {
                        val obj = Json.parseToJsonElement(it).jsonObject
                        obj.map { (k, v) ->
                            DbColumnMeta(
                                name = k,
                                required = v.jsonObject["required"]?.jsonPrimitive?.booleanOrNull ?: false,
                                unique = v.jsonObject["unique"]?.jsonPrimitive?.booleanOrNull ?: false,
                                repeatable = v.jsonObject["repeatable"]?.jsonPrimitive?.booleanOrNull ?: false,
                                type = v.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "string",
                                component = v.jsonObject["component"]?.jsonPrimitive?.contentOrNull,
                            )
                        }
                    } ?: emptyList()
                    results.add(
                        DbTableMetadata(
                            apiUid = rs.getString("api_uid"),
                            collectionType = rs.getString("collection_type").let {
                                when (it) {
                                    "singleType" -> StrapiContentTypeKind.SingleType
                                    "collectionType" -> StrapiContentTypeKind.CollectionType
                                    "component" -> StrapiContentTypeKind.Component
                                    else -> throw IllegalArgumentException("Unknown collection type: $it")
                                }
                            },
                            singularName = rs.getString("singular_name"),
                            pluralName = rs.getString("plural_name"),
                            collectionName = rs.getString("collection_name"),
                            displayName = rs.getString("display_name"),
                            draftAndPublish = rs.getBoolean("draft_and_publish"),
                            localized = rs.getBoolean("localized"),
                            contentManagerVisible = rs.getBoolean("content_manager_visible"),
                            contentTypeBuilderVisible = rs.getBoolean("content_type_builder_visible"),
                            columns = columns,
                        )
                    )
                }
            } catch (e: Exception) {
                logger.error("Error parsing strapi_core_store_settings: ${e.message}", e)
                throw e
            }
            results
        }?.toList() ?: emptyList()
    }
}


// Added for async mode: build comparable signatures from DbSchema and compare against live target
private fun buildTableDefFromDbSchema(schema: DbSchema): Map<String, TableDef> {
    fun colSig(c: DbColumn): String = buildString {
        append("type=").append(c.type)
        append("|args=").append(c.args?.toString())
        append("|defaultTo=").append(c.defaultTo?.toString())
        append("|notNullable=").append(c.notNullable)
        append("|unsigned=").append(c.unsigned)
    }
    fun idxSig(i: DbIndex): String = buildString {
        append("columns=").append(i.columns.joinToString(","))
        append("|type=").append(i.type)
    }
    fun fkSig(fk: DbForeignKey): String = buildString {
        append("columns=").append(fk.columns.joinToString(","))
        append("|refTable=").append(fk.referencedTable)
        append("|refCols=").append(fk.referencedColumns.joinToString(","))
        append("|onDelete=").append(fk.onDelete)
    }
    val map = mutableMapOf<String, TableDef>()
    for (t in schema.tables) {
        val colMap = t.columns.associate { it.name to colSig(it) }
        val idxMap = t.indexes.associate { it.name to idxSig(it) }
        val fkMap = t.foreignKeys.associate { it.name to fkSig(it) }
        map[t.name] = TableDef(colMap, idxMap, fkMap)
    }
    return map
}

suspend fun SyncService.checkSchemaCompatibilityAgainstTarget(
    sourceSchema: DbSchema,
    targetInstance: StrapiInstance
): SchemaDbCompatibilityResult {
    val logger = LoggerFactory.getLogger("SyncServiceDb")
    logger.info("Checking DB schema compatibility between uploaded source schema and live target '${targetInstance.name}'")

    val targetJson = fetchStrapiSchemaJsonFromDb(targetInstance)
    if (targetJson == null) {
        logger.warn("Target schema JSON missing; returning incompatible result")
        return SchemaDbCompatibilityResult(isCompatible = false)
    }

    val targetContentTypes: Map<String, StrapiContentType> =
        targetInstance.client().getContentTypes().associateBy { it.schema.collectionName }
    val targetComponents: Map<String, StrapiComponent> =
        targetInstance.client().getComponentSchema().associateBy { it.schema.collectionName }
    val targetSchema = parseDbSchemaModel(targetJson, targetContentTypes, targetComponents)

    val sourceDef = buildTableDefFromDbSchema(sourceSchema)
    val targetDef = buildTableDefFromDbSchema(targetSchema)

    val sourceTables = sourceDef.keys
    val targetTables = targetDef.keys

    val missingInTarget = sourceTables.minus(targetTables).sorted()
    val missingInSource = targetTables.minus(sourceTables).sorted()
    val commonTables = sourceTables.intersect(targetTables)

    val tableDiffs = mutableListOf<TableDifference>()
    for (table in commonTables) {
        val s = sourceDef.getValue(table)
        val t = targetDef.getValue(table)
        val colMissingInTarget = s.columns.keys.minus(t.columns.keys).sorted()
        val colMissingInSource = t.columns.keys.minus(s.columns.keys).sorted()
        val commonCols = s.columns.keys.intersect(t.columns.keys)
        val differentCols = commonCols.mapNotNull { c ->
            val sv = s.columns.getValue(c)
            val tv = t.columns.getValue(c)
            if (sv != tv) ColumnDifference(c, sv, tv) else null
        }
        val idxMissingInTarget = s.indexes.keys.minus(t.indexes.keys).sorted()
        val idxMissingInSource = t.indexes.keys.minus(s.indexes.keys).sorted()
        val commonIdx = s.indexes.keys.intersect(t.indexes.keys)
        val differentIdx = commonIdx.mapNotNull { i ->
            val sv = s.indexes.getValue(i)
            val tv = t.indexes.getValue(i)
            if (sv != tv) IndexDifference(i, sv, tv) else null
        }
        val fkMissingInTarget = s.foreignKeys.keys.minus(t.foreignKeys.keys).sorted()
        val fkMissingInSource = t.foreignKeys.keys.minus(s.foreignKeys.keys).sorted()
        val commonFk = s.foreignKeys.keys.intersect(t.foreignKeys.keys)
        val differentFk = commonFk.mapNotNull { f ->
            val sv = s.foreignKeys.getValue(f)
            val tv = t.foreignKeys.getValue(f)
            if (sv != tv) ForeignKeyDifference(f, sv, tv) else null
        }
        if (colMissingInTarget.isNotEmpty() || colMissingInSource.isNotEmpty() ||
            differentCols.isNotEmpty() || idxMissingInTarget.isNotEmpty() || idxMissingInSource.isNotEmpty() ||
            differentIdx.isNotEmpty() || fkMissingInTarget.isNotEmpty() || fkMissingInSource.isNotEmpty() ||
            differentFk.isNotEmpty()
        ) {
            tableDiffs.add(
                TableDifference(
                    table = table,
                    missingColumnsInTarget = colMissingInTarget,
                    missingColumnsInSource = colMissingInSource,
                    differentColumns = differentCols,
                    missingIndexesInTarget = idxMissingInTarget,
                    missingIndexesInSource = idxMissingInSource,
                    differentIndexes = differentIdx,
                    missingForeignKeysInTarget = fkMissingInTarget,
                    missingForeignKeysInSource = fkMissingInSource,
                    differentForeignKeys = differentFk
                )
            )
        }
    }

    val isCompatible = missingInTarget.isEmpty() && missingInSource.isEmpty() && tableDiffs.isEmpty()
    val extracted: DbSchema? = if (isCompatible) sourceSchema else null

    return SchemaDbCompatibilityResult(
        isCompatible = isCompatible,
        missingTablesInTarget = missingInTarget,
        missingTablesInSource = missingInSource,
        tableDifferences = tableDiffs.sortedBy { it.table },
        extractedSchema = extracted
    )
}


// Public helper to build enriched DbSchema for a single instance (used for async export)
suspend fun buildDbSchemaForInstance(instance: StrapiInstance): DbSchema? {
    val json = fetchStrapiSchemaJsonFromDb(instance) ?: return null
    val contentTypesMeta: Map<String, StrapiContentType> =
        instance.client().getContentTypes().associateBy { it.schema.collectionName }
    val componentMeta: Map<String, StrapiComponent> =
        instance.client().getComponentSchema().associateBy { it.schema.collectionName }
    return parseDbSchemaModel(json, contentTypesMeta, componentMeta)
}
