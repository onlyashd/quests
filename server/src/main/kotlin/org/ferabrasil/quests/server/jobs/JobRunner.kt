package org.ferabrasil.quests.server.jobs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Duration

/** A periodic background job (backend.md §7). */
interface BackgroundJob {
    /** Stable, unique per job: hashed into the Postgres advisory-lock key. */
    val name: String
    val interval: Duration

    /** Runs with the advisory lock held. Must be idempotent. */
    suspend fun run()
}

/**
 * Runs each job on every replica's schedule, but only the replica that wins
 * `pg_try_advisory_lock` executes it (backend.md §7). The lock is session-scoped,
 * so the connection is held for the run and the lock released explicitly.
 */
class JobRunner(private val dataSource: DataSource, private val jobs: List<BackgroundJob>) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = LoggerFactory.getLogger(JobRunner::class.java)

    init {
        val keys = jobs.map { lockKey(it.name) }
        require(keys.toSet().size == keys.size) { "job names must be unique" }
    }

    fun start(): List<Job> = jobs.map { job ->
        scope.launch {
            while (isActive) {
                runOnce(job)
                delay(job.interval)
            }
        }
    }

    fun stop() = scope.cancel()

    /** Returns true if this replica held the lock and ran the job. */
    suspend fun runOnce(job: BackgroundJob): Boolean {
        val key = lockKey(job.name)
        dataSource.connection.use { conn ->
            conn.autoCommit = true
            if (!conn.tryLock(key)) return false
            try {
                job.run()
            } catch (e: Exception) {
                log.error("job {} failed: {}", job.name, e::class.qualifiedName)
            } finally {
                conn.unlock(key)
            }
            return true
        }
    }

    private fun Connection.tryLock(key: Long): Boolean =
        prepareStatement("SELECT pg_try_advisory_lock(?)").use { st ->
            st.setLong(1, key)
            st.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
        }

    private fun Connection.unlock(key: Long) {
        prepareStatement("SELECT pg_advisory_unlock(?)").use { st ->
            st.setLong(1, key)
            st.execute()
        }
    }

    companion object {
        /** Namespaced so it can't collide with other advisory-lock users. */
        fun lockKey(name: String): Long = (0x4151_4500L shl 32) or (name.hashCode().toLong() and 0xFFFF_FFFFL)
    }
}
