package it.sebi

import io.ktor.server.application.*
import it.sebi.database.initDatabaseConnection

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    configureHTTP()
    configureMonitoring()
    configureSerialization()
    configureSSE()
    initDatabaseConnection()
    configureRouting()
}
