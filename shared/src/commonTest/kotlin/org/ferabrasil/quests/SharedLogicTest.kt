package org.ferabrasil.quests

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.ferabrasil.quests.domain.identity.IdentityRepository
import org.ferabrasil.quests.domain.identity.IdentityState
import org.ferabrasil.quests.domain.net.ApiFailure
import org.ferabrasil.quests.domain.preferences.PreferencesRepository
import org.ferabrasil.quests.domain.preferences.ThemeMode
import org.ferabrasil.quests.domain.quests.ClaimBlocker
import org.ferabrasil.quests.domain.quests.ClaimEligibility
import org.ferabrasil.quests.domain.quests.QuestFilter
import org.ferabrasil.quests.domain.quests.QuestRules
import org.ferabrasil.quests.domain.quests.QuestSearch
import org.ferabrasil.quests.domain.sync.SyncTiming
import org.ferabrasil.quests.platform.SecretStore
import org.ferabrasil.quests.protocol.model.ErrorDto
import org.ferabrasil.quests.protocol.model.QuestDto
import org.ferabrasil.quests.protocol.model.QuestStatus
import org.ferabrasil.quests.protocol.model.VerificationMode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private class FakeSecretStore : SecretStore {
    val map = mutableMapOf<String, ByteArray>()
    override fun get(name: String) = map[name]?.copyOf()
    override fun put(name: String, value: ByteArray) { map[name] = value.copyOf() }
    override fun delete(name: String) { map.remove(name) }
}

private fun quest(
    status: QuestStatus = QuestStatus.PUBLISHED,
    slots: Int? = null,
    expiresAt: Long? = null,
    title: String = "Plantar uma árvore",
    description: String = "Missão na praça",
    mode: VerificationMode = VerificationMode.MANUAL_REVIEW,
) = QuestDto(
    id = "00000000-0000-4000-8000-000000000001", leaderPubkey = "aa", leaderEncPubkey = "bb",
    voucherKeyId = "cc", title = title, description = description, verificationMode = mode,
    secretPreimageHash = null, maxParticipants = null, rewardUnits = 1, status = status,
    slotsRemaining = slots, expiresAt = expiresAt, signature = "ss",
)

class IdentityRepositoryTest {
    @Test
    fun createLoadWipe() = runTest {
        val store = FakeSecretStore()
        val repo = IdentityRepository(store, StandardTestDispatcher(testScheduler))
        assertEquals(IdentityState.LOADING, repo.state.value)

        repo.load()
        assertEquals(IdentityState.ABSENT, repo.state.value)

        repo.create()
        assertEquals(IdentityState.PRESENT, repo.state.value)
        assertEquals(32, store.map[IdentityRepository.ENTROPY_KEY]?.size)
        assertFailsWith<IllegalStateException> { repo.create() }

        val second = IdentityRepository(store, StandardTestDispatcher(testScheduler))
        second.load()
        assertEquals(IdentityState.PRESENT, second.state.value)

        repo.wipe()
        assertEquals(IdentityState.ABSENT, repo.state.value)
        assertNull(store.map[IdentityRepository.ENTROPY_KEY])
    }
}

class PreferencesRepositoryTest {
    @Test
    fun persistsAndClears() {
        val settings = MapSettings()
        PreferencesRepository(settings).apply {
            setTheme(ThemeMode.DARK)
            setLeaderMode(true)
        }
        val reloaded = PreferencesRepository(settings)
        assertEquals(ThemeMode.DARK, reloaded.state.value.theme)
        assertTrue(reloaded.state.value.leaderMode)
        reloaded.clear()
        assertEquals(ThemeMode.SYSTEM, reloaded.state.value.theme)
    }
}

class QuestRulesTest {
    private fun eligibility(q: QuestDto, enrolled: Boolean = true, tokens: Int = 3, claimed: Boolean = false) =
        QuestRules.claimEligibility(q, nowEpochSeconds = 1_000, enrolled = enrolled, tokensRemaining = tokens, alreadyClaimed = claimed)

    @Test
    fun blockers() {
        assertEquals(ClaimEligibility.Eligible, eligibility(quest()))
        assertEquals(ClaimEligibility.Blocked(ClaimBlocker.NOT_ENROLLED), eligibility(quest(), enrolled = false))
        assertEquals(ClaimEligibility.Blocked(ClaimBlocker.NOT_PUBLISHED), eligibility(quest(QuestStatus.PAUSED)))
        assertEquals(ClaimEligibility.Blocked(ClaimBlocker.EXPIRED), eligibility(quest(expiresAt = 1_000)))
        assertEquals(ClaimEligibility.Blocked(ClaimBlocker.NO_SLOTS), eligibility(quest(slots = 0)))
        assertEquals(ClaimEligibility.Blocked(ClaimBlocker.NO_TOKENS), eligibility(quest(), tokens = 0))
        assertEquals(ClaimEligibility.Blocked(ClaimBlocker.ALREADY_CLAIMED), eligibility(quest(), claimed = true))
    }

    @Test
    fun searchIsAccentAndCaseInsensitive() {
        val quests = listOf(quest(), quest(title = "Limpar a praia", description = "Coleta"))
        assertEquals(1, QuestSearch.apply(quests, QuestFilter(query = "  MISSAO   arvore "), 0).size)
        assertEquals(2, QuestSearch.apply(quests, QuestFilter(), 0).size)
        assertEquals(0, QuestSearch.apply(quests, QuestFilter(mode = VerificationMode.SECRET_PREIMAGE), 0).size)
        val closed = listOf(quest(slots = 0), quest(QuestStatus.PAUSED), quest(expiresAt = 5))
        assertEquals(0, QuestSearch.apply(closed, QuestFilter(claimableOnly = true), 10).size)
    }
}

class ApiFailureTest {
    @Test
    fun mapsSpecTable() {
        assertEquals(ApiFailure.ALREADY_DONE, ApiFailure.from(409, ErrorDto("already_claimed")))
        assertEquals(ApiFailure.UNEXPECTED, ApiFailure.from(409, ErrorDto("chunk_out_of_order")))
        assertEquals(ApiFailure.RESYNC_CLOCK_AND_RETRY, ApiFailure.from(401, ErrorDto("bad_auth")))
        assertEquals(ApiFailure.NO_TOKENS, ApiFailure.from(402, ErrorDto("token_spent")))
        assertEquals(ApiFailure.NO_TOKENS, ApiFailure.from(429, null))
        assertEquals(ApiFailure.QUEST_GONE, ApiFailure.from(410, null))
        assertEquals(ApiFailure.UNEXPECTED, ApiFailure.from(500, null))
    }
}

class SyncTimingTest {
    private val timing = SyncTiming(Random(42))

    @Test
    fun windowsMatchSpec() {
        repeat(1_000) {
            assertTrue(timing.nextStatusSync() in 30.minutes..90.minutes)
            assertTrue(timing.gapBetweenClaimPolls() in 2.seconds..20.seconds)
            assertTrue(timing.tokenRefillOffset() < 1.days)
            assertTrue(timing.backoff(it) <= 5.minutes)
        }
        assertTrue(timing.backoff(0) <= 1.seconds)
        assertTrue(timing.shouldStillPoll(null))
        assertTrue(timing.shouldStillPoll(13.days))
        assertTrue(!timing.shouldStillPoll(14.days))
    }
}
