// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test

/** The cases of `docs/rules-format/vectors/kid-hold-12.json` (#1128), from [KidHold12VectorFile]. */
class KidHold12VectorTest {
    @Test
    fun theCeilingAndExplicitSpellingVectorCasesHold() {
        KidHold6VectorCheck.run(KidHold12VectorFile.json, minRows = MIN_ROWS, explicitIsAdult = false)
    }

    private companion object {
        const val MIN_ROWS = 39
    }
}
