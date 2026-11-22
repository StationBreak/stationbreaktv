// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.FakeProEntitlement
import tv.stationbreak.core.domain.hours.AudienceLevel
import tv.stationbreak.core.domain.hours.HoursEdit
import tv.stationbreak.core.domain.hours.HoursOfOperation
import tv.stationbreak.core.domain.hours.HoursPeriod
import tv.stationbreak.core.domain.hours.HoursSchedule
import tv.stationbreak.core.domain.hours.InMemoryHoursStore
import tv.stationbreak.core.domain.kids.FakeKidsLock
import tv.stationbreak.feature.guide.R
import tv.stationbreak.core.ui.R as UiR

/** The Hours of operation screen (#789): period editing, the steppers' wrap, overnight, and each save result. */
class HoursScreenTest {
    private val night = HoursPeriod(5, 22 * 60, 6 * 60, AudienceLevel.OffAir)
    private val morning = HoursPeriod(1, 7 * 60, 9 * 60, AudienceLevel.Kids)
    private val pro = SettingsValues(pro = true, kidsPinSet = true)
    private val withTwo = pro.copy(hours = HoursSchedule(listOf(night, morning)))

    private fun SettingsPanel.press(
        key: Key,
        values: SettingsValues,
    ): SettingsStep {
        val step = hoursKey(checkNotNull(hours), values, key, KeyEventType.KeyDown, 0)
        return checkNotNull(step) { "$key was not kept" }
    }

    private fun SettingsPanel.release(
        key: Key,
        values: SettingsValues,
    ): SettingsStep {
        val step = hoursKey(checkNotNull(hours), values, key, KeyEventType.KeyUp, 0)
        return checkNotNull(step) { "$key was not kept" }
    }

    private fun open(screen: HoursScreen = HoursScreen()) = SettingsPanel(SettingsRow.Hours, hours = screen)

    // Presses [keys] one after the other on the panel, each from what the one before left.
    private fun SettingsPanel.pressed(
        values: SettingsValues,
        keys: List<Key>,
    ): SettingsStep {
        var step = SettingsStep(this)
        for (key in keys) step = checkNotNull(step.panel).press(key, values)
        return step
    }

    @Test
    fun theRowOpensTheScreen_unlessKidsModeIsOn() {
        val sheet = SettingsPanel(SettingsRow.Hours)
        val step = checkNotNull(settingsStep(sheet, pro, Key.DirectionCenter, KeyEventType.KeyDown, 0))
        assertEquals(SettingsStep(SettingsPanel(SettingsRow.Hours, hours = HoursScreen())), step)
        val locked = pro.copy(kidsOn = true)
        assertTrue(SettingsRow.Hours.isInert(locked))
        val refused = checkNotNull(settingsStep(sheet, locked, Key.DirectionCenter, KeyEventType.KeyDown, 0))
        assertEquals(SettingsStep(sheet), refused)
    }

    @Test
    fun theModeIsTheProNotice_thenSetAPin_thenTheEditor() {
        assertEquals(HoursMode.ProRequired, hoursMode(SettingsValues(pro = false, kidsPinSet = true)))
        assertEquals(HoursMode.NeedsPin, hoursMode(SettingsValues(pro = true, kidsPinSet = false)))
        assertEquals(HoursMode.Editor, hoursMode(pro))
    }

    @Test
    fun withoutProOrAPin_onlyBackDoesAnything_andItCloses() {
        for (values in listOf(SettingsValues(kidsPinSet = true), SettingsValues(pro = true))) {
            val panel = open()
            for (key in listOf(Key.DirectionDown, Key.DirectionCenter, Key.DirectionLeft)) {
                assertEquals(SettingsStep(panel), panel.press(key, values))
            }
            assertEquals(SettingsStep(SettingsPanel(SettingsRow.Hours)), panel.release(Key.Back, values))
        }
    }

