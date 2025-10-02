package it.sebi.service.merge

import it.sebi.models.ContentTypeComparisonResultMapWithRelationships
import it.sebi.models.DbSchema
import it.sebi.models.DbTable
import it.sebi.models.MergeRequestDocumentMapping
import it.sebi.models.STRAPI_FILE_CONTENT_TYPE_NAME
import it.sebi.models.StrapiLinkRef
import it.sebi.service.TECHNICAL_FIELDS
import it.sebi.service.resolveComponentTableName
import kotlinx.serialization.json.*
import kotlin.collections.forEach

object ContentJsonUtils {
    private fun resolveTargetDocIdForLink(
        table: String,
        targetCollectionUID: String,
        id: Int?,
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships,
        mappingMap: Map<String, Map<String, MergeRequestDocumentMapping>> = emptyMap()
    ): String? {
        if (id == null) return null
        // Step 1: find the SOURCE documentId for the provided numeric id
        var sourceDocId: String? = null
        when (table) {
            "files" -> {
                comparisonDataMap.files.forEach { cmp ->
                    val s = cmp.sourceImage?.metadata
                    if (s?.id == id) sourceDocId = s.documentId
                    val t = cmp.targetImage?.metadata
                    // if only target match is found, keep that as fallback
                    if (sourceDocId == null && t?.id == id) sourceDocId = t.documentId
                }
            }

            else -> {
                comparisonDataMap.singleTypes[table]?.let { st ->
                    if (st.sourceContent?.metadata?.id == id) sourceDocId = st.sourceContent.metadata.documentId
                    if (sourceDocId == null && st.targetContent?.metadata?.id == id) sourceDocId =
                        st.targetContent.metadata.documentId
                }
                if (sourceDocId == null) {
                    comparisonDataMap.collectionTypes[table]?.forEach { e ->
                        if (e.sourceContent?.metadata?.id == id) sourceDocId = e.sourceContent.metadata.documentId
                        if (sourceDocId == null && e.targetContent?.metadata?.id == id) sourceDocId =
                            e.targetContent.metadata.documentId
                    }
                }
            }
        }
        val src = sourceDocId ?: return null
        // Step 2: map SOURCE -> TARGET using mappingMap (if present)
        val mappingForType = mappingMap[targetCollectionUID]
        val targetDoc = mappingForType?.get(src)?.targetDocumentId
        return targetDoc ?: src
    }

