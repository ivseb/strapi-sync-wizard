package it.sebi.service.merge

import it.sebi.models.DbColumnMeta
import it.sebi.models.DbTable
import it.sebi.models.DbTableMetadata
import it.sebi.models.StrapiContentTypeKind
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentJsonUtilsHelpersTest {

    @Test
    fun `normalizeKeyName strips underscores and lowercases`() {
        assertEquals("myfieldname", ContentJsonUtils.normalizeKeyName("my_field_name"))
        assertEquals("myfieldname", ContentJsonUtils.normalizeKeyName("MyFieldName"))
        assertEquals("documentid", ContentJsonUtils.normalizeKeyName("document_id"))
        assertEquals("", ContentJsonUtils.normalizeKeyName("___"))
    }

    @Test
    fun `convertToCamelCase converts snake_case`() {
        assertEquals("myFieldName", ContentJsonUtils.convertToCamelCase("my_field_name"))
        assertEquals("createdAt", ContentJsonUtils.convertToCamelCase("created_at"))
        // No underscore → returned verbatim.
        assertEquals("alreadyCamel", ContentJsonUtils.convertToCamelCase("alreadyCamel"))
        // Trailing underscore yields an empty trailing part that is skipped.
        assertEquals("foo", ContentJsonUtils.convertToCamelCase("foo_"))
        // Inner uppercase letters are lowercased in non-first parts.
        assertEquals("fooBar", ContentJsonUtils.convertToCamelCase("foo_BAR"))
    }

    @Test
    fun `buildFieldNameResolver maps normalized names back to original and adds technical keys`() {
        val table = DbTable(
            name = "articles",
            metadata = DbTableMetadata(
                apiUid = "api::article.article",
                collectionType = StrapiContentTypeKind.CollectionType,
                collectionName = "articles",
                singularName = "article",
                pluralName = "articles",
                displayName = "Article",
                draftAndPublish = true,
                localized = false,
                contentManagerVisible = true,
                contentTypeBuilderVisible = true,
                columns = listOf(
                    column("title"),
                    column("cover_image"),
                ),
            ),
        )

        val resolver = ContentJsonUtils.buildFieldNameResolver(table)

        // Column names recoverable via their normalized form.
        assertEquals("title", resolver["title"])
        assertEquals("cover_image", resolver["coverimage"])
        // Technical keys are always present.
        assertEquals("documentId", resolver["documentid"])
        assertEquals("createdAt", resolver["createdat"])
        assertEquals("id", resolver["id"])
    }

    private fun column(name: String) = DbColumnMeta(
        name = name,
        required = false,
        type = "string",
        unique = false,
        repeatable = false,
    )
}