    @Test
    fun theListWalksThePeriodsInDayOrder_thenAddAndStopsAtTheEnds() {
        var panel = open()
        assertEquals(listOf(morning, night), withTwo.hours.sortedPeriods())
        panel = checkNotNull(panel.press(Key.DirectionUp, withTwo).panel)
        assertEquals(0, panel.hours?.focus)
        repeat(5) { panel = checkNotNull(panel.press(Key.DirectionDown, withTwo).panel) }
        // Two periods, then the Add row.
        assertEquals(2, panel.hours?.focus)
    }

    @Test
    fun okOnAPeriodEditsIt_andOnAddStartsANewOne() {
        val first =
            open()
                .press(Key.DirectionCenter, withTwo)
                .panel
                ?.hours
                ?.draft
        assertEquals(HoursDraft.of(0, morning), first)
        val add =
            open(HoursScreen(focus = 2))
                .press(Key.DirectionCenter, withTwo)
                .panel
                ?.hours
                ?.draft
        assertEquals(HoursDraft.new(), add)
        // No periods: the Add row is the only row.
        assertEquals(
            HoursDraft.new(),
            open()
                .press(Key.DirectionCenter, pro)
                .panel
                ?.hours
                ?.draft,
        )
    }

    @Test
    fun theEditorWalksItsRows_andADeleteRowIsOnlyOnAPeriodThatIsThere() {
        assertEquals(
            listOf(HoursField.Day, HoursField.From, HoursField.To, HoursField.Level, HoursField.Save),
            HoursDraft.new().fields,
        )
        assertEquals(HoursField.Delete, HoursDraft.of(0, morning).fields.last())
        val editing = open(HoursScreen(draft = HoursDraft.new()))
        val down = editing.pressed(pro, List(9) { Key.DirectionDown })
        assertEquals(
            HoursField.Save,
            down.panel
                ?.hours
                ?.draft
                ?.field,
        )
        val up = checkNotNull(down.panel).pressed(pro, List(9) { Key.DirectionUp })
        assertEquals(
            HoursField.Day,
            up.panel
                ?.hours
                ?.draft
                ?.field,
        )
    }

    @Test
    fun leftAndRightStepAValue_andOkStepsItOn() {
        val day = open(HoursScreen(draft = HoursDraft.new()))
        assertEquals(
            2,
            day
                .press(Key.DirectionRight, pro)
                .panel
                ?.hours
                ?.draft
                ?.day,
        )
        assertEquals(
            7,
            day
                .press(Key.DirectionLeft, pro)
                .panel
                ?.hours
                ?.draft
                ?.day,
        )
        assertEquals(
            2,
            day
                .press(Key.DirectionCenter, pro)
                .panel
                ?.hours
                ?.draft
                ?.day,
        )
    }

    @Test
    fun theDaysWrapRound() {
        assertEquals(1, stepDay(7, 1))
        assertEquals(7, stepDay(1, -1))
        assertEquals(4, stepDay(3, 1))
    }

    @Test
    fun theTimesStepByFifteenMinutes_andWrapRoundTheDay() {
        assertEquals(15, stepStart(0, 7 * 60, 1))
        assertEquals(1425, stepStart(0, 7 * 60, -1))
        assertEquals(0, stepStart(1425, 7 * 60, 1))
        assertEquals(15, stepEnd(1440, 0, 1))
        assertEquals(1440, stepEnd(15, 0, -1))
        assertEquals(7 * 60 + 15, stepEnd(7 * 60, 20 * 60, 1))
    }

    @Test
    fun aTimeOffTheGridMovesToTheNearestStepInTheDirectionPressed() {
        assertEquals(15, stepStart(7, 600, 1))
        assertEquals(0, stepStart(7, 600, -1))
        assertEquals(30, stepEnd(20, 600, 1))
        assertEquals(15, stepEnd(20, 600, -1))
        // An end at 0 (the domain allows it) steps to the first and last steps.
        assertEquals(15, stepEnd(0, 600, 1))
        assertEquals(1425, stepEnd(0, 600, -1))
    }

