// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test

/** The cases of `docs/rules-format/vectors/kid-hold-7.json`, from the copy in [KidHold7VectorFile]. */
class KidHold7VectorTest {
    @Test
    fun theSharedKidHold7VectorCasesHold() = KidHold6VectorCheck.run(KidHold7VectorFile.json, minRows = 30)
}