    fun processJsonElementNew(
        comparisonDataMap: ContentTypeComparisonResultMapWithRelationships,
        element: JsonElement,
        currentSchema: DbTable?,
        dbSchema: DbSchema,
        contentTypeMappingByTable: Map<String, DbTable>,
        linkToProcess: List<StrapiLinkRef>,
        allTargetFileIdByDoc: Map<String, Int> = emptyMap(),
        mappingMap: Map<String, Map<String, MergeRequestDocumentMapping>> = emptyMap()
    ): JsonElement {
        val currentResolver = currentSchema?.let { buildFieldNameResolver(it) }

        fun resolveTargetValue(link: StrapiLinkRef): JsonElement? {
            return if (link.targetTable == "files") {

                val srcOrTgtDocId = resolveTargetDocIdForLink(
                    link.targetTable,
                    STRAPI_FILE_CONTENT_TYPE_NAME,
                    link.targetId,
                    comparisonDataMap,
                    mappingMap
                )
                val numericId = srcOrTgtDocId?.let { did ->
                    allTargetFileIdByDoc[did]
                        ?: comparisonDataMap.files.firstOrNull { f -> f.targetImage?.metadata?.documentId == did }?.targetImage?.metadata?.id
                }
                numericId?.let { JsonPrimitive(it) }
            } else {
                val table = link.targetTable
                contentTypeMappingByTable[table]?.metadata?.let { schemaLinkMeta ->
                    val targetDocId =
                        resolveTargetDocIdForLink(
                            link.targetTable,
                            schemaLinkMeta.apiUid,
                            link.targetId,
                            comparisonDataMap,
                            mappingMap
                        )
                    targetDocId?.let { JsonPrimitive(it) }
                }
            }
        }
        return when (element) {
            is JsonObject -> {
                buildJsonObject {
                    val currentId = element["id"]?.jsonPrimitive?.intOrNull
                    val linkToAdd = linkToProcess.filter { !it.field.contains('.') && it.sourceId == (currentId ?: 0) }
                    if (linkToAdd.isNotEmpty()) {
                        linkToAdd.groupBy { it.field }.forEach { (field, value) ->
                            value.groupBy { it.targetTable }.forEach { (table, links) ->
                                val normalized = normalizeKeyName(field)
                                val mappedKey = currentResolver?.get(normalized+"s")?: currentResolver?.get(normalized) ?: convertToCamelCase(field)

                                val linkMap: List<JsonElement> = links.mapNotNull { resolveTargetValue(it) }
                                if(linkMap.isNotEmpty()){
                                    put(mappedKey, buildJsonObject { put("set", JsonArray(linkMap))})
                                }

                            }
                        }
                    }
                    for ((key, value) in element) {
                        println(key)
                        if (TECHNICAL_FIELDS.contains(key) || key == "document_id" || key == "__order" || key == "__links" || key == "id") continue
                        val normalized = normalizeKeyName(key)
                        val mappedKey = currentResolver?.get(normalized) ?: convertToCamelCase(key)
                        val nestedLinkToProcess = linkToProcess.filter { it.field.startsWith("$key.") }
                            .map { it.copy(field = it.field.substring("$key.".length)) }


                        // Determine if this field is a component of the current schema
                        val col =
                            currentSchema?.metadata?.columns?.firstOrNull { normalizeKeyName(it.name) == normalized }
                        val nextSchemaAndResolver: DbTable? = if (col?.component != null) {
                            val compTable = resolveComponentTableName(col.component, dbSchema)
                            compTable?.let { contentTypeMappingByTable[it] }
                        } else null

                        val processedValue: JsonElement = if (nextSchemaAndResolver != null) {

                            val isRepeatable = col?.repeatable == true
                            when {
                                isRepeatable && value is JsonObject -> {
                                    // Wrap single object into array for repeatable component fields
                                    buildJsonArray {
                                        add(
                                            processJsonElementNew(
                                                comparisonDataMap,
                                                value,
                                                nextSchemaAndResolver,
                                                dbSchema,
                                                contentTypeMappingByTable,
                                                nestedLinkToProcess,
                                                allTargetFileIdByDoc,
                                                mappingMap
                                            )
                                        )
                                    }
                                }

                                isRepeatable && value is JsonArray -> {
                                    buildJsonArray {
                                        value.forEach {
                                            add(
                                                processJsonElementNew(
                                                    comparisonDataMap,
                                                    it,
                                                    nextSchemaAndResolver,
                                                    dbSchema,
                                                    contentTypeMappingByTable,
                                                    nestedLinkToProcess,
                                                    allTargetFileIdByDoc,
                                                    mappingMap
                                                )
                                            )
                                        }
                                    }
                                }

                                value is JsonArray -> {

                                    processJsonElementNew(
                                        comparisonDataMap,
                                        value.first(),
                                        nextSchemaAndResolver,
                                        dbSchema,
                                        contentTypeMappingByTable,
                                        nestedLinkToProcess,
                                        allTargetFileIdByDoc,
                                        mappingMap
                                    )
                                }

                                value is JsonObject -> {
                                    processJsonElementNew(
                                        comparisonDataMap,
                                        value,
                                        nextSchemaAndResolver,
                                        dbSchema,
                                        contentTypeMappingByTable,
                                        nestedLinkToProcess,
                                        allTargetFileIdByDoc,
                                        mappingMap
                                    )
                                }

                                else -> value
                            }
                        } else {
                            // Not a component field: keep current resolver
                            when (value) {
                                is JsonArray -> buildJsonArray {
                                    value.forEach {
                                        add(
                                            processJsonElementNew(
                                                comparisonDataMap,
                                                it,
                                                nextSchemaAndResolver,
                                                dbSchema,
                                                contentTypeMappingByTable,
                                                nestedLinkToProcess,
                                                allTargetFileIdByDoc,
                                                mappingMap
                                            )
                                        )
                                    }
                                }

                                is JsonObject -> processJsonElementNew(
                                    comparisonDataMap,
                                    value,
                                    nextSchemaAndResolver,
                                    dbSchema,
                                    contentTypeMappingByTable,
                                    nestedLinkToProcess,
                                    allTargetFileIdByDoc,
                                    mappingMap
                                )

                                else -> value
                            }
                        }
                        put(mappedKey, processedValue)
                    }


                }
            }

            is JsonArray -> buildJsonArray {
                element.forEach {
                    add(
                        processJsonElementNew(
                            comparisonDataMap,
                            it,
                            currentSchema,
                            dbSchema,
                            contentTypeMappingByTable,
                            linkToProcess,
                            allTargetFileIdByDoc,
                            mappingMap
                        )
                    )
                }
            }

            else -> element
        }
    }

