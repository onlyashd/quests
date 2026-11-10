package org.ferabrasil.quests.server

import com.github.ajalt.clikt.core.main
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.localPort
import io.ktor.server.routing.routing
import org.ferabrasil.quests.protocol.ProtocolJson
import org.ferabrasil.quests.server.admin.AdminCli
import org.ferabrasil.quests.server.config.AppConfig
import org.ferabrasil.quests.server.db.Db
import org.ferabrasil.quests.server.db.Migrations
import org.ferabrasil.quests.server.jobs.JobRunner
import org.ferabrasil.quests.server.routes.configureErrors
import org.ferabrasil.quests.server.routes.internalRoutes
import org.ferabrasil.quests.server.service.ArtifactStore
import org.ferabrasil.quests.server.service.MinioArtifactStore

/** Everything the HTTP module needs. Built once in [main], or by tests. */
class ServerDeps(
    val config: AppConfig,
    val db: Db,
    val artifacts: ArtifactStore,
    val jobs: JobRunner,
)

fun main(args: Array<String>) {
    if (args.firstOrNull() == "admin") {
        AdminCli().main(args.drop(1))
        return
    }

    val config = AppConfig.load()
    val pool = Db.pool(config.db)
    Migrations.migrate(pool)
    val artifacts = MinioArtifactStore(config.s3).also { it.ensureReady() }
    val deps = ServerDeps(config, Db(pool), artifacts, JobRunner(pool, jobs = emptyList()))

    embeddedServer(
        Netty,
        configure = {
            connector { port = config.http.port }
            connector { port = config.http.internalPort }
        },
    ) {
        module(deps)
        monitor.subscribe(ApplicationStopped) { pool.close() }
    }.start(wait = true)
}

fun Application.module(deps: ServerDeps) {
    // No CallLogging, no CallId in logs, no access log (backend.md §9 S1).
    install(ContentNegotiation) { json(ProtocolJson) }
    configureErrors()

    routing {
        localPort(deps.config.http.internalPort) {
            internalRoutes(deps.db, deps.artifacts)
        }
        localPort(deps.config.http.port) {
            outerRoutes()
        }
    }

    deps.jobs.start()
    monitor.subscribe(ApplicationStopped) { deps.jobs.stop() }
}

/** Public port: `GET /ohttp/keys` and `POST /ohttp` (backend.md §3.1) — implemented in B1. */
private fun Route.outerRoutes() = Unit
