package org.ferabrasil.quests.redeemer

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.connector
import io.ktor.server.testing.testApplication
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedeemerConfigTest {
    @Test
    fun defaultsMatchSpec() {
        val c = RedeemerConfig.load()
        assertEquals(90, c.voucherAcceptGraceDays)
        assertEquals(10, c.spendLockMinutes)
        assertEquals(500, c.maxVouchersPerSpend)
    }
}

/** Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class RedeemerIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Test
    fun migratesAndReportsHealthy() = testApplication {
        val config = RedeemerConfig.load().copy(
            dbUrl = postgres.jdbcUrl, dbUser = postgres.username, dbPassword = postgres.password,
        )
        val pool = pool(config)
        migrate(pool)

        val tables = pool.connection.use { c ->
            c.createStatement().executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")
                .use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
        assertTrue(tables.containsAll(setOf("voucher_keys", "spends", "voucher_states")))

        engine { connector { host = "localhost"; port = config.internalPort } }
        application { module(config, pool) }
        val res = client.get("http://localhost:${config.internalPort}/healthz")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"db":true}""", res.bodyAsText())
        pool.close()
    }
}
