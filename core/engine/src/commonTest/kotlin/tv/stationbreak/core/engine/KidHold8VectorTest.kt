// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test

/** The cases of `docs/rules-format/vectors/kid-hold-8.json`, from the copy in [KidHold8VectorFile]. */
class KidHold8VectorTest {
    @Test
    fun theSharedKidHold8VectorCasesHold() = KidHold6VectorCheck.run(KidHold8VectorFile.json, explicitIsAdult = false)
}
