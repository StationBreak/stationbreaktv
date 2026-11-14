// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

// Studio fold 2 (ADR-0026, "The sets" and "The pass"). Every table below is a fixed list of code points copied from
// the ADR, never the platform's idea of whitespace, control, format, letter or case, so every TV (API 25 and API 30
// alike) gives the same key. The tables are frozen: a change is a new fold version.

private const val ZWNJ = 0x200C
private const val ZWJ = 0x200D
private const val FIRST_SUPPLEMENTARY = 0x10000
private const val HIGH_BASE = 0xD800
private const val LOW_BASE = 0xDC00
private const val SURROGATE_BITS = 10
private const val LOW_BITS = 0x3FF
private const val CASE_GAP = 'a' - 'A'

// Space set S (Unicode Zs).
private val SPACE_SET =
    listOf(
        0x0020..0x0020,
        0x00A0..0x00A0,
        0x1680..0x1680,
        0x2000..0x200A,
        0x202F..0x202F,
        0x205F..0x205F,
        0x3000..0x3000,
    )

// Blank and break set B.
private val BLANK_AND_BREAK_SET =
    listOf(
        0x0009..0x000D,
        0x001C..0x001F,
        0x0085..0x0085,
        0x2028..0x2029,
        0x115F..0x1160,
        0x2800..0x2800,
        0x3164..0x3164,
        0xFFA0..0xFFA0,
    )

// Dropped set D, part 1: fold 1's dropped set (docs/rules-format.md, Studio names).
private val DROPPED_FOLD_1 =
    listOf(
        0x0000..0x001F,
        0x007F..0x009F,
        0x00AD..0x00AD,
        0x0600..0x0605,
        0x061C..0x061C,
        0x06DD..0x06DD,
        0x070F..0x070F,
        0x0890..0x0891,
        0x08E2..0x08E2,
        0x180E..0x180E,
        0x200B..0x200B,
        0x200E..0x200F,
        0x202A..0x202E,
        0x2060..0x2064,
        0x2066..0x206F,
        0xFEFF..0xFEFF,
        0xFFF9..0xFFFB,
        0x110BD..0x110BD,
        0x110CD..0x110CD,
        0x13430..0x1343F,
        0x1BCA0..0x1BCA3,
        0x1D173..0x1D17A,
        0xE0001..0xE0001,
        0xE0020..0xE007F,
    )

// Dropped set D, part 2: Default_Ignorable_Code_Point of Unicode 15.1.0 DerivedCoreProperties.txt, reserved code
// points included, less U+200C and U+200D. The vector generator checks this list against that file.
private val DROPPED_DEFAULT_IGNORABLE =
    listOf(
        0x00AD..0x00AD,
        0x034F..0x034F,
        0x061C..0x061C,
        0x115F..0x1160,
        0x17B4..0x17B5,
        0x180B..0x180D,
        0x180E..0x180E,
        0x180F..0x180F,
        0x200B..0x200B,
        0x200E..0x200F,
        0x202A..0x202E,
        0x2060..0x2064,
        0x2065..0x2065,
        0x2066..0x206F,
        0x3164..0x3164,
        0xFE00..0xFE0F,
        0xFEFF..0xFEFF,
        0xFFA0..0xFFA0,
        0xFFF0..0xFFF8,
        0x1BCA0..0x1BCA3,
        0x1D173..0x1D17A,
        0xE0000..0xE0000,
        0xE0001..0xE0001,
        0xE0002..0xE001F,
        0xE0020..0xE007F,
        0xE0080..0xE00FF,
        0xE0100..0xE01EF,
        0xE01F0..0xE0FFF,
    )

// Dropped set D, part 3: U+2800 (Braille pattern blank), which is not default-ignorable.
private val DROPPED_EXTRA = listOf(0x2800..0x2800)

// Gap set G: what shows as a space or a blank and that fold 1 keeps, as itself or as U+0020: S, and these.
private val GAP_EXTRA =
    listOf(
        0x0009..0x000A,
        0x000D..0x000D,
        0x2028..0x2029,
        0x115F..0x1160,
        0x2800..0x2800,
        0x3164..0x3164,
        0xFFA0..0xFFA0,
    )

// Removed set R = S u B u D, merged into one sorted list of disjoint ranges once, for a binary search.
private val REMOVED: IntArray =
    merged(SPACE_SET + BLANK_AND_BREAK_SET + DROPPED_FOLD_1 + DROPPED_DEFAULT_IGNORABLE + DROPPED_EXTRA)

private val GAP: IntArray = merged(SPACE_SET + GAP_EXTRA)

