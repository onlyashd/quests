package org.ferabrasil.quests.redeemer

import io.ktor.server.config.ApplicationConfig

data class RedeemerConfig(
    val port: Int,
    val internalPort: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val dbMaxPoolSize: Int,
    val serverUrl: String,
    val genesisAdminSetPath: String,
    val voucherAcceptGraceDays: Int,
    val spendLockMinutes: Int,
    val maxVouchersPerSpend: Int,
) {
    companion object {
        fun load(): RedeemerConfig = from(ApplicationConfig("application.conf"))

        fun from(root: ApplicationConfig): RedeemerConfig {
            val c = root.config("redeemer")
            fun s(p: String) = c.property(p).getString()
            return RedeemerConfig(
                port = s("http.port").toInt(),
                internalPort = s("http.internal_port").toInt(),
                dbUrl = s("db.url"),
                dbUser = s("db.user"),
                dbPassword = s("db.password"),
                dbMaxPoolSize = s("db.max_pool_size").toInt(),
                serverUrl = s("server_url"),
                genesisAdminSetPath = s("genesis_admin_set"),
                voucherAcceptGraceDays = s("tunables.voucher_accept_grace_days").toInt(),
                spendLockMinutes = s("tunables.spend_lock_minutes").toInt(),
                maxVouchersPerSpend = s("tunables.max_vouchers_per_spend").toInt(),
            ).also {
                require(it.spendLockMinutes > 0) { "spend_lock_minutes must be > 0" }
                require(it.maxVouchersPerSpend in 1..500) { "max_vouchers_per_spend must be in 1..500" }
            }
        }
    }
}