    @Test
    fun aTimeNeverLandsOnTheOtherEnd_soThePeriodIsNeverEmpty() {
        assertEquals(60, stepStart(30, 45, 1))
        assertEquals(30, stepStart(60, 45, -1))
        assertEquals(60, stepEnd(30, 45, 1))
        assertEquals(30, stepEnd(60, 45, -1))
    }

    @Test
    fun theLevelsWrapRound_includingOffAir() {
        assertEquals(AudienceLevel.OffAir, stepLevel(AudienceLevel.Kids, 1))
        assertEquals(AudienceLevel.Adult, stepLevel(AudienceLevel.OffAir, 1))
        assertEquals(AudienceLevel.OffAir, stepLevel(AudienceLevel.Adult, -1))
        assertEquals(AudienceLevel.Family, stepLevel(AudienceLevel.Teen, 1))
    }

    @Test
    fun aPeriodThatEndsBeforeItStartsRunsOvernight() {
        assertTrue(night.isOvernight)
        assertTrue(!morning.isOvernight)
        assertTrue(HoursDraft.of(0, night).overnight)
        assertTrue(!HoursDraft.of(0, morning).overnight)
        // Stepping the end across the start flips it: from 6 AM past a 5:45 AM start is the same day, then.
        val draft = HoursDraft(null, 1, 5 * 60 + 45, 6 * 60, AudienceLevel.Kids)
        assertTrue(!draft.overnight)
        assertTrue(draft.copy(field = HoursField.To).stepped(-1).overnight)
        // 24:00 is the end of the same day, never overnight.
        assertTrue(!draft.copy(end = 1440).overnight)
    }

    @Test
    fun saveAsksForTheScheduleWithTheNewPeriodAdded_inOrder() {
        val draft = HoursDraft.new().copy(field = HoursField.Save)
        val step = open(HoursScreen(draft = draft)).press(Key.DirectionCenter, withTwo)
        val expected = HoursSchedule(listOf(morning, night, draft.toPeriod()))
        assertEquals(expected, step.hoursSave)
        assertTrue(step.panel?.hours?.busy == true)
    }

    @Test
    fun saveReplacesTheEditedPeriod_andDeleteRemovesIt() {
        val edit = HoursDraft.of(1, night).copy(level = AudienceLevel.Teen, field = HoursField.Save)
        val saved = open(HoursScreen(draft = edit)).press(Key.DirectionCenter, withTwo)
        assertEquals(HoursSchedule(listOf(morning, night.copy(level = AudienceLevel.Teen))), saved.hoursSave)
        val gone = open(HoursScreen(draft = edit.copy(field = HoursField.Delete))).press(Key.DirectionCenter, withTwo)
        assertEquals(HoursSchedule(listOf(morning)), gone.hoursSave)
    }

    @Test
    fun whileASaveIsAwaitedNothingButBackIsTaken() {
        val busy = open(HoursScreen(draft = HoursDraft.new(), busy = true))
        for (key in listOf(Key.DirectionDown, Key.DirectionRight, Key.DirectionCenter)) {
            assertEquals(SettingsStep(busy), busy.press(key, pro))
        }
        // Back leaves the editor, and a late answer is ignored.
        val left = checkNotNull(busy.release(Key.Back, pro).panel)
        assertEquals(HoursScreen(), left.hours)
        assertEquals(left, left.hoursSaved(HoursEdit.Saved))
    }

    @Test
    fun saved_returnsToTheListSayingSo() {
        val draft = HoursDraft.new().copy(field = HoursField.Save)
        val after = open(HoursScreen(draft = draft, busy = true)).hoursSaved(HoursEdit.Saved)
        assertEquals(HoursScreen(draft = null, notice = HoursEdit.Saved), after.hours)
    }

