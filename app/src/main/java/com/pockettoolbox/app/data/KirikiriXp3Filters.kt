package com.pockettoolbox.app.data

import java.nio.charset.Charset

/*
 * Selected XP3 content filters are Kotlin adaptations of GARbro's
 * ArcFormats/KiriKiri/CryptAlgorithms.cs, Copyright (c) 2016-2017 morkt,
 * used under the MIT License. See THIRD_PARTY_NOTICES.md.
 */
internal data class Xp3FilterContext(
    val path: String,
    val hash: Long,
    val originalSize: Long,
)

internal interface Xp3ContentFilter {
    val id: String
    val displayName: String
    val hashAfterCrypt: Boolean get() = false

    fun decrypt(
        entry: Xp3FilterContext,
        offset: Long,
        data: ByteArray,
        start: Int,
        count: Int,
    )
}

private class LambdaXp3Filter(
    override val id: String,
    override val displayName: String,
    override val hashAfterCrypt: Boolean = false,
    private val operation: (Xp3FilterContext, Long, ByteArray, Int, Int) -> Unit,
) : Xp3ContentFilter {
    override fun decrypt(entry: Xp3FilterContext, offset: Long, data: ByteArray, start: Int, count: Int) {
        operation(entry, offset, data, start, count)
    }
}

