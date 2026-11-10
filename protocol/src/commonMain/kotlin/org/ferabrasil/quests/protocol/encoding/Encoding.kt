package org.ferabrasil.quests.protocol.encoding

import kotlin.io.encoding.Base64

// protocol.md §1: keys/signatures as lowercase hex, byte strings in JSON as base64url without padding.

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = HEX_DIGITS[v ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[v and 0x0f]
    }
    return out.concatToString()
}

/** Strict: lowercase only, even length. Throws [IllegalArgumentException] otherwise. */
fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex length must be even" }
    return ByteArray(length / 2) { i ->
        ((nibble(this[i * 2]) shl 4) or nibble(this[i * 2 + 1])).toByte()
    }
}

private fun nibble(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    else -> throw IllegalArgumentException("invalid lowercase hex character")
}

private val B64URL = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

fun ByteArray.toBase64Url(): String = B64URL.encode(this)

/** Rejects padding and non-url-safe alphabets. */
fun String.base64UrlToBytes(): ByteArray = B64URL.decode(this)