    @Test
    fun everyOtherResult_staysInTheEditorWithTheReasonSaid() {
        val draft = HoursDraft.new().copy(field = HoursField.Save)
        for (edit in listOf(HoursEdit.ProRequired, HoursEdit.NoPin, HoursEdit.Unavailable)) {
            val after = open(HoursScreen(draft = draft, busy = true)).hoursSaved(edit)
            assertEquals(edit.name, HoursScreen(draft = draft, notice = edit), after.hours)
        }
    }

    @Test
    fun everyResultHasItsOwnWords() {
        val words = HoursEdit.entries.map { hoursNoticeWords(it) }
        assertEquals(HoursEdit.entries.size, words.toSet().size)
    }

    @Test
    fun aNoticeGoesWithTheNextKey() {
        val noticed = open(HoursScreen(notice = HoursEdit.Saved))
        assertNull(
            noticed
                .press(Key.DirectionDown, withTwo)
                .panel
                ?.hours
                ?.notice,
        )
    }

    @Test
    fun backInTheEditorReturnsToTheList_andBackOnTheListClosesTheScreen() {
        val editing = open(HoursScreen(focus = 1, draft = HoursDraft.new()))
        // The press does nothing; the release acts.
        assertEquals(SettingsStep(editing), editing.press(Key.Back, pro))
        val list = checkNotNull(editing.release(Key.Back, pro).panel)
        assertEquals(HoursScreen(focus = 1), list.hours)
        assertEquals(SettingsStep(SettingsPanel(SettingsRow.Hours)), list.release(Key.Back, pro))
    }

    @Test
    fun theMediaKeysAreNotTheScreens() {
        assertNull(open().hoursKey(HoursScreen(), pro, Key.MediaPlayPause, KeyEventType.KeyDown, 0))
        assertNull(open().hoursKey(HoursScreen(), pro, Key.Menu, KeyEventType.KeyDown, 0))
    }

    @Test
    fun theHintSaysWhatOkDoesOnEachRow() {
        fun said(
            screen: HoursScreen,
            values: SettingsValues = withTwo,
        ) = hoursHintOf(screen, values).map { it.action }

        assertEquals(listOf(R.string.guide_hint_hours_edit, UiR.string.hint_close), said(HoursScreen()))
        assertEquals(listOf(R.string.guide_hint_hours_add, UiR.string.hint_close), said(HoursScreen(focus = 2)))
        val draft = HoursDraft.new()
        val step = listOf(R.string.guide_hint_change, R.string.guide_hint_next, R.string.guide_hint_pin_cancel)
        assertEquals(step, said(HoursScreen(draft = draft)))
        assertEquals(
            listOf(R.string.guide_hint_hours_save, R.string.guide_hint_pin_cancel),
            said(HoursScreen(draft = draft.copy(field = HoursField.Save))),
        )
        assertEquals(
            listOf(R.string.guide_hint_hours_delete, R.string.guide_hint_pin_cancel),
            said(HoursScreen(draft = HoursDraft.of(0, night).copy(field = HoursField.Delete))),
        )
        assertEquals(listOf(UiR.string.hint_close), said(HoursScreen(), SettingsValues()))
    }

    // The real feature behind the screen's save: the screen's schedule is what it stores, and each refusal is one of
    // the results the screen has words for.
    @Test
    fun theScreensSaveGoesThroughTheFeature_andEachResultIsOneItSays() =
        runTest {
            val store = InMemoryHoursStore()
            val entitlement = FakeProEntitlement(initial = true)
            val lock = FakeKidsLock()
            val hours = HoursOfOperation(store, entitlement, lock)
            val schedule = HoursSchedule(listOf(night))
            assertEquals(HoursEdit.NoPin, hours.edit(schedule))
            lock.setPin("1234")
            assertEquals(HoursEdit.Saved, hours.edit(schedule))
            assertEquals(schedule, store.current.value)
            store.failWrites = true
            assertEquals(HoursEdit.Unavailable, hours.edit(HoursSchedule.EMPTY))
            entitlement.set(false)
            assertEquals(HoursEdit.ProRequired, hours.edit(HoursSchedule.EMPTY))
            assertEquals(schedule, store.current.value)
        }
}
