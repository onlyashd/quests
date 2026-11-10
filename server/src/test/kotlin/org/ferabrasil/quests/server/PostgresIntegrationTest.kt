package org.ferabrasil.quests.server

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.connector
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.ferabrasil.quests.server.config.AppConfig
import org.ferabrasil.quests.server.config.DbConfig
import org.ferabrasil.quests.server.config.S3Config
import org.ferabrasil.quests.server.db.Db
import org.ferabrasil.quests.server.db.Migrations
import org.ferabrasil.quests.server.jobs.BackgroundJob
import org.ferabrasil.quests.server.jobs.JobRunner
import org.ferabrasil.quests.server.service.MinioArtifactStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** B0 exit criterion: `./gradlew :server:test` green against Testcontainers. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresIntegrationTest {
    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        // Official minio/minio images left Docker Hub in 2025; override with MINIO_IMAGE.
        @Container @JvmStatic
        val minio = MinIOContainer(
            DockerImageName.parse(System.getenv("MINIO_IMAGE") ?: DEFAULT_MINIO_IMAGE)
                .asCompatibleSubstituteFor("minio/minio"),
        )

        const val DEFAULT_MINIO_IMAGE = "pgsty/minio:RELEASE.2026-08-04T00-00-00Z"
    }

    private lateinit var pool: HikariDataSource

    @BeforeAll
    fun setUp() {
        pool = Db.pool(DbConfig(postgres.jdbcUrl, postgres.username, postgres.password, maxPoolSize = 4))
        Migrations.migrate(pool)
    }

    @AfterAll
    fun tearDown() = pool.close()

    @Test
    fun migrationCreatesSchema() {
        val tables = query("SELECT tablename FROM pg_tables WHERE schemaname = 'public'")
        val expected = setOf(
            "admin_sets", "leaders", "quest_transfers", "leader_voucher_keys", "quests", "quest_claims",
            "quest_submissions", "upload_sessions", "issuer_keys", "spent_tokens", "enrollments",
            "issuance_quota", "invites", "request_replay", "admin_audit",
        )
        assertTrue(tables.containsAll(expected), "missing: ${expected - tables.toSet()}")
    }

    @Test
    fun adminAuditIsAppendOnly() {
        exec("INSERT INTO admin_audit (action, request_hash, signer_pubkeys) VALUES ('test', '\\x00', '{a}')")
        assertFailsWith<SQLException> { exec("DELETE FROM admin_audit") }
        assertFailsWith<SQLException> { exec("UPDATE admin_audit SET action = 'x'") }
    }

    @Test
    fun advisoryLockAllowsOneRunner() = runBlocking {
        var runs = 0
        val job = object : BackgroundJob {
            override val name = "test-job"
            override val interval = 5.minutes
            override suspend fun run() { runs++ }
        }
        val runner = JobRunner(pool, listOf(job))

        pool.connection.use { holder ->
            holder.autoCommit = true
            holder.createStatement().use { it.execute("SELECT pg_advisory_lock(${JobRunner.lockKey(job.name)})") }
            assertFalse(runner.runOnce(job), "must not run while another session holds the lock")
        }
        assertTrue(runner.runOnce(job))
        assertEquals(1, runs)
    }

    @Test
    fun healthReportsDbAndStorage() = testApplication {
        val base = AppConfig.load()
        val config = base.copy(s3 = S3Config(minio.s3URL, "artifacts", minio.userName, minio.password))
        val storage = MinioArtifactStore(config.s3).also { it.ensureReady() }
        engine { connector { host = "localhost"; port = config.http.internalPort } }
        application { module(ServerDeps(config, Db(pool), storage, JobRunner(pool, emptyList()))) }

        val res = client.get("http://localhost:${config.http.internalPort}/healthz")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"db":true,"storage":true}""", res.bodyAsText())
    }

    private fun exec(sql: String) = pool.connection.use { c ->
        c.autoCommit = true
        c.createStatement().use { it.execute(sql) }
    }

    private fun query(sql: String): List<String> = pool.connection.use { c ->
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }
}
