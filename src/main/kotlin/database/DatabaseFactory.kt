package it.sebi.database

import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import io.ktor.server.config.*
import io.ktor.util.logging.*
import it.sebi.tables.MergeRequestDocumentMappingTable
import it.sebi.tables.MergeRequestSelectionsTable
import it.sebi.tables.MergeRequestsTable
import it.sebi.tables.StrapiInstancesTable
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.MigrationUtils

fun Application.initDatabaseConnection() {
    // Connect to the database
    val database = Database.connect(hikari(this.log))


    // Create tables
    transaction(database) {
        val statement = MigrationUtils.statementsRequiredForDatabaseMigration(
            StrapiInstancesTable,
            MergeRequestsTable,
            MergeRequestDocumentMappingTable,
            MergeRequestSelectionsTable
        )
        this.execInBatch(statement)
    }
}

private fun hikari(log: Logger): HikariDataSource {
    val appConfig = HoconApplicationConfig(ConfigFactory.load())
    val dbConfig = appConfig.config("database")

    // Explicitly load the PostgreSQL driver
    val driverClass = dbConfig.property("driverClassName").getString()
    try {
        Class.forName(driverClass)
    } catch (e: ClassNotFoundException) {
        throw RuntimeException("Failed to load database driver: $driverClass", e)
    }

    val config = HikariConfig().apply {
        driverClassName = dbConfig.property("driverClassName").getString()
        jdbcUrl = dbConfig.property("jdbcUrl").getString()
        username = dbConfig.property("username").getString()
        password = dbConfig.property("password").getString()
        maximumPoolSize = dbConfig.property("maximumPoolSize").getString().toInt()
        isAutoCommit = dbConfig.property("isAutoCommit").getString().toBoolean()
        transactionIsolation = dbConfig.property("transactionIsolation").getString()
        validate()
    }

    log.info("Connecting to database: ${config.jdbcUrl}")
    log.info("Connection username: ${config.username}")
    try {
        log.info("Connection password: ${config.password.take(3)}${"*".repeat(config.password.length - 3)}")
    } catch (_: Throwable) {

    }

    return HikariDataSource(config)
}

suspend fun <T> dbQuery(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }
