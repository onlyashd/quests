package org.ferabrasil.quests.server.admin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import org.ferabrasil.quests.server.config.AppConfig
import org.ferabrasil.quests.server.db.Db
import org.ferabrasil.quests.server.db.Migrations

/**
 * `java -jar server.jar admin …` (backend.md §5.7). There is no network admin API.
 * propose/sign/apply, invite mint and key rotation land in B1/B2.
 */
class AdminCli : CliktCommand(name = "admin") {
    init {
        subcommands(MigrateCommand())
    }

    override fun run() = Unit
}

private class MigrateCommand : CliktCommand(name = "migrate") {
    override fun run() {
        Db.pool(AppConfig.load().db).use { Migrations.migrate(it) }
        echo("migrations applied")
    }
}
