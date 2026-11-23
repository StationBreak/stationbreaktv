// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test

/** The cases of `docs/rules-format/vectors/kid-hold-10.json` (#987), from [KidHold10VectorFile]. */
class KidHold10VectorTest {
    @Test
    fun theSharedNonUsRatingVectorCasesHold() {
        KidHold6VectorCheck.run(KidHold10VectorFile.json, minRows = MIN_ROWS, explicitIsAdult = false)
    }

    private companion object {
        // Every spelling of the Android TV XML's country tables has a row.
        const val MIN_ROWS = 1_000
    }
}
