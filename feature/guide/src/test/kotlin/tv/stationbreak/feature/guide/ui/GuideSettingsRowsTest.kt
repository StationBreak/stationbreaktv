// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.ui.ChannelChangeStyle
import tv.stationbreak.core.ui.ScreenFit
import tv.stationbreak.feature.guide.R
import tv.stationbreak.core.ui.R as UiR

/** The Settings sheet's rows: their sections, kinds, values, words and hints. */
class GuideSettingsRowsTest {
    // The English strings by resource name, this module's and core:ui's (the hint keys), read from the files: a unit
    // test has no resources to resolve.
    private val strings: Map<String, String> by lazy {
        val entry = Regex("""<string name="([^"]+)">([^<]*)</string>""")
        listOf("src/main/res/values/strings.xml", "../../core/ui/src/main/res/values/strings.xml")
            .flatMap { entry.findAll(java.io.File(it).readText()).toList() }
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("&#183;", "\u00b7") }
    }

    private fun text(id: Int): String {
        val name =
            (R.string::class.java.fields + UiR.string::class.java.fields)
                .first { it.getInt(null) == id }
                .name
        return checkNotNull(strings[name]) { "no string $name" }
    }

    @Test
    fun theSectionsHoldEveryRowOnce_inTheSheetsOrder() {
        assertEquals(SettingsRow.entries, SettingsGroup.entries.flatMap { it.rows })
        assertEquals(
            listOf("Picture", "Playback", "Kids Mode", "Library", "Account", "Privacy"),
            SettingsGroup.entries.map { text(it.flag) },
        )
        assertEquals(listOf(SettingsRow.Appearance, SettingsRow.ScreenFit), SettingsGroup.Picture.rows)
        assertEquals(listOf(SettingsRow.ChannelChange, SettingsRow.VolumeLevelling), SettingsGroup.Playback.rows)
        assertEquals(listOf(SettingsRow.KidsMode, SettingsRow.ChangePin), SettingsGroup.Kids.rows)
        assertEquals(listOf(SettingsRow.Libraries, SettingsRow.SyncNow), SettingsGroup.Library.rows)
        assertEquals(listOf(SettingsRow.SignOut), SettingsGroup.Account.rows)
        assertEquals(listOf(SettingsRow.CrashReports), SettingsGroup.Privacy.rows)
    }

    @Test
    fun eachRowSaysWhatItIs_andItsLabel() {
        val expected =
            mapOf(
                SettingsRow.Appearance to (RowKind.Choice to "Appearance"),
                SettingsRow.ScreenFit to (RowKind.Choice to "Screen fit"),
                SettingsRow.ChannelChange to (RowKind.Choice to "When changing channel"),
                SettingsRow.VolumeLevelling to (RowKind.Choice to "Even out volume"),
                SettingsRow.KidsMode to (RowKind.Action to "Kids Mode"),
                SettingsRow.ChangePin to (RowKind.Action to "Change PIN"),
                SettingsRow.Libraries to (RowKind.Link to "Libraries"),
                SettingsRow.SyncNow to (RowKind.Action to "Update library"),
                SettingsRow.SignOut to (RowKind.Action to "Sign out"),
                SettingsRow.CrashReports to (RowKind.Choice to "Send crash reports"),
            )
        assertEquals(SettingsRow.entries.toSet(), expected.keys)
        for ((row, kindAndLabel) in expected) {
            assertEquals(row.name, kindAndLabel.first, row.kind)
            assertEquals(kindAndLabel.second, text(row.label))
        }
    }

    @Test
    fun aChoiceShowsItsValue_andWhereItStands() {
        fun shown(
            row: SettingsRow,
            values: SettingsValues,
        ) = checkNotNull(choiceShown(row, values)).let { Triple(text(it.value), it.index, it.count) }

        val v = SettingsValues()
        assertEquals(Triple("Dark", 0, 2), shown(SettingsRow.Appearance, v))
        assertEquals(Triple("Light", 1, 2), shown(SettingsRow.Appearance, v.copy(theme = GuideTheme.Light)))
        // Off until the viewer turns it on.
        assertEquals(Triple("Off", 0, 2), shown(SettingsRow.CrashReports, v))
        assertEquals(Triple("On", 1, 2), shown(SettingsRow.CrashReports, v.copy(crashReports = true)))
        assertEquals(Triple("Safe", 0, 2), shown(SettingsRow.ScreenFit, v))
        assertEquals(Triple("Edge", 1, 2), shown(SettingsRow.ScreenFit, v.copy(fit = ScreenFit.Edge)))
        // Named by what the viewer sees, never by Stan's name; the stand-by card is the default.
        assertEquals(Triple("Stand-by card", 0, 2), shown(SettingsRow.ChannelChange, v))
        assertEquals(
            Triple("Static", 1, 2),
            shown(SettingsRow.ChannelChange, v.copy(channelChange = ChannelChangeStyle.Snow)),
        )
        assertEquals(Triple("On", 1, 2), shown(SettingsRow.VolumeLevelling, v))
        assertEquals(Triple("Off", 0, 2), shown(SettingsRow.VolumeLevelling, v.copy(levelling = false)))
        for (row in listOf(SettingsRow.Libraries, SettingsRow.SyncNow, SettingsRow.SignOut)) {
            assertNull(choiceShown(row, v))
        }
    }

    @Test
    fun theDefaultsAreTheViewersDefaults() {
        assertEquals(SettingsValues(GuideTheme.Dark, ScreenFit.Safe, ChannelChangeStyle.Stan, true), SettingsValues())
    }

    @Test
    fun theHintSaysWhatTheKeysDo_backLast() {
        fun shown(row: SettingsRow) = settingsHintOf(row).joinToString("   ") { "${text(it.key)} ${text(it.action)}" }

        fun said(row: SettingsRow) =
            settingsHintOf(row).joinToString("   ") {
                "${text(it.spokenKey ?: it.key)} ${text(it.action)}"
            }

        assertEquals("◂ ▸ Change   OK Next   Back Close", shown(SettingsRow.Appearance))
        // Read out, the arrows are words.
        assertEquals("Left or right Change   OK Next   Back Close", said(SettingsRow.Appearance))
        assertEquals("OK Open   Back Close", said(SettingsRow.Libraries))
        // An action's OK says the row's own verb.
        assertEquals("OK Update   Back Close", said(SettingsRow.SyncNow))
        assertEquals("OK Sign out   Back Close", said(SettingsRow.SignOut))
        // OK is the one round cap.
        for (row in SettingsRow.entries) {
            assertEquals(listOf(UiR.string.hint_key_ok), settingsHintOf(row).filter { it.round }.map { it.key })
        }
    }

    @Test
    fun kidsModeOffersWhatItCanDoNow() {
        fun said(values: SettingsValues) =
            settingsHintOf(SettingsRow.KidsMode, values).joinToString("   ") { "${text(it.key)} ${text(it.action)}" }

        assertEquals("◂ ▸ Change   OK Set a PIN   Back Close", said(SettingsValues()))
        assertEquals("◂ ▸ Change   OK Turn on   Back Close", said(SettingsValues(kidsPinSet = true)))
        assertEquals("◂ ▸ Change   OK Turn off   Back Close", said(SettingsValues(kidsOn = true, kidsPinSet = true)))
        // Until the lock has been read, Left and Right do nothing, so the hint does not offer them.
        assertEquals("OK Turn off   Back Close", said(SettingsValues(kidsOn = true, kidsKnown = false)))
    }

    private fun kidsValue(
        values: SettingsValues,
        spoken: Boolean = false,
    ): String {
        val shown = kidsModeShown(values, spoken)
        val cap = shown.cap?.let { text(it) }
        return if (cap == null) text(shown.format) else text(shown.format).replace("%1\$s", cap)
    }

    @Test
    fun kidsModeShowsOnOrOffWithItsCap_andSaysItWithoutTheDot() {
        fun on(cap: KidsCap) = SettingsValues(kidsOn = true, kidsCap = cap)

        assertEquals("On \u00b7 up to Kids", kidsValue(on(KidsCap.Kids)))
        assertEquals("On \u00b7 up to Family", kidsValue(on(KidsCap.Family)))
        assertEquals("On \u00b7 up to Teen", kidsValue(on(KidsCap.Teen)))
        assertEquals("Off \u00b7 Kids", kidsValue(SettingsValues()))
        assertEquals("Off \u00b7 Teen", kidsValue(SettingsValues(kidsCap = KidsCap.Teen)))
        assertEquals("On, up to Family", kidsValue(on(KidsCap.Family), spoken = true))
        assertEquals("Off, cap Kids", kidsValue(SettingsValues(), spoken = true))
        assertEquals(R.string.guide_kids_unknown, kidsModeShown(SettingsValues(kidsKnown = false)).format)
    }

    @Test
    fun anUnreadLockClosesEveryInertRowAndOnlyTheRowsWordsSayUnknown() {
        val unread = SettingsValues(kidsOn = true, kidsKnown = false)
        for (row in listOf(SettingsRow.Libraries, SettingsRow.SyncNow, SettingsRow.SignOut)) {
            assertTrue(row.name, row.isInert(unread))
        }
        assertEquals(R.string.guide_kids_unknown, kidsModeShown(unread).format)
    }

    @Test
    fun changePinIsThereOnlyWithAPinSetAndKidsModeOff() {
        assertEquals(false, SettingsRow.ChangePin.isShown(SettingsValues()))
        assertEquals(true, SettingsRow.ChangePin.isShown(SettingsValues(kidsPinSet = true)))
        assertEquals(false, SettingsRow.ChangePin.isShown(SettingsValues(kidsPinSet = true, kidsOn = true)))
    }

    @Test
    fun kidsModeSwitchesOffExactlyTheRowsThatCouldUndoIt_andTheirHintHasOnlyBack() {
        val on = SettingsValues(kidsOn = true, kidsPinSet = true)
        val inert = SettingsRow.entries.filter { it.isInert(on) }
        assertEquals(listOf(SettingsRow.Libraries, SettingsRow.SyncNow, SettingsRow.SignOut), inert)
        val pinOnly = SettingsValues(kidsPinSet = true)
        assertEquals(emptyList<SettingsRow>(), SettingsRow.entries.filter { it.isInert(pinOnly) })
        for (row in inert) {
            assertEquals(listOf("Back Close"), settingsHintOf(row, on).map { "${text(it.key)} ${text(it.action)}" })
        }
        assertEquals("Turn off Kids Mode first", text(R.string.guide_kids_locked))
    }

    @Test
    fun theSheetsOtherWords() {
        assertEquals("Settings", text(R.string.guide_settings_button))
        assertEquals("›", text(R.string.guide_settings_chevron))
        assertEquals("%1\$s, %2\$s", text(R.string.guide_settings_spoken))
    }
}
