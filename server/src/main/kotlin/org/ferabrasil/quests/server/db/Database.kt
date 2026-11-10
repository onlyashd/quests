package org.ferabrasil.quests.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ferabrasil.quests.server.config.DbConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import javax.sql.DataSource

/** Owns the connection pool and the Exposed handle. */
class Db(val dataSource: DataSource) {
    val exposed: Database = Database.connect(dataSource)

    /** Runs [block] in a transaction on the IO dispatcher. */
    suspend fun <T> tx(block: JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { transaction(exposed) { block() } }

    fun isHealthy(): Boolean = runCatching {
        dataSource.connection.use { it.isValid(2) }
    }.getOrDefault(false)

    companion object {
        fun pool(cfg: DbConfig): HikariDataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = cfg.url
                username = cfg.user
                password = cfg.password
                maximumPoolSize = cfg.maxPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                poolName = "quests"
            },
        )
    }
}

object Migrations {
    fun migrate(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
