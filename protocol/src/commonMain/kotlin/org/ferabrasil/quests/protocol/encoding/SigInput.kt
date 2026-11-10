package org.ferabrasil.quests.protocol.encoding

/**
 * protocol.md §4:
 * `SigInput(context, fields...) = "AQE-v1" ‖ 0x00 ‖ lp(context) ‖ lp(field_1) ‖ … ‖ lp(field_n)`
 * where `lp(x) = u32be(len(x)) ‖ x`. Integers are unsigned big-endian.
 */
class SigInput private constructor(context: String) {
    private val buf = ByteBuffer()

    init {
        buf.write(PREFIX)
        buf.write(0x00)
        lp(context.encodeToByteArray())
    }

    fun bytes(field: ByteArray): SigInput = apply { lp(field) }

    fun string(field: String): SigInput = apply { lp(field.encodeToByteArray()) }

    /** `lp(u32be(v))` — used for `u32(...)` fields. */
    fun u32(v: Long): SigInput = apply {
        require(v in 0..0xFFFF_FFFFL) { "u32 out of range" }
        lp(beBytes(v, 4))
    }

    fun u32(v: Int): SigInput = u32(v.toLong())

    /** `lp(i64be(v))`. */
    fun i64(v: Long): SigInput = apply { lp(beBytes(v, 8)) }

    /** `lp(u64be(v))` — same bytes as i64 for non-negative values. */
    fun u64(v: Long): SigInput = apply {
        require(v >= 0) { "u64 must be non-negative" }
        lp(beBytes(v, 8))
    }

    fun build(): ByteArray = buf.toByteArray()

    private fun lp(x: ByteArray) {
        buf.write(beBytes(x.size.toLong(), 4))
        buf.write(x)
    }

    companion object {
        const val VERSION_TAG = "AQE-v1"
        private val PREFIX = VERSION_TAG.encodeToByteArray()

        fun of(context: String): SigInput = SigInput(context)
    }
}

internal fun beBytes(v: Long, width: Int): ByteArray =
    ByteArray(width) { i -> (v ushr (8 * (width - 1 - i))).toByte() }

/** Minimal growable byte buffer for common code. */
internal class ByteBuffer(initial: Int = 128) {
    private var data = ByteArray(initial)
    private var size = 0

    fun write(b: Int) {
        ensure(1)
        data[size++] = b.toByte()
    }

    fun write(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(data, size)
        size += bytes.size
    }

    fun toByteArray(): ByteArray = data.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }
}
