// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test

/** The cases of `docs/rules-format/vectors/kid-hold-11.json` (#1124), from [KidHold11VectorFile]. */
class KidHold11VectorTest {
    @Test
    fun theCeilingAndExplicitSpellingVectorCasesHold() {
        KidHold6VectorCheck.run(KidHold11VectorFile.json, minRows = MIN_ROWS, explicitIsAdult = false)
    }

    private companion object {
        const val MIN_ROWS = 75
    }
}
