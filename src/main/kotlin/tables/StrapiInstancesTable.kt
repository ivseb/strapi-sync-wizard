package it.sebi.tables

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.crypt.Algorithms
import org.jetbrains.exposed.v1.crypt.encryptedVarchar
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime
import java.lang.System.getenv


/**
 * Database table for Strapi instances
 */
object StrapiInstancesTable : IntIdTable("strapi_instances") {
    val name = varchar("name", 255)
    val url = varchar("url", 255)
    val username = varchar("username", 255)
    val password =
        encryptedVarchar("password", 255, Algorithms.AES_256_PBE_CBC("passwd", getenv("DB_SALT") ?: "5c0744940b5c369b"))
    val apiKey =
        encryptedVarchar("api_key", 2000, Algorithms.AES_256_PBE_CBC("passwd", getenv("DB_SALT") ?: "5c0744940b5c369b"))
    val createdAt = timestampWithTimeZone("created_at").default(OffsetDateTime.now())
    val updatedAt = timestampWithTimeZone("updated_at").default(OffsetDateTime.now())

}
