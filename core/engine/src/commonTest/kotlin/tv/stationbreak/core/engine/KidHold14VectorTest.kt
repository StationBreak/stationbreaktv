// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test

/** The cases of `docs/rules-format/vectors/kid-hold-14.json` (#1006), from [KidHold14VectorFile]. */
class KidHold14VectorTest {
    @Test
    fun theAgeSeventeenAndDashVectorCasesHold() {
        KidHold6VectorCheck.run(KidHold14VectorFile.json, minRows = MIN_ROWS, explicitIsAdult = false)
    }

    private companion object {
        const val MIN_ROWS = 40
    }
}
