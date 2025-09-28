package it.sebi

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import it.sebi.repository.MergeRequestRepository
import it.sebi.repository.MergeRequestSelectionsRepository
import it.sebi.repository.StrapiInstanceRepository
import it.sebi.routes.configureInstanceRoutes
import it.sebi.routes.configureMergeRequestRoutes
import it.sebi.service.MergeRequestService
import it.sebi.service.SyncService

fun Application.configureRouting() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            this@configureRouting.log.error("Error handling request ${call.request.uri}", cause)
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }

    val instanceRepository = StrapiInstanceRepository()
    val syncService = SyncService()
    val mergeRequestRepository = MergeRequestRepository(instanceRepository)
    val mergeRequestSelectionsRepository = MergeRequestSelectionsRepository()
    val mergeRequestService = MergeRequestService(
        this.environment.config,
        mergeRequestRepository,
        syncService,
        mergeRequestSelectionsRepository
    )

    routing {
        // API routes
        configureInstanceRoutes(
            instanceRepository,
            mergeRequestRepository,
            mergeRequestSelectionsRepository
        )
        configureMergeRequestRoutes(mergeRequestService)


        singlePageApplication {
            useResources = true
            react("frontend-build")
        }

    }


}
