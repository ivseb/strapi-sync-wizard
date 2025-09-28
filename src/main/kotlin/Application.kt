package it.sebi

import io.ktor.server.application.*
import it.sebi.database.DatabaseFactory

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    AppSettings.init(environment.config)
    DatabaseFactory.init(log)
    configureHTTP()
    configureMonitoring()
    configureSerialization()
    configureSSE()

    configureRouting()
}
