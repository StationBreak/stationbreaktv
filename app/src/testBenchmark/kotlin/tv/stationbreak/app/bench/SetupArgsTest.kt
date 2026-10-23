// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SetupArgsTest {
    @Test
    fun setupNeedsAServerAndTrimsIt() {
        assertEquals(SetupArgs.Setup("media.example.invalid"), SetupArgs.parse("setup", "  media.example.invalid "))
    }

    @Test
    fun setupWithoutAUsableServerIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SetupArgs.parse("setup", null) }
        assertThrows(IllegalArgumentException::class.java) { SetupArgs.parse("setup", "   ") }
        assertThrows(IllegalArgumentException::class.java) {
            SetupArgs.parse("setup", "a".repeat(SetupArgs.MAX_SERVER + 1))
        }
    }

    @Test
    fun signoutNeedsNoServer() {
        assertEquals(SetupArgs.SignOut, SetupArgs.parse("signout", null))
    }

    @Test
    fun anUnknownOrMissingActionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SetupArgs.parse(null, "media.example.invalid") }
        assertThrows(IllegalArgumentException::class.java) { SetupArgs.parse("wipe", null) }
    }

    @Test
    fun theServerIsNotPrinted() {
        assertFalse(SetupArgs.Setup("media.example.invalid").toString().contains("example"))
    }
}
