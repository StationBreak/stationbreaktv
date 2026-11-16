// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.lineup.curation

private const val FRAC_BITS = 16
private const val MID_BITS = 30
private const val SPECIFICITY_SCALE = 100L shl 32

/**
 * Binary logarithm of [x] (at least 1) in 16-bit fixed point, by integer squaring: the whole part is the highest set
 * bit, then 16 fraction bits. Integer only, so every TV gets the same answer. [x] must be positive.
 */
internal fun log2Q16(x: Long): Long {
    require(x > 0) { "log2 needs a positive number" }
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

/** 100 / log2(2 + [candidateTitles]) in 16-bit fixed point: a channel with fewer candidates fits each title more.
 * [candidateTitles] must not be negative.
 */
internal fun specificityQ16(candidateTitles: Int): Long {
    require(candidateTitles >= 0) { "A channel has no negative candidates" }
    return SPECIFICITY_SCALE / log2Q16(2L + candidateTitles)
}

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

/** The fit of a title to a channel with [candidateTitles] (not negative) candidates, in 16-bit fixed point. */
internal fun fitScoreQ16(
    candidateTitles: Int,
    f: FitInputs,
): Long =
    specificityQ16(candidateTitles) +
        fitPointsQ16(
            f.genreHits,
            f.tagHits,
            f.themeStudio,
            f.guestOnly,
            f.anchor,
            f.kidOnKid,
            f.formatFits,
            f.toneMs,
            f.candidateMs,
        )

/**
 * The fit score without its specificity term, in 16-bit fixed point: the one place the points are written. A caller
 * that scores many titles on one channel adds the channel's [specificityQ16] once and allocates no [FitInputs].
 */
@Suppress("LongParameterList") // One parameter per term of the score, so the per-title path allocates nothing.
internal fun fitPointsQ16(
    genreHits: Int,
    tagHits: Int,
    themeStudio: Boolean,
    guestOnly: Boolean,
    anchor: Boolean,
    kidOnKid: Boolean,
    formatFits: Boolean,
    toneMs: Long,
    candidateMs: Long,
): Long {
    val plain =
        GENRE_POINTS * genreHits + TAG_POINTS * minOf(tagHits, TAG_HITS_MAX) +
            (if (themeStudio) STUDIO_POINTS else 0L) - (if (guestOnly) GUEST_ONLY_POINTS else 0L) +
            (if (anchor) ANCHOR_POINTS else 0L) + (if (kidOnKid) KID_POINTS else 0L) +
            (if (formatFits) FORMAT_POINTS else 0L) +
            (if (candidateMs > 0) TONE_POINTS * toneMs / candidateMs else 0L)
    return plain shl FRAC_BITS
}

/**
 * A fit score taken apart, each term in 16-bit fixed point (the lineup audit's reading of [fitScoreQ16]). [decade] is
 * the guest-only penalty: a guest-only channel is a decade channel (one of its sources is a year range), so a title
 * on it pays that term. [points] is every term but [specificity], which [fitPointsQ16] sums; [total] is the score.
 */
@Suppress("LongParameterList") // A plain carrier: one field per term of the score.
internal class FitTerms(
    val specificity: Long,
    val genre: Long,
    val tag: Long,
    val studio: Long,
    val decade: Long,
    val anchor: Long,
    val kids: Long,
    val format: Long,
    val tone: Long,
) {
    val points: Long get() = genre + tag + studio + decade + anchor + kids + format + tone
    val total: Long get() = specificity + points
}

/**
 * The terms of [fitScoreQ16] for a channel with [candidateTitles] candidates, from the same constants as
 * [fitPointsQ16]. For the audit only: it allocates, so the per-title path never calls it; a test holds that its
 * [FitTerms.points] equal [fitPointsQ16] for every combination.
 */
internal fun fitTermsQ16(
    candidateTitles: Int,
    f: FitInputs,
): FitTerms =
    FitTerms(
        specificity = specificityQ16(candidateTitles),
        genre = (GENRE_POINTS * f.genreHits) shl FRAC_BITS,
        tag = (TAG_POINTS * minOf(f.tagHits, TAG_HITS_MAX)) shl FRAC_BITS,
        studio = (if (f.themeStudio) STUDIO_POINTS else 0L) shl FRAC_BITS,
        decade = (if (f.guestOnly) -GUEST_ONLY_POINTS else 0L) shl FRAC_BITS,
        anchor = (if (f.anchor) ANCHOR_POINTS else 0L) shl FRAC_BITS,
        kids = (if (f.kidOnKid) KID_POINTS else 0L) shl FRAC_BITS,
        format = (if (f.formatFits) FORMAT_POINTS else 0L) shl FRAC_BITS,
        tone = (if (f.candidateMs > 0) TONE_POINTS * f.toneMs / f.candidateMs else 0L) shl FRAC_BITS,
    )