    // New overload: resolve field names contextually, switching resolver when traversing into components
//    fun prepareDataForCreation(
//        sourceData: JsonObject,
//        rootSchema: DbTable,
//        dbSchema: DbSchema,
//        contentTypeMappingByTable: Map<String, DbTable>
//    ): JsonObject {
//        val rootResolver = buildFieldNameResolver(rootSchema)
//        return processJsonElement(
//            element = sourceData,
//            currentResolver = rootResolver,
//            currentSchema = rootSchema,
//            dbSchema = dbSchema,
//            contentTypeMappingByTable = contentTypeMappingByTable
//        ) as JsonObject
//    }

    fun processJsonElement(element: JsonElement, fieldNameResolver: Map<String, String>): JsonElement {
        return when (element) {
            is JsonObject -> {
                buildJsonObject {
                    for ((key, value) in element) {
                        if (key == "document_id" || key == "__order" || key == "__links" || key == "id") continue
                        val normalized = normalizeKeyName(key)
                        val mappedKey = fieldNameResolver[normalized] ?: convertToCamelCase(key)
                        val processedValue = processJsonElement(value, fieldNameResolver)
                        put(mappedKey, processedValue)
                    }
                }
            }

            is JsonArray -> buildJsonArray { element.forEach { add(processJsonElement(it, fieldNameResolver)) } }
            else -> element
        }
    }

    // Overload that changes resolver when navigating into component fields
//    fun processJsonElement(
//        element: JsonElement,
//        currentResolver: Map<String, String>,
//        currentSchema: DbTable?,
//        dbSchema: DbSchema,
//        contentTypeMappingByTable: Map<String, DbTable>
//    ): JsonElement {
//        return when (element) {
//            is JsonObject -> {
//                buildJsonObject {
//                    for ((key, value) in element) {
//                        if (key == "document_id" || key == "__order" || key == "__links" || key == "id") continue
//                        val normalized = normalizeKeyName(key)
//                        val mappedKey = currentResolver[normalized] ?: convertToCamelCase(key)
//
//                        // Determine if this field is a component of the current schema
//                        val col =
//                            currentSchema?.metadata?.columns?.firstOrNull { normalizeKeyName(it.name) == normalized }
//                        val nextSchemaAndResolver = if (col?.component != null) {
//                            val compTable = resolveComponentTableName(col.component, dbSchema)
//                            val compSchema = compTable?.let { contentTypeMappingByTable[it] }
//                            if (compSchema != null) buildFieldNameResolver(compSchema) to compSchema else null
//                        } else null
//
//                        val processedValue: JsonElement = if (nextSchemaAndResolver != null) {
//                            val (subResolver, subSchema) = nextSchemaAndResolver
//                            val isRepeatable = col?.repeatable == true
//                            when {
//                                isRepeatable && value is JsonObject -> {
//                                    // Wrap single object into array for repeatable component fields
//                                    buildJsonArray {
//                                        add(
//                                            processJsonElement(
//                                                value,
//                                                subResolver,
//                                                subSchema,
//                                                dbSchema,
//                                                contentTypeMappingByTable
//                                            )
//                                        )
//                                    }
//                                }
//
//                                isRepeatable && value is JsonArray -> {
//                                    buildJsonArray {
//                                        value.forEach {
//                                            add(
//                                                processJsonElement(
//                                                    it,
//                                                    subResolver,
//                                                    subSchema,
//                                                    dbSchema,
//                                                    contentTypeMappingByTable
//                                                )
//                                            )
//                                        }
//                                    }
//                                }
//
//                                value is JsonArray -> {
//
//                                    processJsonElement(
//                                        value.first(),
//                                        subResolver,
//                                        subSchema,
//                                        dbSchema,
//                                        contentTypeMappingByTable
//                                    )
//                                }
//
//                                value is JsonObject -> {
//                                    processJsonElement(
//                                        value,
//                                        subResolver,
//                                        subSchema,
//                                        dbSchema,
//                                        contentTypeMappingByTable
//                                    )
//                                }
//
//                                else -> value
//                            }
//                        } else {
//                            // Not a component field: keep current resolver
//                            when (value) {
//                                is JsonArray -> buildJsonArray {
//                                    value.forEach {
//                                        add(
//                                            processJsonElement(
//                                                it,
//                                                currentResolver,
//                                                currentSchema,
//                                                dbSchema,
//                                                contentTypeMappingByTable
//                                            )
//                                        )
//                                    }
//                                }
//
//                                is JsonObject -> processJsonElement(
//                                    value,
//                                    currentResolver,
//                                    currentSchema,
//                                    dbSchema,
//                                    contentTypeMappingByTable
//                                )
//
//                                else -> value
//                            }
//                        }
//                        put(mappedKey, processedValue)
//                    }
//                }
//            }
//
//            is JsonArray -> buildJsonArray {
//                element.forEach {
//                    add(
//                        processJsonElement(
//                            it,
//                            currentResolver,
//                            currentSchema,
//                            dbSchema,
//                            contentTypeMappingByTable
//                        )
//                    )
//                }
//            }
//
//            else -> element
//        }
//    }

