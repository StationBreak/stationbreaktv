// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test

/** The cases of `docs/rules-format/vectors/grown-up-hold-9.json` (#1108), from [GrownUpHold9VectorFile]. */
class GrownUpHold9VectorTest {
    @Test
    fun theCustomCeilingVectorCasesHold() {
        val json = GrownUpHold9VectorFile.json
        KidHold6VectorCheck.run(json, minRows = 1, explicitIsAdult = false)
    }
}