private const val LATIN_END = 0x024F
private val COMBINING_MARKS = 0x0300..0x036F

/** Ranges as a flat sorted array `[start0, end0, start1, end1, ...]` of disjoint, non-adjacent ranges. */
private fun merged(ranges: List<IntRange>): IntArray {
    val out = ArrayList<Int>()
    for (range in ranges.sortedBy { it.first }) {
        if (out.isNotEmpty() && range.first <= out[out.lastIndex] + 1) {
            out[out.lastIndex] = maxOf(out[out.lastIndex], range.last)
        } else {
            out.add(range.first)
            out.add(range.last)
        }
    }
    return out.toIntArray()
}

private fun inRanges(
    ranges: IntArray,
    cp: Int,
): Boolean {
    var low = 0
    var high = ranges.size / 2 - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        when {
            cp < ranges[2 * mid] -> high = mid - 1
            cp > ranges[2 * mid + 1] -> low = mid + 1
            else -> return true
        }
    }
    return false
}

/** Whether [cp] is in the removed set R of studio fold 2 (S, B and D together). */
internal fun isRemovedByStudioKey2(cp: Int): Boolean = inRanges(REMOVED, cp)

/** Whether [cp] is in the gap set G of studio fold 2. */
internal fun isGapForStudioKey2(cp: Int): Boolean = inRanges(GAP, cp)

/** Whether [cp] is in the Latin range L of studio fold 2: U+0000 to U+024F and U+0300 to U+036F. */
internal fun isLatinForStudioKey2(cp: Int): Boolean = cp in 0..LATIN_END || cp in COMBINING_MARKS

/**
 * The studio fold 2 key of [text] (ADR-0026, "The pass"): two studio names match when their keys are equal code point
 * for code point. One pass by code point, keeping the last kept code point and a gap flag:
 * 1. a surrogate that is not half of a pair is removed;
 * 2. a code point of the removed set R (spaces, blanks and breaks, default-ignorables, U+2800) is removed, and sets the
 *    gap flag when it is in the gap set G;
 * 3. U+200C or U+200D is removed when nothing is kept yet, the last kept code point is Latin (U+0000 to U+024F, U+0300
 *    to U+036F) or the gap flag is on, and kept otherwise;
 * 4. anything else is kept, `A` to `Z` written as `a` to `z`.
 *
 * Every keep clears the gap flag. No trim, no collapse, no normalisation, no locale. Idempotent, and the key of studio
 * fold 1's result ([cleanStudioName] then [foldNameAscii]) is the key of the raw name. An empty key is possible (a name
 * of spaces or blanks only). [cleanStudioName] is studio fold 1 and is unchanged.
 */
fun studioKey2(text: String): String {
    val pass = StudioKey2Pass(text.length)
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        val next = text.getOrNull(i + 1)
        if (ch.isHighSurrogate() && next != null && next.isLowSurrogate()) {
            pass.read(FIRST_SUPPLEMENTARY + ((ch.code - HIGH_BASE) shl SURROGATE_BITS) + (next.code - LOW_BASE))
            i += 2
        } else {
            if (!ch.isSurrogate()) pass.read(ch.code)
            i++
        }
    }
    return pass.key()
}

/** The state of one [studioKey2] pass: the key so far, the last kept code point (-1 for none) and the gap flag. */
private class StudioKey2Pass(
    capacity: Int,
) {
    private val out = StringBuilder(capacity)
    private var lastKept = -1
    private var gap = false

    fun read(cp: Int) {
        if (isRemovedByStudioKey2(cp)) {
            gap = gap || isGapForStudioKey2(cp)
        } else if ((cp != ZWNJ && cp != ZWJ) || keepsJoiner()) {
            append(if (cp in 'A'.code..'Z'.code) cp + CASE_GAP else cp)
            lastKept = cp
            gap = false
        }
    }

    fun key(): String = out.toString()

    private fun keepsJoiner() = lastKept >= 0 && !isLatinForStudioKey2(lastKept) && !gap

    private fun append(cp: Int) {
        if (cp < FIRST_SUPPLEMENTARY) {
            out.append(Char(cp))
        } else {
            val offset = cp - FIRST_SUPPLEMENTARY
            out.append(Char(HIGH_BASE + (offset shr SURROGATE_BITS)))
            out.append(Char(LOW_BASE + (offset and LOW_BITS)))
        }
    }
}

/** [names] through [studioKey2], in order; a name whose key is empty is dropped (one key per name otherwise). */
internal fun studioKeys2(names: List<String>): List<String> = names.mapNotNull { studioKey2(it).ifEmpty { null } }
