// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class LogNameTest {
    private sealed interface Outer {
        data object Watching : Outer

        class Tuning : Outer
    }

    private class TopLevelLike

    @Test
    fun aNestedClassIsNamedWithoutItsOuterClass() {
        // From the binary name, so a minified build that drops the InnerClasses attribute still says "Watching" (#441).
        assertEquals("Watching", Outer.Watching.logName())
        assertEquals("Tuning", Outer.Tuning().logName())
        assertEquals("Watching", Outer.Watching::class.java.logName())
    }

    @Test
    fun aClassNestedInATestIsNamedByItsOwnName() {
        assertEquals("TopLevelLike", TopLevelLike().logName())
        assertEquals("String", "x".logName())
    }

    @Test
    fun anAnonymousClassHasNoName() {
        val anonymous = object {}
        assertEquals("", anonymous.logName())
    }
}
