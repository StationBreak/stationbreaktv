// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine

import kotlin.test.Test

/** The cases of `docs/rules-format/vectors/kid-hold-5.json`, from the copy in [KidHold5VectorFile]. */
class KidHold5VectorTest {
    @Test
    fun theSharedKidHold5VectorCasesHold() = KidHold5VectorCheck.run(KidHold5VectorFile.json)
}
