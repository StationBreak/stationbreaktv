// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SetupArgsTest {
    @Test
    fun setupNeedsAServerAndTrimsIt() {
        assertEquals(SetupArgs.Setup("https://media.example.invalid"), SetupArgs.parse("setup", "  https://media.example.invalid "))
    }

    @Test
    fun setupWithoutAUsableServerIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SetupArgs.parse("setup", null) }
        assertThrows(IllegalArgumentException::class.java) { SetupArgs.parse("setup", "   ") }
        assertThrows(IllegalArgumentException::class.java) {
            SetupArgs.parse("setup", "https://" + "a".repeat(SetupArgs.MAX_SERVER))
        }
    }

    @Test
    fun aServerWithoutAnExplicitSchemeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SetupArgs.parse("setup", "media.example.invalid") }
        assertThrows(IllegalArgumentException::class.java) { SetupArgs.parse("setup", "ftp://media.example.invalid") }
        assertEquals(SetupArgs.Setup("http://10.0.0.1:8096"), SetupArgs.parse("setup", "http://10.0.0.1:8096"))
    }

    @Test
    fun signoutNeedsNoServerAndMayBeForced() {
        assertEquals(SetupArgs.SignOut(), SetupArgs.parse("signout", null))
        assertEquals(SetupArgs.SignOut(force = true), SetupArgs.parse("signout", null, force = true))
    }

    @Test
    fun anUnknownOrMissingActionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SetupArgs.parse(null, "https://media.example.invalid") }
        assertThrows(IllegalArgumentException::class.java) { SetupArgs.parse("wipe", null) }
    }

    @Test
    fun theServerIsNotPrinted() {
        assertFalse(SetupArgs.Setup("https://media.example.invalid").toString().contains("example"))
    }
}
