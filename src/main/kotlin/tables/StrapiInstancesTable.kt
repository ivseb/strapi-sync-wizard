package it.sebi.tables

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.crypt.Algorithms
import org.jetbrains.exposed.v1.crypt.encryptedVarchar
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime
import java.lang.System.getenv



// Lazy: viene risolto alla prima necessità, dopo l'init in Application.configureHTTP()
private val crypto by lazy {
    Algorithms.AES_256_PBE_CBC("passwd", AppSettings.dbSalt)
}

/**
 * Database table for Strapi instances
 */
object StrapiInstancesTable : IntIdTable("strapi_instances") {
    val name = varchar("name", 255)
    val url = varchar("url", 255)
    val username = varchar("username", 255)
    val password =
        encryptedVarchar("password", 255, crypto)
    val apiKey =
        encryptedVarchar("api_key", 2000, crypto)

    // Optional Postgres connection settings per instance (nullable for existing records)
    val dbHost = varchar("db_host", 255).nullable()
    val dbPort = integer("db_port").nullable()
    val dbName = varchar("db_name", 255).nullable()
    val dbSchema = varchar("db_schema", 255).nullable()
    val dbUser = varchar("db_user", 255).nullable()
    val dbPassword = encryptedVarchar(
        "db_password",
        255,
        crypto
    ).nullable()
    val dbSslMode = varchar("db_sslmode", 32).nullable()

    val createdAt = timestampWithTimeZone("created_at").default(OffsetDateTime.now())
    val updatedAt = timestampWithTimeZone("updated_at").default(OffsetDateTime.now())

}
