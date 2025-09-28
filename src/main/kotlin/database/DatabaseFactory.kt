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
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

object DatabaseFactory {

    private var initDb: Database? = null

    val database: Database?
        get() = initDb

    fun init(log: Logger) {
        if (initDb == null) {
            initDb = Database.connect(hikari(log))
            transaction(initDb) {
                SchemaUtils.createMissingTablesAndColumns(
                    StrapiInstancesTable,
                    MergeRequestsTable,
                    MergeRequestDocumentMappingTable,
                    MergeRequestSelectionsTable
                )
            }
        }
    }

    suspend fun <T> dbQuery(block: suspend JdbcTransaction.() -> T): T =
        newSuspendedTransaction(db = initDb, context = Dispatchers.IO) { block() }


}

fun Application.initDatabaseConnection() {
    // Connect to the database
    val database = Database.connect(hikari(this.log))


    // Create tables
    transaction(database) {
        SchemaUtils.createMissingTablesAndColumns(
            StrapiInstancesTable,
            MergeRequestsTable,
            MergeRequestDocumentMappingTable,
            MergeRequestSelectionsTable
        )
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


suspend fun <T> dbQuery(db: Database? = DatabaseFactory.database, block: suspend JdbcTransaction.() -> T): T =
    newSuspendedTransaction(db = db, context = Dispatchers.IO) { block() }

//
//
//fun ResultSet.getStringOrNull(columnName: String): String? = this.get(columnName, String::class.java)
//fun ResultSet.getString(columnName: String): String = get(columnName, String::class.java)?: error("Column $columnName is null")
//fun ResultSet.getBigDecimalOrNull(columnName: String): BigDecimal? = get(columnName, BigDecimal::class.java)
//fun ResultSet.getBigDecimal(columnName: String): BigDecimal = get(columnName, BigDecimal::class.java)?: error("Column $columnName is null")
//fun ResultSet.getTimestampOrNull(columnName: String): Timestamp? = get(columnName, Timestamp::class.java)
//fun ResultSet.getTimestamp(columnName: String): Timestamp = get(columnName, Timestamp::class.java)?: error("Column $columnName is null")