    fun convertToCamelCase(snakeCase: String): String {
        if (!snakeCase.contains("_")) return snakeCase
        val parts = snakeCase.split("_")
        val camelCase = StringBuilder(parts[0].lowercase())
        for (i in 1 until parts.size) {
            val part = parts[i]
            if (part.isNotEmpty()) {
                camelCase.append(part.first().uppercaseChar())
                if (part.length > 1) camelCase.append(part.substring(1).lowercase())
            }
        }
        return camelCase.toString()
    }

    fun normalizeKeyName(name: String): String = name.replace("_", "").lowercase()

    fun buildFieldNameResolver(contentTypeSchema: DbTable): Map<String, String> {
        val map = mutableMapOf<String, String>()
        contentTypeSchema.metadata?.columns?.forEach { col ->
            map[normalizeKeyName(col.name)] = col.name
        }
        listOf(
            "documentId", "createdAt", "updatedAt", "publishedAt",
            "createdBy", "updatedBy", "locale", "localizations", "id"
        ).forEach { key -> map.putIfAbsent(normalizeKeyName(key), key) }
        return map
    }

    fun processNestedObject(obj: JsonObject): JsonObject? {
        val result = obj.toMutableMap()
        if (obj.containsKey("id") && !obj.containsKey("documentId")) {
            result.remove("id")
        }
        val updates = mutableMapOf<String, JsonElement>()
        val keysToRemove = mutableListOf<String>()
        obj.entries.toList().forEach { (key, value) ->
            when (value) {
                is JsonObject -> {
                    val res = processNestedObject(value)
                    if (res != null) updates[key] = res else keysToRemove.add(key)
                }

                is JsonArray -> {
                    val res = processNestedArray(value)
                    if (res.isNotEmpty()) updates[key] = res else keysToRemove.add(key)
                }

                else -> {}
            }
        }
        updates.forEach { (key, value) -> result[key] = value }
        keysToRemove.forEach { key -> result.remove(key) }
        return JsonObject(result)
    }

    fun processNestedArray(array: JsonArray): JsonArray = buildJsonArray {
        array.forEach { item ->
            when (item) {
                is JsonObject -> processNestedObject(item)?.let { add(it) }
                is JsonArray -> add(processNestedArray(item))
                else -> add(item)
            }
        }
    }

