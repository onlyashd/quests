package org.ferabrasil.quests.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.ferabrasil.quests.server.db.Db
import org.ferabrasil.quests.server.service.ArtifactStore

@Serializable
data class HealthDto(val db: Boolean, val storage: Boolean)

/** Mounted on the internal port only (backend.md §10: 9090). Metrics (S5) land in B7. */
fun Route.internalRoutes(db: Db, artifacts: ArtifactStore) {
    get("/healthz") {
        val health = withContext(Dispatchers.IO) { HealthDto(db.isHealthy(), artifacts.isHealthy()) }
        val status = if (health.db && health.storage) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(status, health)
    }
}
