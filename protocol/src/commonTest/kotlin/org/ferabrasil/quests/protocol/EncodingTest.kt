package org.ferabrasil.quests.protocol

import org.ferabrasil.quests.protocol.encoding.SigInput
import org.ferabrasil.quests.protocol.encoding.base64UrlToBytes
import org.ferabrasil.quests.protocol.encoding.hexToBytes
import org.ferabrasil.quests.protocol.encoding.toBase64Url
import org.ferabrasil.quests.protocol.encoding.toHex
import org.ferabrasil.quests.protocol.model.AdminSignature
import org.ferabrasil.quests.protocol.model.QuestDto
import org.ferabrasil.quests.protocol.model.QuestStatus
import org.ferabrasil.quests.protocol.model.VerificationMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class EncodingTest {
    @Test
    fun hexRoundTrip() {
        val bytes = byteArrayOf(0x00, 0x0f, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals("000f7f80ff", bytes.toHex())
        assertContentEquals(bytes, "000f7f80ff".hexToBytes())
    }

    @Test
    fun hexRejectsUppercaseAndOddLength() {
        assertFailsWith<IllegalArgumentException> { "0F".hexToBytes() }
        assertFailsWith<IllegalArgumentException> { "abc".hexToBytes() }
    }

    @Test
    fun base64UrlHasNoPadding() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte())
        assertEquals("-_8", bytes.toBase64Url())
        assertContentEquals(bytes, "-_8".base64UrlToBytes())
    }

    @Test
    fun sigInputLayout() {
        val actual = SigInput.of("claim").string("ab").u32(1).i64(-1).build()
        val expected = "AQE-v1".encodeToByteArray().toHex() + "00" +
            "00000005" + "claim".encodeToByteArray().toHex() +
            "00000002" + "6162" +
            "00000004" + "00000001" +
            "00000008" + "ffffffffffffffff"
        assertEquals(expected, actual.toHex())
    }

    @Test
    fun jsonUsesSnakeCaseAndOmitsNulls() {
        val quest = QuestDto(
            id = "8a1b2c3d-0000-4000-8000-000000000000",
            leaderPubkey = "aa", leaderEncPubkey = "bb", voucherKeyId = "cc",
            title = "t", description = "d", verificationMode = VerificationMode.MANUAL_REVIEW,
            secretPreimageHash = null, maxParticipants = null, rewardUnits = 1,
            status = QuestStatus.PUBLISHED, slotsRemaining = null, expiresAt = null, signature = "ss",
        )
        val json = ProtocolJson.encodeToString(QuestDto.serializer(), quest)
        assertFalse("secret_preimage_hash" in json)
        assertEquals(quest, ProtocolJson.decodeFromString(QuestDto.serializer(), json))
        assertEquals(
            """{"admin_pubkey":"aa","sig":"bb"}""",
            ProtocolJson.encodeToString(AdminSignature.serializer(), AdminSignature("aa", "bb")),
        )
    }
}
