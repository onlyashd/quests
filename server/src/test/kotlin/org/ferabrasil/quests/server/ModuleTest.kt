package org.ferabrasil.quests.server

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.connector
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import org.ferabrasil.quests.protocol.ProtocolJson
import org.ferabrasil.quests.protocol.model.AdminSet
import org.ferabrasil.quests.protocol.model.ErrorCode
import org.ferabrasil.quests.server.config.AppConfig
import org.ferabrasil.quests.server.db.Db
import org.ferabrasil.quests.server.jobs.JobRunner
import org.ferabrasil.quests.server.routes.ApiException
import org.ferabrasil.quests.server.routes.configureErrors
import org.ferabrasil.quests.server.service.ArtifactStore
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Runs without Docker: DB and storage are faked. */
class ModuleTest {
    @Test
    fun errorsUseSpecShape() = testApplication {
        application {
            install(ContentNegotiation) { json(ProtocolJson) }
            configureErrors()
            routing {
                get("/gone") { throw ApiException(ErrorCode.QUEST_UNAVAILABLE, "paused") }
                get("/boom") { error("secret 0123456789abcdef") }
                post("/echo") { call.receive<AdminSet>() }
            }
        }

        client.get("/gone").let {
            assertEquals(HttpStatusCode.Gone, it.status)
            assertEquals("""{"error":"quest_unavailable","message":"paused"}""", it.bodyAsText())
        }
        client.get("/boom").let {
            assertEquals(HttpStatusCode.InternalServerError, it.status)
            assertEquals("""{"error":"internal"}""", it.bodyAsText())
        }
        client.get("/nope").let {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals("""{"error":"not_found"}""", it.bodyAsText())
        }
        client.post("/echo") {
            contentType(ContentType.Application.Json)
            setBody("""{"version":"not-a-number"}""")
        }.let {
            assertEquals(HttpStatusCode.BadRequest, it.status)
            assertFalse("not-a-number" in it.bodyAsText(), "request content must not be echoed")
        }
    }

    @Test
    fun healthIsOnlyOnInternalPort() = testApplication {
        val config = AppConfig.load()
        val brokenDb = mockk<DataSource> { every { connection } throws SQLException("down") }
        val storage = object : ArtifactStore {
            override fun ensureReady() = Unit
            override fun isHealthy() = true
        }
        engine {
            connector { host = "localhost"; port = config.http.port }
            connector { host = "localhost"; port = config.http.internalPort }
        }
        application { module(ServerDeps(config, Db(brokenDb), storage, JobRunner(brokenDb, emptyList()))) }

        client.get("http://localhost:${config.http.internalPort}/healthz").let {
            assertEquals(HttpStatusCode.ServiceUnavailable, it.status)
            assertEquals("""{"db":false,"storage":true}""", it.bodyAsText())
        }
        assertEquals(HttpStatusCode.NotFound, client.get("http://localhost:${config.http.port}/healthz").status)
    }
}
