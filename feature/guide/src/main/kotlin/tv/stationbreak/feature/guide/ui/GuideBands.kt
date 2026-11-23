// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import tv.stationbreak.core.domain.lineup.NumberBand
import tv.stationbreak.core.ui.BandColors
import tv.stationbreak.core.ui.BandFlag
import tv.stationbreak.core.ui.GuideColors
import tv.stationbreak.core.ui.teletype
import tv.stationbreak.feature.guide.GuideRow
import tv.stationbreak.feature.guide.R

/** The accent of [band] in these colours. */
internal fun BandColors.of(band: NumberBand): Color =
    when (band) {
        NumberBand.Tots -> tots
        NumberBand.Kids -> kids
        NumberBand.Teens -> teens
        NumberBand.Adults -> adults
    }

/**
 * The stripe on a channel tile's left edge: the band's accent, or none for Adults, which has no accent of its own
 * (most of a lineup is Adults, and a neutral stripe on every tile would only be noise).
 */
internal fun stripeOf(
    band: NumberBand?,
    colors: GuideColors,
): Color? = band?.takeIf { it != NumberBand.Adults }?.let { colors.band.of(it) }

/**
 * The band that starts at the row at [index], when a divider goes between it and the row above: the two are both in
 * the window that starts at [firstVisibleRow] (the first visible row has none above it) and their bands differ. The
 * divider is the band's accent, so a row with no band starts none. Adults starts one in the neutral stroke colour.
 */
internal fun dividerBandAt(
    rows: List<GuideRow>,
    index: Int,
    firstVisibleRow: Int,
): NumberBand? {
    if (index <= firstVisibleRow || index !in rows.indices) return null
    val band = rows[index].band
    return band?.takeIf { it != rows[index - 1].band }
}

@Composable
private fun bandName(band: NumberBand): String =
    stringResource(
        when (band) {
            NumberBand.Tots -> R.string.band_tots
            NumberBand.Kids -> R.string.band_kids
            NumberBand.Teens -> R.string.band_teens
            NumberBand.Adults -> R.string.band_adults
        },
    )

/** The panel's band flag for [band]: its dot, the name and the range in the teletype, in secondary text. */
@Composable
internal fun PanelBandFlag(
    band: NumberBand,
    colors: GuideColors,
) = BandFlag(
    name = bandName(band),
    first = band.numbers.first,
    last = band.last,
    accent = colors.band.of(band),
    textColor = colors.textSecondary,
    style = teletype(BAND_FLAG_SP.sp),
)

private const val BAND_FLAG_SP = 10
