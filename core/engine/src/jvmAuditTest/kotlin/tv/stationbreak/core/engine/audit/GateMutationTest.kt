// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The gate must fail when a rule is broken. Each test makes a leak the way an engine bug would (a title on a channel it
 * must not air on, in a view the real planner made) and holds that the invariant of its rule reports it. These are the
 * four deliberate mutations of #1035; [GateRuleMutationTest] has the other rules.
 */
class GateMutationTest {
    private val leaks = Leaks()

    @Test
    fun anXxxHentaiSeriesOnTokyoAfterSchoolFails() {
        val item = leaks.slice("Synthetic Hentai Series")
        val rules = leaks.broken(leaks.leaking("tokyo-after-school", item))
        assertTrue(Rule.ExplicitOffAfterHours in rules, "$rules")
    }

    @Test
    fun anRFilmWithTheSexploitationKeywordOnShagCarpetFails() {
        val item = leaks.slice("Synthetic Seventies Sexploitation Comedy")
        val rules = leaks.broken(leaks.leaking("shag-carpet", item))
        assertTrue(Rule.ExplicitOffAfterHours in rules, "$rules")
    }

    @Test
    fun aTv14EcchiSeriesOnLevelUpFails() {
        val rules = leaks.broken(leaks.leaking("level-up", leaks.slice("Synthetic TV-14 Ecchi Anime")))
        assertTrue(Rule.AdultOnYoungBand in rules, "$rules")
    }

    @Test
    fun aNoMetadataClipInOddsAndEnds199Fails() {
        val key =
            leaks.view.channels
                .first { it.leftovers && it.number == ODDS_AND_ENDS_GROWN_UP }
                .key
        val rules = leaks.broken(leaks.leaking(key, leaks.slice("Synthetic Test Clip")))
        assertTrue(Rule.NoMetadataInLeftovers in rules, "$rules")
    }

    private companion object {
        const val ODDS_AND_ENDS_GROWN_UP = 199
    }
}
