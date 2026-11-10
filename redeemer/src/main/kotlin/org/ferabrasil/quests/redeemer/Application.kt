package org.ferabrasil.quests.redeemer

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.localPort
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.ferabrasil.quests.protocol.ProtocolJson
import org.ferabrasil.quests.protocol.model.ErrorCode
import org.ferabrasil.quests.protocol.model.ErrorDto
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource

fun main() {
    val config = RedeemerConfig.load()
    val pool = pool(config)
    migrate(pool)

    embeddedServer(
        Netty,
        configure = {
            connector { port = config.port }
            connector { port = config.internalPort }
        },
    ) {
        module(config, pool)
        monitor.subscribe(ApplicationStopped) { pool.close() }
    }.start(wait = true)
}

fun pool(config: RedeemerConfig): HikariDataSource = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = config.dbUrl
        username = config.dbUser
        password = config.dbPassword
        maximumPoolSize = config.dbMaxPoolSize
        isAutoCommit = false
        poolName = "redeemer"
    },
)

fun migrate(dataSource: DataSource) {
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
}

@Serializable
data class HealthDto(val db: Boolean)

private val log = LoggerFactory.getLogger("org.ferabrasil.quests.redeemer")

fun Application.module(config: RedeemerConfig, dataSource: DataSource) {
    // No CallLogging / access log (backend.md §9 S1).
    install(ContentNegotiation) { json(ProtocolJson) }
    install(StatusPages) {
        exception<Throwable> { call, e ->
            log.error("unhandled {}", e::class.qualifiedName)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto(ErrorCode.INTERNAL.wire))
        }
        status(HttpStatusCode.NotFound) { call, status -> call.respond(status, ErrorDto(ErrorCode.NOT_FOUND.wire)) }
    }

    routing {
        localPort(config.internalPort) {
            get("/healthz") {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { dataSource.connection.use { it.isValid(2) } }.getOrDefault(false)
                }
                call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable, HealthDto(ok))
            }
        }
        // Public port: OHTTP gateway + /v1/voucher-keys, /v1/spends… (backend.md §8) — milestone B6.
    }
}
