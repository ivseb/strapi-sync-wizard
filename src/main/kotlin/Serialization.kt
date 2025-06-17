package it.sebi

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import it.sebi.serializers.OffsetDateTimeSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonBuilder
import kotlinx.serialization.modules.SerializersModule
import java.time.OffsetDateTime

val builderAction: JsonBuilder.() -> Unit  = {
    prettyPrint = true
    isLenient = true
    encodeDefaults = true
    ignoreUnknownKeys = true
    serializersModule = SerializersModule {
        contextual(OffsetDateTime::class, OffsetDateTimeSerializer)
    }
}

val JsonParser = Json(builderAction = builderAction)

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(JsonParser)
    }
}


