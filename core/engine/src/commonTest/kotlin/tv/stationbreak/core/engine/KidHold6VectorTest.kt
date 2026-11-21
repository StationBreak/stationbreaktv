// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test

/** The cases of `docs/rules-format/vectors/kid-hold-6.json`, from the copy in [KidHold6VectorFile]. */
class KidHold6VectorTest {
    @Test
    fun theSharedKidHold6VectorCasesHold() = KidHold6VectorCheck.run(KidHold6VectorFile.json)
}
