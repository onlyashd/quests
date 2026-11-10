package org.ferabrasil.quests.server.config

import io.ktor.server.config.ApplicationConfig

data class AppConfig(
    val http: HttpConfig,
    val db: DbConfig,
    val s3: S3Config,
    val masterKey: String,
    val relayAllowlist: List<String>,
    val genesisAdminSetPath: String,
    val issuerName: String,
    val tunables: Tunables,
) {
    companion object {
        /** Loads `application.conf` from the classpath (env overrides applied by HOCON). */
        fun load(): AppConfig = from(ApplicationConfig("application.conf"))

        fun from(root: ApplicationConfig): AppConfig {
            val c = root.config("quests")
            val t = c.config("tunables")
            return AppConfig(
                http = HttpConfig(
                    port = c.int("http.port"),
                    internalPort = c.int("http.internal_port"),
                ),
                db = DbConfig(
                    url = c.string("db.url"),
                    user = c.string("db.user"),
                    password = c.string("db.password"),
                    maxPoolSize = c.int("db.max_pool_size"),
                ),
                s3 = S3Config(
                    endpoint = c.string("s3.endpoint"),
                    bucket = c.string("s3.bucket"),
                    accessKey = c.string("s3.access_key"),
                    secretKey = c.string("s3.secret_key"),
                ),
                masterKey = c.string("master_key"),
                relayAllowlist = c.string("relay_allowlist").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                genesisAdminSetPath = c.string("genesis_admin_set"),
                issuerName = c.string("issuer_name"),
                tunables = Tunables(
                    tokensPerEpoch = t.int("tokens_per_epoch"),
                    tokenEpochDays = t.int("token_epoch_days"),
                    claimTtlDays = t.int("claim_ttl_days"),
                    maxRewardUnits = t.int("max_reward_units"),
                    maxArtifactBytes = t.long("max_artifact_bytes"),
                    voucherEpochDays = t.int("voucher_epoch_days"),
                    statusPrefixBits = t.int("status_prefix_bits"),
                    artifactRetentionDays = t.int("artifact_retention_days"),
                    unreviewedRetentionDays = t.int("unreviewed_retention_days"),
                    terminalRetentionDays = t.int("terminal_retention_days"),
                    transferTimeoutDays = t.int("transfer_timeout_days"),
                ).also { it.validate() },
            )
        }
    }
}

data class HttpConfig(val port: Int, val internalPort: Int)

data class DbConfig(val url: String, val user: String, val password: String, val maxPoolSize: Int)

data class S3Config(val endpoint: String, val bucket: String, val accessKey: String, val secretKey: String)

data class Tunables(
    val tokensPerEpoch: Int,
    val tokenEpochDays: Int,
    val claimTtlDays: Int,
    val maxRewardUnits: Int,
    val maxArtifactBytes: Long,
    val voucherEpochDays: Int,
    val statusPrefixBits: Int,
    val artifactRetentionDays: Int,
    val unreviewedRetentionDays: Int,
    val terminalRetentionDays: Int,
    val transferTimeoutDays: Int,
) {
    fun validate() {
        require(tokensPerEpoch > 0) { "tokens_per_epoch must be > 0" }
        require(tokenEpochDays > 0) { "token_epoch_days must be > 0" }
        require(claimTtlDays > 0) { "claim_ttl_days must be > 0" }
        require(maxRewardUnits in 1..10_000) { "max_reward_units out of range" }
        // Matches the upload_sessions.declared_size CHECK in V1__init.sql.
        require(maxArtifactBytes in 1..MAX_ARTIFACT_BYTES_HARD_CAP) { "max_artifact_bytes out of range" }
        require(statusPrefixBits in 1..32) { "status_prefix_bits must be in 1..32" }
    }

    companion object {
        const val MAX_ARTIFACT_BYTES_HARD_CAP = 16L * 1024 * 1024
    }
}

private fun ApplicationConfig.string(path: String): String = property(path).getString()
private fun ApplicationConfig.int(path: String): Int = string(path).toInt()
private fun ApplicationConfig.long(path: String): Long = string(path).toLong()
