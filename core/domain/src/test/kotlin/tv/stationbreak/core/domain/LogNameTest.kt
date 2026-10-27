// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** A class at the top of its file: its binary name has no '$'. */
private class TopLevel

class LogNameTest {
    private sealed interface Outer {
        data object Watching : Outer

        class Tuning : Outer {
            class Deeper
        }
    }

    @Test
    fun aNestedClassIsNamedWithoutItsOuterClasses() {
        // From the binary name, so a minified build that drops the InnerClasses attribute still says "Watching" (#441).
        assertEquals("Watching", Outer.Watching.javaClass.logName())
        assertEquals("Tuning", Outer.Tuning::class.java.logName())
        assertEquals("Deeper", Outer.Tuning.Deeper::class.java.logName())
    }

    @Test
    fun aTopLevelClassIsNamedByItsOwnName() {
        assertEquals("TopLevel", TopLevel::class.java.logName())
        assertEquals("String", "x".javaClass.logName())
    }

    @Test
    fun anAnonymousClassHasNoName() {
        val anonymous = object {}
        assertEquals("", anonymous.javaClass.logName())
    }
}
