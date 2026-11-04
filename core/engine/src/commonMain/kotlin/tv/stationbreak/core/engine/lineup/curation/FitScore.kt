// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

private const val FRAC_BITS = 16
private const val MID_BITS = 30
private const val SPECIFICITY_SCALE = 100L shl 32

/**
 * Binary logarithm of [x] (at least 1) in 16-bit fixed point, by integer squaring: the whole part is the highest set
 * bit, then 16 fraction bits. Integer only, so every TV gets the same answer.
 */
internal fun log2Q16(x: Long): Long {
    val k = 63 - x.countLeadingZeroBits()
    var m = if (k <= MID_BITS) x shl (MID_BITS - k) else x ushr (k - MID_BITS)
    var frac = 0L
    for (bit in FRAC_BITS - 1 downTo 0) {
        m = (m * m) ushr MID_BITS
        if (m >= (1L shl (MID_BITS + 1))) {
            m = m ushr 1
            frac = frac or (1L shl bit)
        }
    }
    return (k.toLong() shl FRAC_BITS) or frac
}

/** 100 / log2(2 + [candidateTitles]) in 16-bit fixed point: a channel with fewer candidates fits each title more. */
internal fun specificityQ16(candidateTitles: Int): Long = SPECIFICITY_SCALE / log2Q16(2L + candidateTitles)

/** What one title brings to one channel's fit score. */
@Suppress("LongParameterList") // A plain carrier: one field per term of the score.
internal class FitInputs(
    val genreHits: Int,
    val tagHits: Int,
    val themeStudio: Boolean,
    val guestOnly: Boolean,
    val anchor: Boolean,
    val kidOnKid: Boolean,
    val formatFits: Boolean,
    val toneMs: Long,
    val candidateMs: Long,
)

private const val GENRE_POINTS = 40L
private const val TAG_POINTS = 35L
private const val TAG_HITS_MAX = 2
private const val STUDIO_POINTS = 120L
private const val GUEST_ONLY_POINTS = 150L
private const val ANCHOR_POINTS = 1000L
private const val KID_POINTS = 200L
private const val FORMAT_POINTS = 80L
private const val TONE_POINTS = 60L

/** The fit of a title to a channel with [candidateTitles] candidates, in 16-bit fixed point. */
internal fun fitScoreQ16(
    candidateTitles: Int,
    f: FitInputs,
): Long {
    val plain =
        GENRE_POINTS * f.genreHits + TAG_POINTS * minOf(f.tagHits, TAG_HITS_MAX) +
            (if (f.themeStudio) STUDIO_POINTS else 0L) - (if (f.guestOnly) GUEST_ONLY_POINTS else 0L) +
            (if (f.anchor) ANCHOR_POINTS else 0L) + (if (f.kidOnKid) KID_POINTS else 0L) +
            (if (f.formatFits) FORMAT_POINTS else 0L) +
            (if (f.candidateMs > 0) TONE_POINTS * f.toneMs / f.candidateMs else 0L)
    return specificityQ16(candidateTitles) + (plain shl FRAC_BITS)
}
