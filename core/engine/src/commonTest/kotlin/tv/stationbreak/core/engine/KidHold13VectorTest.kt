// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test

/** The cases of `docs/rules-format/vectors/kid-hold-13.json` (#987), from [KidHold13VectorFile]. */
class KidHold13VectorTest {
    @Test
    fun theCeilingAndExplicitSpellingVectorCasesHold() {
        KidHold6VectorCheck.run(KidHold13VectorFile.json, minRows = MIN_ROWS, explicitIsAdult = false)
    }

    private companion object {
        const val MIN_ROWS = 200
    }
}