internal object KirikiriXp3Filters {
    val automatic: List<Xp3ContentFilter> = buildList {
        add(filter("none", "仅设置保护标记") { _, _, _, _, _ -> })
        add(fate())
        add(mizukake())
        add(filter("hash", "GARbro HashCrypt") { entry, _, data, start, count ->
            xorRange(data, start, count, entry.hash.toInt())
        })
        add(filter("flying_shine", "GARbro FlyingShine") { entry, _, data, start, count ->
            val hash = entry.hash.u32()
            val shift = (hash.toInt() and 0xFF).takeUnless { it == 0 } ?: 0x0F
            val key = ((hash ushr 8).toInt() and 0xFF).takeUnless { it == 0 } ?: 0xF0
            repeat(count) { index ->
                val position = start + index
                data[position] = rotateByteRight(data[position].u8() xor key, shift).toByte()
            }
        })
        add(filter("seiten", "GARbro Seiten") { entry, offset, data, start, count ->
            repeat(count) { index ->
                val key = (entry.hash.u32() xor (offset + index).u32()).u32()
                var value = data[start + index].u8()
                if (key and 2L != 0L) {
                    val shift = key.toInt() and 0x18
                    value = value xor (((key ushr shift) or (key ushr (shift and 8))).toInt() and 0xFF)
                }
                if (key and 4L != 0L) value = (value + key.toInt()) and 0xFF
                if (key and 8L != 0L) value = (value - (key ushr (key.toInt() and 0x10)).toInt()) and 0xFF
                data[start + index] = value.toByte()
            }
        })
        add(filter("okiba", "GARbro Okiba") { entry, offset, data, start, count ->
            val hash = entry.hash.u32()
            val swapped = ((hash and 0x00FF0000L) shl 8) or
                ((hash and 0xFF000000L) ushr 8) or
                ((hash and 0x0000FF00L) ushr 8) or
                ((hash and 0x000000FFL) shl 8)
            repeat(count) { index ->
                val absolute = offset + index
                val key = if (absolute < 0x65L) {
                    (hash ushr 4).toInt()
                } else {
                    (swapped ushr (8 * ((absolute - 0x65L).toInt() and 3))).toInt()
                }
                data[start + index] = (data[start + index].u8() xor key).toByte()
            }
        })
        add(filter("dieselmine", "GARbro Dieselmine") { entry, offset, data, start, count ->
            val hashByte = entry.hash.toInt() and 0xFF
            repeat(count) { index ->
                val absolute = offset + index
                val value = data[start + index].u8()
                data[start + index] = when {
                    absolute < 123L -> value xor (21 * hashByte)
                    absolute < 246L -> value + (-32 * hashByte)
                    absolute < 369L -> value xor (43 * hashByte)
                    else -> value + (-54 * hashByte)
                }.toByte()
            }
        })
        add(filter("damegane", "GARbro Damegane") { entry, offset, data, start, count ->
            repeat(count) { index ->
                val absolute = offset + index
                val key = if (absolute and 1L != 0L) entry.hash.toInt() else absolute.toInt()
                data[start + index] = (data[start + index].u8() xor key).toByte()
            }
        })
        add(filter("nephrite", "GARbro Nephrite") { entry, offset, data, start, count ->
            repeat(count) { index ->
                val absolute = offset + index
                val key = if (absolute and 1L != 0L) absolute.toInt() else entry.hash.toInt()
                data[start + index] = (data[start + index].u8() xor key).toByte()
            }
        })
        add(filter("natsupochi", "GARbro Natsupochi") { entry, _, data, start, count ->
            xorRange(data, start, count, (entry.hash.u32() ushr 3).toInt())
        })
        add(filter("poring_soft", "GARbro PoringSoft") { entry, _, data, start, count ->
            xorRange(data, start, count, (entry.hash.u32() + 1L).inv().toInt())
        })
        add(filter("applique", "GARbro Applique") { entry, offset, data, start, count ->
            val skip = if (offset < 5L) minOf(count, (5L - offset).toInt()) else 0
            xorRange(data, start + skip, count - skip, (entry.hash.u32() ushr 12).toInt())
        })
        add(tokidoki())
        add(filter("sourire", "GARbro Sourire") { entry, _, data, start, count ->
            xorRange(data, start, count, entry.hash.toInt() xor 0xCD)
        })
        add(filter("hibiki", "GARbro Hibiki") { entry, offset, data, start, count ->
            val key1 = (entry.hash.u32() ushr 5).toInt()
            val key2 = (entry.hash.u32() ushr 8).toInt()
            repeat(count) { index ->
                val absolute = offset + index
                val key = if (absolute and 4L != 0L || absolute <= 0x64L) key1 else key2
                data[start + index] = (data[start + index].u8() xor key).toByte()
            }
        })
        add(filter("haikuo", "GARbro Haikuo") { entry, _, data, start, count ->
            val hash = entry.hash.u32()
            xorRange(data, start, count, (hash xor (hash ushr 8)).toInt())
        })
        add(filter("exa", "GARbro Exa") { entry, offset, data, start, count ->
            val hash = entry.hash.u32()
            repeat(count) { index ->
                val shift = ((offset + index) % 5L).toInt()
                data[start + index] = (data[start + index].u8() xor (hash ushr shift).toInt()).toByte()
            }
        })
        add(filter("yuzu", "GARbro Yuzu") { entry, _, data, start, count ->
            val hash = entry.hash.u32() xor 0x1DDB6E7AL
            var key = (hash xor (hash ushr 8) xor (hash ushr 16) xor (hash ushr 24)).toInt() and 0xFF
            if (key == 0) key = 0xD0
            xorRange(data, start, count, key)
        })
        add(filter("high_running", "GARbro HighRunning") { entry, offset, data, start, count ->
            val key = entry.hash.toInt() and 0xFF
            if (key != 0) repeat(count) { index ->
                if ((offset + index) % key != 0L) {
                    data[start + index] = (data[start + index].u8() xor key).toByte()
                }
            }
        })
        add(puca())
        add(filter("festival", "GARbro Festival") { entry, _, data, start, count ->
            xorRange(data, start, count, (entry.hash.u32() ushr 7).inv().toInt())
        })
        add(filter("pin_point", "GARbro PinPoint") { _, _, data, start, count ->
            repeat(count) { index ->
                val position = start + index
                val value = data[position].u8()
                data[position] = rotateByteLeft(value, Integer.bitCount(value)).toByte()
            }
        })
        add(filter("hybrid", "GARbro Hybrid") { entry, _, data, start, count ->
            xorRange(data, start, count, (entry.hash.u32() ushr 5).toInt())
        })
    }

    fun fixedXor(key: Int): Xp3ContentFilter {
        val normalized = key and 0xFF
        val hex = normalized.toString(16).padStart(2, '0').uppercase()
        return filter("xor_${hex.lowercase()}", "固定 XOR 0x$hex") { _, _, data, start, count ->
            xorRange(data, start, count, normalized)
        }
    }

    private fun fate(): Xp3ContentFilter = filter("fate", "GARbro Fate", hashAfterCrypt = true) {
            _, offset, data, start, count ->
        xorRange(data, start, count, 0x36)
        xorAt(data, start, count, offset, 0x13L, 1)
        xorAt(data, start, count, offset, 0x2EA29L, 3)
    }

