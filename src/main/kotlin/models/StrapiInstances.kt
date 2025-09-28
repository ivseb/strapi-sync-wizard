package it.sebi.models

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.OffsetDateTime

@Serializable
data class StrapiInstance(
    val id: Int = 0,
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val apiKey: String,
    // Optional Postgres connection settings per instance
    val dbHost: String? = null,
    val dbPort: Int? = null,
    val dbName: String? = null,
    val dbSchema: String? = null,
    val dbUser: String? = null,
    val dbPassword: String? = null,
    val dbSslMode: String? = null,
    @Contextual val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val updatedAt: OffsetDateTime = OffsetDateTime.now()
) {


    val database by lazy {
        if (dbHost == null || dbPort == null || dbName == null || dbUser == null || dbPassword == null) {
            null
        } else {
            val dbUrl = "jdbc:postgresql://$dbHost:$dbPort/$dbName?currentSchema=$dbSchema"
            Database.connect(
                url = dbUrl,
                user = dbUser,
                password = dbPassword,
                databaseConfig = DatabaseConfig {
                    defaultMaxAttempts = 1
                })
        }

    }
}

/**
 * Secure version of StrapiInstance that excludes sensitive fields (password and apiKey)
 */
@Serializable
data class StrapiInstanceSecure(
    val id: Int = 0,
    val name: String,
    val url: String,
    val username: String,
    // Optional Postgres connection settings are safe to expose except passwords
    val dbHost: String? = null,
    val dbPort: Int? = null,
    val dbName: String? = null,
    val dbSchema: String? = null,
    val dbUser: String? = null,
    val dbSslMode: String? = null,
    @Contextual val createdAt: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val updatedAt: OffsetDateTime = OffsetDateTime.now()
)

@Serializable
data class StrapiInstanceDTO(
    val id: Int? = null,
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val apiKey: String,
    // Optional Postgres connection settings per instance for create/update
    val dbHost: String? = null,
    val dbPort: Int? = null,
    val dbName: String? = null,
    val dbSchema: String? = null,
    val dbUser: String? = null,
    val dbPassword: String? = null,
    val dbSslMode: String? = null
)
