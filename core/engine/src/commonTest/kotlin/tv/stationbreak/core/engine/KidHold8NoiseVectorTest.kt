// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test

/** The cases of `docs/rules-format/vectors/kid-hold-8-noise.json` (#1088), from [KidHold8NoiseVectorFile]. */
class KidHold8NoiseVectorTest {
    @Test
    fun theSharedNoiseVectorCasesHold() {
        val json = KidHold8NoiseVectorFile.json
        KidHold6VectorCheck.run(json, minRows = 1, explicitIsAdult = false)
    }
}