    private fun mizukake(): Xp3ContentFilter = filter("mizukake", "GARbro Mizukake", hashAfterCrypt = true) {
            _, offset, data, start, count ->
        alterAt(data, start, count, offset, 0x103L) { it - 1 }
        xorRange(data, start, count, 0xB6)
        xorAt(data, start, count, offset, 0x3F82L, 1)
        xorAt(data, start, count, offset, 0x83L, 3)
    }

    private fun tokidoki(): Xp3ContentFilter = filter("tokidoki", "GARbro Tokidoki", hashAfterCrypt = true) {
            entry, offset, data, start, count ->
        val extension = entry.path.substringAfterLast('.', "").lowercase().takeIf(String::isNotBlank)?.let { ".$it" }
        val keyBytes = ByteArray(4)
        extension?.let { value ->
            val encoded = value.toByteArray(SHIFT_JIS)
            encoded.copyInto(keyBytes, endIndex = minOf(keyBytes.size, encoded.size))
        }
        val key = (keyBytes[0].u8().toLong() or
            (keyBytes[1].u8().toLong() shl 8) or
            (keyBytes[2].u8().toLong() shl 16) or
            (keyBytes[3].u8().toLong() shl 24)).inv().u32()
        val limit = if (extension in setOf(".asd", ".tjs", ".ks")) entry.originalSize else minOf(entry.originalSize, 0x100L)
        val decryptCount = minOf(count.toLong(), (limit - offset).coerceAtLeast(0L)).toInt()
        repeat(decryptCount) { index ->
            val shift = (((offset + index).toInt() and 3) shl 3)
            data[start + index] = (data[start + index].u8() xor (key ushr shift).toInt()).toByte()
        }
    }

    private fun puca(): Xp3ContentFilter = filter("puca", "GARbro PuCa") { entry, offset, data, start, count ->
        val hashTable = ByteArray(32)
        var hash = entry.hash.u32()
        for (index in 0 until 32 step 4) {
            hash = if (hash and 1L != 0L) hash or 0x80000000L else hash and 0x7FFFFFFFL
            repeat(4) { byteIndex -> hashTable[index + byteIndex] = (hash ushr (byteIndex * 8)).toByte() }
            hash = hash ushr 1
        }
        val keyTable = ByteArray(0x400)
        for (left in 0 until 32) for (right in 0 until 32) {
            keyTable[left * 32 + right] = (hashTable[left].u8().inv() xor hashTable[right].u8()).toByte()
        }
        repeat(count) { index ->
            val key = keyTable[((offset + index) and 0x3FFL).toInt()].u8()
            data[start + index] = (data[start + index].u8() xor key).toByte()
        }
    }

    private fun filter(
        id: String,
        displayName: String,
        hashAfterCrypt: Boolean = false,
        operation: (Xp3FilterContext, Long, ByteArray, Int, Int) -> Unit,
    ): Xp3ContentFilter = LambdaXp3Filter(id, displayName, hashAfterCrypt, operation)

    private fun xorRange(data: ByteArray, start: Int, count: Int, key: Int) {
        repeat(count) { index -> data[start + index] = (data[start + index].u8() xor key).toByte() }
    }

    private fun xorAt(data: ByteArray, start: Int, count: Int, offset: Long, target: Long, key: Int) {
        alterAt(data, start, count, offset, target) { it xor key }
    }

    private fun alterAt(
        data: ByteArray,
        start: Int,
        count: Int,
        offset: Long,
        target: Long,
        operation: (Int) -> Int,
    ) {
        if (target in offset until offset + count) {
            val position = start + (target - offset).toInt()
            data[position] = operation(data[position].u8()).toByte()
        }
    }

    private fun rotateByteLeft(value: Int, shift: Int): Int {
        val bits = shift and 7
        return ((value shl bits) or (value ushr (8 - bits))) and 0xFF
    }

    private fun rotateByteRight(value: Int, shift: Int): Int {
        val bits = shift and 7
        return ((value ushr bits) or (value shl (8 - bits))) and 0xFF
    }

    private fun Byte.u8(): Int = toInt() and 0xFF
    private fun Long.u32(): Long = this and 0xFFFF_FFFFL

    private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
}