    fun buildNestedObjectFromPath(
        path: String,
        leaf: JsonObject,
        repeatableByKey: Map<String, Boolean>? = null,
        fieldNameResolver: Map<String, String>? = null,
        subFieldNameResolver: Map<String, String>? = null,
    ): JsonObject {
        // Helper to decide if a key is repeatable
        fun isRepeatableKey(key: String): Boolean {
            val normalized = normalizeKeyName(key)
            return repeatableByKey?.get(normalized) == true
        }

        fun resolveKey(raw: String, root: Boolean): String {
            val normalized = normalizeKeyName(raw)
            val normalizedPlural = normalized + "s"
            return if (root) {
                fieldNameResolver?.get(normalized) ?: fieldNameResolver?.get(normalizedPlural)
            } else {
                subFieldNameResolver?.get(normalized) ?: subFieldNameResolver?.get(normalizedPlural)
                ?: fieldNameResolver?.get(normalized) ?: fieldNameResolver?.get(normalizedPlural)
            } ?: normalized
        }

        if (!path.contains('.')) {
            val key = resolveKey(path, true)
            val value: JsonElement = if (isRepeatableKey(path)) JsonArray(listOf(leaf)) else leaf
            return buildJsonObject { put(key, value) }
        }
        val parts = path.split('.')
        var current: JsonElement = leaf
        for (index in parts.size - 1 downTo 0) {
            val part = parts[index]
            val key = resolveKey(part, index == 0)
            val valueToPut: JsonElement = if (isRepeatableKey(part)) JsonArray(listOf(current)) else current
            current = buildJsonObject { put(key, valueToPut) }
        }
        return current as JsonObject
    }

    // Schema-aware builder that resolves each segment using the corresponding component schema when needed
    fun buildNestedObjectFromPathWithSchema(
        path: String,
        leaf: JsonObject,
        rootSchema: DbTable?,
        dbSchema: DbSchema,
        contentTypeMappingByTable: Map<String, DbTable>
    ): JsonObject {
        if (path.isEmpty()) return leaf
        val parts = path.split('.')
        var currentSchema: DbTable? = rootSchema
        var current: JsonElement = leaf

        // Build from leaf upwards
        for (index in parts.indices.reversed()) {
            val rawPart = parts[index]
            val normalized = normalizeKeyName(rawPart)

            // Determine mapping for this level using the current schema (if available)
            val col = currentSchema?.metadata?.columns?.firstOrNull { normalizeKeyName(it.name) == normalized }
            val mappedKey = col?.name ?: buildFieldNameResolver(currentSchema ?: return buildJsonObject {
                put(
                    convertToCamelCase(rawPart), current
                )
            })
                .getOrElse(normalized) { convertToCamelCase(rawPart) }

            // Wrap if this segment is a repeatable component field
            val valueToPut: JsonElement = if (col?.repeatable == true) JsonArray(listOf(current)) else current

            // Create the object for this level
            current = buildJsonObject { put(mappedKey, valueToPut) }

            // Prepare next schema if this is the root-most segment (moving outward) and it is a component
            // When moving upwards, the next iteration will handle the parent segment; so we must update
            // currentSchema to that parent schema. Here, we update schema for the child we just wrapped, so that
            // on the previous iteration we still use parent's schema.
            // Therefore we only change schema for the next inner level before the loop continues: if parent is component, it will be resolved on its iteration.
            // To keep traversal correct, we need to switch schema for the next inner segment when the parent of that inner segment is a component.
            // At this point, to compute the schema for the next iteration (one level deeper), check if col?.component exists.
            currentSchema = if (col?.component != null) {
                val compTable = resolveComponentTableName(col.component, dbSchema)
                if (compTable != null) contentTypeMappingByTable[compTable] else null
            } else {
                // If not a component, deeper keys (towards the leaf) will still use the same schema
                currentSchema
            }
        }
        return current as JsonObject
    }

    fun deepMergeJsonObjects(a: JsonObject, b: JsonObject): JsonObject {
        val result = a.toMutableMap()
        b.forEach { (key, bValue) ->
            val aValue = result[key]
            result[key] = if (aValue is JsonObject && bValue is JsonObject) {
                deepMergeJsonObjects(aValue, bValue)
            } else bValue
        }
        return JsonObject(result)
    }
}