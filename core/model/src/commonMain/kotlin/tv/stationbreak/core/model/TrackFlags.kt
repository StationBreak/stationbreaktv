// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * What a track is for, beyond its language, as far as the server tells us: [commentary] (a director's or cast
 * commentary), [sdh] (subtitles for the deaf and hard of hearing, with sound cues), [forced] (only the foreign-language
 * lines) and [description] (audio description for the blind). The picker labels three of them. Commentary, forced and
 * audio description never carry to the next programme; SDH does, as a preference for such a track within the carried
 * language (design doc B2.6).
 */
data class TrackFlags(
    val commentary: Boolean = false,
    val sdh: Boolean = false,
    val forced: Boolean = false,
    val description: Boolean = false,
) {
    /** True when the track is never carried to another programme: commentary, forced or audio description. */
    val neverCarries: Boolean get() = commentary || forced || description

    companion object {
        private val COMMENTARY = Regex("""commentar""", RegexOption.IGNORE_CASE)
        private val SDH = Regex("""\bsdh\b|hearing[\s-]+impaired|deaf""", RegexOption.IGNORE_CASE)
        private val FORCED = Regex("""\bforced\b""", RegexOption.IGNORE_CASE)
        private val DESCRIPTION =
            Regex("""audio[\s-]+descri|\bdescriptive\b|\bvisually[\s-]+impaired\b""", RegexOption.IGNORE_CASE)
    }

    /** The flags of [stream]: the server's own forced flag, and what its titles say for the rest. */
    internal constructor(stream: MediaStreamInfo) : this(
        commentary = stream.matches(COMMENTARY),
        sdh = stream.isHearingImpaired || stream.matches(SDH),
        forced = stream.isForced || stream.matches(FORCED),
        description = stream.matches(DESCRIPTION),
    )
}

private fun MediaStreamInfo.matches(pattern: Regex): Boolean =
    (title?.let(pattern::containsMatchIn) ?: false) || (displayTitle?.let(pattern::containsMatchIn) ?: false)

/** The [TrackFlags] of this track. */
fun MediaStreamInfo.flags(): TrackFlags = TrackFlags(this)
