// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.stationbreak.core.ui.ChannelChangeStyle
import tv.stationbreak.core.ui.ScreenFit
import tv.stationbreak.feature.guide.R

/** The Settings sheet's rows: their sections, kinds, values, words and hints. */
class GuideSettingsRowsTest {
    // The module's English strings by resource name, read from the file: a unit test has no resources to resolve.
    private val strings: Map<String, String> by lazy {
        val file = java.io.File("src/main/res/values/strings.xml").readText()
        val entry = Regex("""<string name="([^"]+)">([^<]*)</string>""")
        entry.findAll(file).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    private fun text(id: Int): String {
        val name =
            R.string::class.java.fields
                .single { it.getInt(null) == id }
                .name
        return checkNotNull(strings[name]) { "no string $name" }
    }

    @Test
    fun theSectionsHoldEveryRowOnce_inTheSheetsOrder() {
        assertEquals(SettingsRow.entries, SettingsGroup.entries.flatMap { it.rows })
        assertEquals(
            listOf("Picture", "Playback", "Library", "Account"),
            SettingsGroup.entries.map { text(it.flag) },
        )
        assertEquals(listOf(SettingsRow.Appearance, SettingsRow.ScreenFit), SettingsGroup.Picture.rows)
        assertEquals(listOf(SettingsRow.ChannelChange, SettingsRow.VolumeLevelling), SettingsGroup.Playback.rows)
        assertEquals(listOf(SettingsRow.Libraries, SettingsRow.SyncNow), SettingsGroup.Library.rows)
        assertEquals(listOf(SettingsRow.SignOut), SettingsGroup.Account.rows)
    }

    @Test
    fun eachRowSaysWhatItIs_andItsLabel() {
        val expected =
            mapOf(
                SettingsRow.Appearance to (RowKind.Choice to "Appearance"),
                SettingsRow.ScreenFit to (RowKind.Choice to "Screen fit"),
                SettingsRow.ChannelChange to (RowKind.Choice to "When changing channel"),
                SettingsRow.VolumeLevelling to (RowKind.Choice to "Even out volume"),
                SettingsRow.Libraries to (RowKind.Link to "Libraries"),
                SettingsRow.SyncNow to (RowKind.Action to "Update library"),
                SettingsRow.SignOut to (RowKind.Action to "Sign out"),
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
        fun said(kind: RowKind) = settingsHintOf(kind).joinToString("   ") { "${text(it.key)} ${text(it.action)}" }

        assertEquals("◂ ▸ Change   OK Next   Back Close", said(RowKind.Choice))
        assertEquals("OK Open   Back Close", said(RowKind.Link))
        assertEquals("OK Choose   Back Close", said(RowKind.Action))
        // OK is the one round cap.
        for (kind in RowKind.entries) {
            assertEquals(listOf(R.string.guide_key_ok), settingsHintOf(kind).filter { it.round }.map { it.key })
        }
    }

    @Test
    fun theSheetsOtherWords() {
        assertEquals("Settings", text(R.string.guide_settings_button))
        assertEquals("›", text(R.string.guide_settings_chevron))
        assertEquals("%1\$s, %2\$s", text(R.string.guide_settings_spoken))
    }
}
