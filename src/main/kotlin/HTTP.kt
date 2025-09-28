package it.sebi

import com.asyncapi.kotlinasyncapi.context.service.AsyncApiExtension
import com.asyncapi.kotlinasyncapi.ktor.AsyncApiPlugin
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.sse.*

fun Application.configureHTTP() {
    install(CORS) {
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowCredentials = true
        allowHeader(HttpHeaders.Accept)
        allowHeader("text/event-stream")
        allowNonSimpleContentTypes = true



        anyHost()
    }
    install(SSE)
    install(AsyncApiPlugin) {
        extension = AsyncApiExtension.builder {
            info {
                title("Sample API")
                version("1.0.0")
            }
        }
    }
    // Configure compression but avoid compressing Server-Sent Events (text/event-stream)
    install(Compression) {
        gzip {
            // Only compress common static/text responses; DO NOT compress event streams
            matchContentType(
                ContentType.Text.Html,
                ContentType.Text.Plain,
                ContentType.Application.Json
            )
            minimumSize(1024)
        }
        deflate {
            matchContentType(
                ContentType.Text.Html,
                ContentType.Text.Plain,
                ContentType.Application.Json
            )
            minimumSize(2048)
        }
    }
}
