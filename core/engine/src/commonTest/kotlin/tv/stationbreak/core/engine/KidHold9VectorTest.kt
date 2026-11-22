// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test

/** The cases of `docs/rules-format/vectors/kid-hold-9.json` (#1088), from [KidHold9VectorFile]. */
class KidHold9VectorTest {
    @Test
    fun theSharedNoiseVectorCasesHold() {
        val json = KidHold9VectorFile.json
        KidHold6VectorCheck.run(json, minRows = 1, explicitIsAdult = false)
    }
}
