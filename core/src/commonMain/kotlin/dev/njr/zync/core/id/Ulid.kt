package dev.njr.zync.core.id

import dev.njr.zync.core.clock.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.random.Random

/**
 * A ULID — 48-bit millisecond timestamp + 80 bits of entropy, encoded as a
 * 26-char Crockford Base32 string that is **lexically sortable in timestamp
 * order**. Used for entity IDs and op IDs (the idempotency key).
 *
 * Generation takes an injected [Clock] and [Random] so it is deterministic under
 * test — merge logic never touches an ambient clock/RNG.
 */
@Serializable(with = UlidSerializer::class)
class Ulid private constructor(private val text: String) : Comparable<Ulid> {

    override fun toString(): String = text

    override fun compareTo(other: Ulid): Int = text.compareTo(other.text)

    override fun equals(other: Any?): Boolean = other is Ulid && other.text == text

    override fun hashCode(): Int = text.hashCode()

    /** The embedded 48-bit millisecond timestamp. */
    val timestampMillis: Long
        get() {
            val b = decode(text)
            return ((b[0].toLong() and 0xFF) shl 40) or
                ((b[1].toLong() and 0xFF) shl 32) or
                ((b[2].toLong() and 0xFF) shl 24) or
                ((b[3].toLong() and 0xFF) shl 16) or
                ((b[4].toLong() and 0xFF) shl 8) or
                (b[5].toLong() and 0xFF)
        }

    companion object {
        private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val LEN = 26

        fun generate(clock: Clock, random: Random): Ulid {
            val time = clock.nowMillis()
            require(time >= 0) { "ULID timestamp must be non-negative, was $time" }
            require(time <= 0xFFFF_FFFF_FFFFL) { "ULID timestamp exceeds 48 bits: $time" }
            val bytes = ByteArray(16)
            bytes[0] = (time ushr 40).toByte()
            bytes[1] = (time ushr 32).toByte()
            bytes[2] = (time ushr 24).toByte()
            bytes[3] = (time ushr 16).toByte()
            bytes[4] = (time ushr 8).toByte()
            bytes[5] = time.toByte()
            val entropy = ByteArray(10)
            random.nextBytes(entropy)
            entropy.copyInto(bytes, destinationOffset = 6)
            return Ulid(encode(bytes))
        }

        /** Parse a canonical or lenient (lowercase / I·L·O aliased) ULID string. */
        fun parse(text: String): Ulid {
            require(text.length == LEN) { "ULID must be $LEN chars, got ${text.length}" }
            // Round-trip through bytes to normalize to canonical uppercase form.
            return Ulid(encode(decode(text)))
        }

        // --- Crockford Base32 over a 128-bit value (2 leading zero pad bits → 26 chars) ---

        private fun encode(bytes: ByteArray): String {
            require(bytes.size == 16)
            val chars = CharArray(LEN)
            var acc = 0
            var bits = 2 // two zero pad bits so 2 + 128 = 130 = 26 * 5
            var idx = 0
            for (b in bytes) {
                acc = (acc shl 8) or (b.toInt() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    bits -= 5
                    chars[idx++] = CROCKFORD[(acc ushr bits) and 0x1F]
                    acc = acc and ((1 shl bits) - 1)
                }
            }
            return chars.concatToString()
        }

        private fun decode(text: String): ByteArray {
            val out = ByteArray(16)
            var acc = 0
            var bits = 0
            var pos = 0
            for (i in 0 until LEN) {
                val v = decodeChar(text[i])
                if (i == 0) {
                    require(v <= 0x07) { "ULID overflow: leading char '${text[i]}'" }
                    acc = v and 0x07
                    bits = 3 // drop the 2 high pad bits from the first 5-bit group
                } else {
                    acc = (acc shl 5) or v
                    bits += 5
                }
                while (bits >= 8) {
                    bits -= 8
                    out[pos++] = ((acc ushr bits) and 0xFF).toByte()
                    acc = acc and ((1 shl bits) - 1)
                }
            }
            return out
        }

        private fun decodeChar(c: Char): Int {
            val u = c.uppercaseChar()
            return when (u) {
                in '0'..'9' -> u - '0'
                'O' -> 0
                'I', 'L' -> 1
                else -> {
                    val idx = CROCKFORD.indexOf(u)
                    require(idx >= 0) { "Invalid ULID character: '$c'" }
                    idx
                }
            }
        }
    }
}

/** Serializes [Ulid] as its canonical string form. */
object UlidSerializer : KSerializer<Ulid> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("dev.njr.zync.core.id.Ulid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Ulid) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Ulid = Ulid.parse(decoder.decodeString())
}
