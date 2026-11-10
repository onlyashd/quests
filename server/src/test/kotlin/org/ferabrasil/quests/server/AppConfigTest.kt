package org.ferabrasil.quests.server

import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.config.ApplicationConfig
import org.ferabrasil.quests.server.config.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppConfigTest {
    @Test
    fun defaultsMatchSpec() {
        val c = AppConfig.load()
        assertEquals(8080, c.http.port)
        assertEquals(9090, c.http.internalPort)
        assertEquals("quests.fera", c.issuerName)
        assertEquals(emptyList(), c.relayAllowlist)
        with(c.tunables) {
            assertEquals(10, tokensPerEpoch)
            assertEquals(7, tokenEpochDays)
            assertEquals(7, claimTtlDays)
            assertEquals(100, maxRewardUnits)
            assertEquals(16_777_216L, maxArtifactBytes)
            assertEquals(30, voucherEpochDays)
            assertEquals(12, statusPrefixBits)
            assertEquals(7, artifactRetentionDays)
            assertEquals(60, unreviewedRetentionDays)
            assertEquals(30, terminalRetentionDays)
            assertEquals(14, transferTimeoutDays)
        }
    }

    @Test
    fun parsesRelayAllowlist() {
        val c = AppConfig.from(base().mergeWith(MapApplicationConfig("quests.relay_allowlist" to " 10.0.0.0/8, ,192.168.0.0/16")))
        assertEquals(listOf("10.0.0.0/8", "192.168.0.0/16"), c.relayAllowlist)
    }

    @Test
    fun rejectsOutOfRangeTunables() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig.from(base().mergeWith(MapApplicationConfig("quests.tunables.max_artifact_bytes" to "999999999")))
        }
        assertFailsWith<IllegalArgumentException> {
            AppConfig.from(base().mergeWith(MapApplicationConfig("quests.tunables.status_prefix_bits" to "0")))
        }
    }

    private fun base() = ApplicationConfig("application.conf")
}
