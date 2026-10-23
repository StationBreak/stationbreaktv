// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.domain.QuickConnectState
import tv.stationbreak.core.domain.ResolvedServer
import tv.stationbreak.core.domain.ServerRecord
import tv.stationbreak.core.domain.ServerResult
import tv.stationbreak.core.model.ItemId
import tv.stationbreak.core.model.Scheme

class BenchSetupTest : SetupFixture() {
    @Test
    fun setupSignsInSavesTheSessionAndTheDefaultLibraries() =
        runTest {
            approvedSignIn()

            setup.setup("media.example.invalid")

            assertEquals(listOf("QC 123456", "SETUP OK"), lines)
            assertEquals(listOf("media.example.invalid"), fake.discoverCalls)
            assertEquals(address, fake.quickConnectCalls.single().first)
            assertNotNull(stored())
            assertEquals(ServerRecord(address), settings.current)
            // Home videos are left out, as the picker's defaults leave them.
            assertEquals(setOf(ItemId("films")), settings.currentLibraries)
        }

    @Test
    fun aStoredSessionIsNeverOverwritten() =
        runTest {
            approvedSignIn()
            sessions.save(session)
            settings.save(ServerRecord(address))
            fake.resumeResult = ServerResult.Success(session)

            setup.setup("media.example.invalid")

            assertEquals(listOf("SETUP ERROR already-signed-in"), lines)
            assertTrue(fake.discoverCalls.isEmpty())
            assertTrue(fake.quickConnectCalls.isEmpty())
        }

    @Test
    fun aDeniedCodeIsAnErrorAndSavesNothing() =
        runTest {
            approvedSignIn()
            fake.quickConnectStates = listOf(QuickConnectState.CodeIssued("123456"), QuickConnectState.Denied)

            setup.setup("media.example.invalid")

            assertEquals(listOf("QC 123456", "SETUP ERROR qc-denied"), lines)
            assertNull(stored())
            assertNull(settings.current)
        }

    @Test
    fun aFlowThatEndsWithoutAnAnswerIsAnExpiredCode() =
        runTest {
            approvedSignIn()
            fake.quickConnectStates = listOf(QuickConnectState.CodeIssued("123456"), QuickConnectState.Waiting)

            setup.setup("media.example.invalid")

            assertEquals(listOf("QC 123456", "SETUP ERROR qc-expired"), lines)
        }

    @Test
    fun anAddressTheServerRefusesIsAReasonCodeOnly() =
        runTest {
            fake.discoverResult = ServerResult.Failure(MediaServerError.InvalidAddress())

            setup.setup("not an address")

            assertEquals(listOf("SETUP ERROR bad-server"), lines)
            assertTrue(fake.quickConnectCalls.isEmpty())
        }

    @Test
    fun anExplicitHttpAddressIsAcknowledgedBecauseTheOperatorTypedIt() =
        runTest {
            approvedSignIn()
            val cleartext = address.copy(scheme = Scheme.Http, port = 80)
            fake.discoverResult = ServerResult.Success(ResolvedServer(cleartext, session.server))

            setup.setup("http://media.example.invalid")

            assertTrue(cleartext.cleartextNeedsConfirmation)
            assertEquals(
                cleartext,
                fake.quickConnectCalls
                    .single()
                    .second
                    ?.address,
            )
        }

    @Test
    fun anHttpAddressReachedByFallbackIsRefusedAsCleartext() =
        runTest {
            approvedSignIn()
            val fallback = address.copy(scheme = Scheme.Http, port = 80, viaFallback = true)
            fake.discoverResult = ServerResult.Success(ResolvedServer(fallback, session.server))

            setup.setup("https://media.example.invalid")

            assertEquals(listOf("SETUP ERROR cleartext"), lines)
            assertTrue(fake.quickConnectCalls.isEmpty())
        }

    @Test
    fun onlyHomeVideosIsNoLibraries() =
        runTest {
            approvedSignIn()
            fake.librariesResult = ServerResult.Success(listOf(libraries[1]))

            setup.setup("media.example.invalid")

            assertEquals("SETUP ERROR no-libraries", lines.last())
            assertNull(stored())
        }

    @Test
    fun noLineHoldsTheAddressATokenOrALibraryName() =
        runTest {
            approvedSignIn()
            setup.setup("media.example.invalid")
            setup.signOut()

            val text = lines.joinToString("\n")
            assertFalse(text.contains("example"))
            assertFalse(text.contains("tok"))
            assertFalse(text.contains("Films"))
        }

    @Test
    fun aSessionStoredWithNoServerRecordIsStillASession() =
        runTest {
            approvedSignIn()
            sessions.save(session)

            setup.setup("https://media.example.invalid")

            assertNull(settings.current)
            assertEquals(listOf("SETUP ERROR already-signed-in"), lines)
            assertTrue(fake.discoverCalls.isEmpty())
        }

    @Test
    fun anUnreadableStoreIsCantTellAndNothingIsOverwritten() =
        runTest {
            approvedSignIn()
            store.failLoad = true

            setup.setup("https://media.example.invalid")

            assertEquals(listOf("SETUP ERROR store-unreadable"), lines)
            assertTrue(fake.discoverCalls.isEmpty())
            assertTrue(store.writtenKeys.isEmpty())
        }
}
