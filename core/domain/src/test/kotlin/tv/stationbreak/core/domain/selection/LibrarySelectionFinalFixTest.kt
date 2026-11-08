// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.selection

import kotlinx.coroutines.runBlocking
import tv.stationbreak.core.domain.StoredSelection
import tv.stationbreak.core.domain.lineup.SavedSelection
import tv.stationbreak.core.domain.lineup.lineupOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Spec 26, Part 4A final fixes: a choice of another account found at the start is cleared (as the sign-in meant), so
 * the launch routes to the picker; features never see another account's choice; the resume takes no repair turn when
 * no library leaves the cache, and reports its time; setup and clear stop the add worker only when an add was under
 * way.
 */
internal class LibrarySelectionFinalFixTest : SelectionTestBase() {
    // F2: a sign-in's clear failed and then the process died: the start finds another account's choice.
    @Test
    fun aForeignChoiceAtTheStart_isCleared_soTheLaunchRoutesToThePicker() {
        val tv = Tv(stored = StoredSelection(setOf(libA, libB), seq = 3L, owner = other))

        tv.resume()

        assertNull(tv.stored(), "cleared, as the sign-in meant")
        assertNull(runBlocking { tv.settings.loadLibraries() }, "what the launch routes on: nothing chosen")
        assertNull(tv.selection.selection.value, "and nothing of it shown")
    }

    // F4: a clear that fails leaves another account's choice stored; features are not shown it.
    @Test
    fun aClearThatFails_showsNoOtherAccountsChoice() {
        val tv = Tv().ready(setOf(libA, libB))
        assertNotNull(tv.selection.selection.value)
        // Another account's choice, stored after the start (its sign-in's clear failed).
        assertTrue(runBlocking { tv.settings.inner.saveSelection(StoredSelection(setOf(libC), 5L, owner = other)) })
        tv.settings.failClears = 1

        assertFalse(runBlocking { tv.selection.clear() })

        assertNull(tv.selection.selection.value)
    }

    // F5: a resume with every stored library kept takes no repair turn for holds (nothing leaves).
    @Test
    fun aResumeWithNothingLeaving_readsNoHolds() {
        val tv = Tv()
        val before = tv.rig.providerReads.get()

        tv.resume()

        assertEquals(before, tv.rig.providerReads.get(), "no held-pins turn")
        assertEquals(1, tv.resumeTimes.size, "its time is reported once")
        assertTrue(tv.resumeTimes.single() >= 0)
    }

    @Test
    fun aResumeWithALibraryLeaving_stillReadsTheHolds() {
        // B is in the cache but no longer chosen (its untick's cache write never ran).
        val tv = Tv(chosen = setOf(libA, libB), stored = StoredSelection(setOf(libA), seq = 2L))
        val before = tv.rig.providerReads.get()

        tv.resume()

        assertTrue(tv.rig.providerReads.get() > before, "the leaving library's hold is read")
    }

    // F7: with nothing being added there is no add worker to stop.
    @Test
    fun aClearOrASetup_withNothingBeingAdded_asksForNoAddPass() {
        val tv = Tv().ready(setOf(libA, libB))

        assertTrue(runBlocking { tv.selection.clear() })
        assertEquals(SetupResult.Saved, runBlocking { tv.selection.choose(setOf(libA)) })

        assertEquals(0, tv.restarts)
    }

    @Test
    fun aClear_withAnAddUnderWay_stopsTheAddPass() {
        val tv = Tv(lineup = lineupOf(laughs), chosen = setOf(libA)).ready(setOf(libA, libB))
        assertEquals(ChangeResult.Saved, tv.change(setOf(libA, libB)))
        val before = tv.restarts

        assertTrue(runBlocking { tv.selection.clear() })

        assertEquals(before + 1, tv.restarts)
    }

    // F6: a selection printed (in a log line, say) gives counts only: no library id, no account.
    @Test
    fun aStoredAndASavedSelection_printCountsOnly() {
        val stored = StoredSelection(setOf(libA, libB), seq = 4L, adding = mapOf(libC to 4L), owner = other)
        val saved = SavedSelection(setOf(libA), seq = 4L, playable = setOf(libA), resetSeq = 1L)

        for (text in listOf(stored.toString(), saved.toString())) {
            for (id in listOf(libA, libB, libC)) assertFalse(id.value in text, text)
            assertFalse(other.user.value in text, text)
        }
        assertTrue("chosen=2" in stored.toString())
        assertTrue("libraries=1" in saved.toString())
    }
}
