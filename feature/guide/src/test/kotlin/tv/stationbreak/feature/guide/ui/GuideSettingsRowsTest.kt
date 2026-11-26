// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.stationbreak.core.domain.kids.KidsCap
import tv.stationbreak.core.domain.store.ProOffer
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
            listOf("Picture", "Playback", "Family Mode", "Library", "Account", "Privacy", "Help"),
            SettingsGroup.entries.map { text(it.flag) },
        )
        assertEquals(listOf(SettingsRow.Appearance, SettingsRow.ScreenFit), SettingsGroup.Picture.rows)
        assertEquals(listOf(SettingsRow.ChannelChange, SettingsRow.VolumeLevelling), SettingsGroup.Playback.rows)
        assertEquals(
            listOf(SettingsRow.KidsMode, SettingsRow.KidsCap, SettingsRow.ChangePin, SettingsRow.Hours),
            SettingsGroup.Kids.rows,
        )
        assertEquals(listOf(SettingsRow.Libraries, SettingsRow.SyncNow), SettingsGroup.Library.rows)
        assertEquals(listOf(SettingsRow.Pro, SettingsRow.SignOut), SettingsGroup.Account.rows)
        assertEquals(listOf(SettingsRow.CrashReports), SettingsGroup.Privacy.rows)
        assertEquals(listOf(SettingsRow.UserGuide), SettingsGroup.Help.rows)
        assertEquals(
            SettingsRow.UserGuide,
            SettingsGroup.entries
                .last()
                .rows
                .last(),
        )
    }

    @Test
    fun hoursOfOperationIsOnTheSheetOnlyWithPro() {
        assertEquals(false, SettingsRow.Hours.isShown(SettingsValues()))
        assertEquals(true, SettingsRow.Hours.isShown(SettingsValues(pro = true)))
    }

    @Test
    fun hoursOfOperationStaysHiddenForAFreeViewer_whateverTheStoreOrTheOffer() {
        // A screen, not a refused action (#1076): a free viewer gets no Pro row on every Settings sheet.
        assertEquals(false, SettingsRow.Hours.isShown(SettingsValues(storeAvailable = true)))
        assertEquals(
            false,
            SettingsRow.Hours.isShown(SettingsValues(offer = ProOffer.Support("https://example.test/support"))),
        )
    }

    @Test
    fun theProRowIsOnTheSheetOnlyWithAStoreOrASupportPage_andSaysSupportInABuildWithNoStore() {
        // The Amazon release before its adapter: the store is not available, so there is no Pro row at all.
        assertEquals(false, SettingsRow.Pro.isShown(SettingsValues()))
        // An owner whose adapter is offline still sees "You have Pro".
        assertEquals(true, SettingsRow.Pro.isShown(SettingsValues(pro = true)))
        val store = SettingsValues(storeAvailable = true)
        assertEquals(true, SettingsRow.Pro.isShown(store))
        assertEquals("StationBreak Pro", text(SettingsRow.Pro.labelFor(store)))
        val github = SettingsValues(offer = ProOffer.Support("https://example.test/support"))
        assertEquals(true, SettingsRow.Pro.isShown(github))
        assertEquals("Support StationBreak", text(SettingsRow.Pro.labelFor(github)))
        assertEquals("Sign out", text(SettingsRow.SignOut.labelFor(github)))
    }

    @Test
    fun eachRowSaysWhatItIs_andItsLabel() {
        val expected =
            mapOf(
                SettingsRow.Appearance to (RowKind.Choice to "Appearance"),
                SettingsRow.ScreenFit to (RowKind.Choice to "Screen fit"),
                SettingsRow.ChannelChange to (RowKind.Choice to "When changing channel"),
                SettingsRow.VolumeLevelling to (RowKind.Choice to "Even out volume"),
                SettingsRow.KidsMode to (RowKind.Action to "Family Mode"),
                SettingsRow.KidsCap to (RowKind.Choice to "Cap"),
                SettingsRow.ChangePin to (RowKind.Action to "Change PIN"),
                SettingsRow.Hours to (RowKind.Link to "Hours of operation"),
                SettingsRow.Libraries to (RowKind.Link to "Libraries"),
                SettingsRow.SyncNow to (RowKind.Action to "Update library"),
                SettingsRow.Pro to (RowKind.Link to "StationBreak Pro"),
                SettingsRow.SignOut to (RowKind.Action to "Sign out"),
                SettingsRow.CrashReports to (RowKind.Choice to "Send crash reports"),
                SettingsRow.UserGuide to (RowKind.Link to "User guide"),
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

    // Family Mode's value: On or Off and nothing else (#1151), or null until the stored lock has been read.
    private fun kidsValue(values: SettingsValues): String? {
        val shown = choiceShown(SettingsRow.KidsMode, values) ?: return null
        return text(shown.value)
    }

    @Test
    fun eachCapHasALineSayingWhichChannelsItEnables_matchingItsRange() {
        for (cap in KidsCap.entries) {
            val line = text(kidsCapChannels(cap))
            assertTrue("$line says 1 to ${cap.upTo}", line.startsWith("Channels 1 to ${cap.upTo}"))
        }
    }

    @Test
    fun familyModeShowsOnlyOnOrOff_andTheCapIsARowOfItsOwn() {
        assertEquals("On", kidsValue(SettingsValues(kidsOn = true, kidsCap = KidsCap.Teen)))
        assertEquals("Off", kidsValue(SettingsValues(kidsCap = KidsCap.Teen)))
        assertEquals(
            ChoiceShown(R.string.guide_kids_on, index = 1, count = 2),
            choiceShown(SettingsRow.KidsMode, SettingsValues(kidsOn = true)),
        )
        assertNull("until the lock has been read", kidsValue(SettingsValues(kidsKnown = false)))
        // The Cap row is drawn by the shared segmented row, not as a choice with dots.
        assertNull(choiceShown(SettingsRow.KidsCap, SettingsValues()))
        assertEquals(listOf("Tots", "Kids", "Teens"), KidsCap.entries.map { text(kidsCapWords(it)) })
        assertEquals("Cap", text(R.string.guide_settings_kids_cap))
    }

    @Test
    fun theCapRowIsRightUnderFamilyMode_andItsHintIsAChoices() {
        val kids = SettingsGroup.Kids.rows
        assertEquals(SettingsRow.KidsCap, kids[kids.indexOf(SettingsRow.KidsMode) + 1])
        assertEquals(
            "\u25c2 \u25b8 Change   OK Next   Back Close",
            settingsHintOf(SettingsRow.KidsCap).joinToString("   ") { "${text(it.key)} ${text(it.action)}" },
        )
    }

    @Test
    fun theCapRowsHelpSaysItsChannelsAndThePin_andFamilyModesHelpNoLongerNamesThem() {
        val values = SettingsValues(kidsCap = KidsCap.Family)
        val cap = checkNotNull(settingsHelpOf(SettingsRow.KidsCap, values))
        assertEquals(R.string.guide_kids_cap_kids_channels, cap.lead)
        assertEquals(R.string.guide_help_family_cap, cap.text)
        assertNull(checkNotNull(settingsHelpOf(SettingsRow.KidsMode, values)).lead)
        val unread = SettingsValues(kidsKnown = false)
        assertNull("nothing until the lock has been read", settingsHelpOf(SettingsRow.KidsCap, unread))
    }

    @Test
    fun anUnreadLockClosesEveryInertRowAndOnlyTheRowsWordsSayUnknown() {
        val unread = SettingsValues(kidsOn = true, kidsKnown = false)
        for (row in listOf(SettingsRow.Libraries, SettingsRow.SyncNow, SettingsRow.SignOut)) {
            assertTrue(row.name, row.isInert(unread))
        }
        assertNull(choiceShown(SettingsRow.KidsMode, unread))
        assertTrue(text(R.string.guide_kids_unknown).isNotEmpty())
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
        assertEquals(
            listOf(
                SettingsRow.Hours,
                SettingsRow.Libraries,
                SettingsRow.SyncNow,
                SettingsRow.Pro,
                SettingsRow.SignOut,
                SettingsRow.UserGuide,
            ),
            inert,
        )
        val pinOnly = SettingsValues(kidsPinSet = true)
        assertEquals(emptyList<SettingsRow>(), SettingsRow.entries.filter { it.isInert(pinOnly) })
        for (row in inert) {
            assertEquals(listOf("Back Close"), settingsHintOf(row, on).map { "${text(it.key)} ${text(it.action)}" })
        }
        assertEquals("Turn off Family Mode first", text(R.string.guide_kids_locked))
    }

    @Test
    fun theUserGuideRowIsAlwaysShown_andItsWordsAreTheApprovedOnes() {
        assertEquals(true, SettingsRow.UserGuide.isShown(SettingsValues()))
        assertEquals("Help", text(R.string.guide_settings_group_help))
        assertEquals("User guide", text(R.string.guide_help_qr_title))
        assertEquals("Scan this with your phone's camera.", text(R.string.guide_help_qr_prompt))
        assertEquals("stationbreak.tv/guide", text(R.string.guide_help_url_text))
        assertEquals(
            "QR code for the user guide. The address is stationbreak.tv slash guide.",
            text(R.string.guide_help_qr_spoken),
        )
        val said = "Shows a QR code for the user guide, to scan with your phone."
        assertEquals(said, helpWords(SettingsRow.UserGuide, SettingsValues()))
        // OK opens it, Back closes the sheet.
        val hint = settingsHintOf(SettingsRow.UserGuide).map { "${text(it.key)} ${text(it.action)}" }
        assertEquals(listOf("OK Open", "Back Close"), hint)
    }

    @Test
    fun theUserGuideRowShowsTheLockedNoteInTheWellUnderFamilyMode() {
        val on = SettingsValues(kidsOn = true, kidsPinSet = true)
        assertTrue(SettingsRow.UserGuide.isInert(on))
        assertEquals("Turn off Family Mode first", helpWords(SettingsRow.UserGuide, on))
        assertEquals(false, SettingsRow.UserGuide.isInert(SettingsValues(kidsPinSet = true)))
    }

    @Test
    fun theSheetsOtherWords() {
        assertEquals("Settings", text(R.string.guide_settings_button))
        assertEquals("›", text(R.string.guide_settings_chevron))
        assertEquals("%1\$s, %2\$s", text(R.string.guide_settings_spoken))
    }

    // The words the help well shows for a row, as the sheet resolves them: a lead line, then the text, with the app's
    // name where the text takes one.
    private fun helpWords(
        row: SettingsRow,
        values: SettingsValues,
        appName: String = "StationBreak",
    ): String? =
        settingsHelpOf(row, values)?.let { help ->
            val body = text(help.text).replace("%1\$s", appName)
            help.lead?.let { "${text(it)} $body" } ?: body
        }

    @Test
    fun everyRowHasAnExplainer_andTheyAreAllDifferent() {
        val values = SettingsValues(pro = true, storeAvailable = true, crashReportsAvailable = true, kidsPinSet = true)
        val said = SettingsRow.entries.map { checkNotNull(helpWords(it, values)) { "${it.name} is silent" } }
        assertEquals(said.size, said.toSet().size)
    }

    @Test
    fun noExplainerNamesAWayRoundFamilyModeOrAPriceATrialOrWhenItArrives() {
        val banned = listOf("forgot", "grown-up", "switch", "price", "trial", "free", "soon", "later", "coming")
        val all = strings.filterKeys { it.startsWith("guide_help_") }.values
        assertEquals(20, all.size)
        for (words in all) {
            for (word in banned) assertTrue("$words has $word", !words.lowercase().contains(word))
        }
    }

    @Test
    fun theProductNameComesFromTheAppNameNotTheStrings() {
        val named = SettingsRow.entries.filter { settingsHelpOf(it, SettingsValues())?.named == true }
        assertEquals(listOf(SettingsRow.SyncNow, SettingsRow.Pro, SettingsRow.CrashReports), named)
        for (row in named) {
            assertTrue(text(checkNotNull(settingsHelpOf(row, SettingsValues())).text).contains("%1\$s"))
        }
        val pro = "Pro adds more ways to change your lineup."
        assertEquals("StationBreak $pro", helpWords(SettingsRow.Pro, SettingsValues()))
        assertEquals("Test TV $pro", helpWords(SettingsRow.Pro, SettingsValues(), "Test TV"))
    }

    @Test
    fun theSupportBuildsProRowSaysWhatSupportDoes() {
        val github = SettingsValues(offer = ProOffer.Support("https://example.test/support"))
        val said = "Shows how to support StationBreak. It changes nothing in the app."
        assertEquals(said, helpWords(SettingsRow.Pro, github))
    }

    @Test
    fun aRowFamilyModeHasLockedShowsItsLockedNoteInTheWell_andNoExplainerIsSpoken() {
        val on = SettingsValues(kidsOn = true, kidsPinSet = true)
        for (row in SettingsRow.entries.filter { it.isInert(on) }) {
            val help = checkNotNull(settingsHelpOf(row, on))
            assertTrue(row.name, help.locked)
            assertEquals("Turn off Family Mode first", helpWords(row, on))
        }
        // The rows that stay the viewer's keep their explainers.
        assertEquals("Dark for a dim room, Light for a bright one.", helpWords(SettingsRow.Appearance, on))
        assertEquals(false, checkNotNull(settingsHelpOf(SettingsRow.Appearance, on)).locked)
    }

    @Test
    fun theCapRowsExplainerFollowsTheCapChosen_andIsSilentUntilTheLockIsRead() {
        fun said(cap: KidsCap) = checkNotNull(helpWords(SettingsRow.KidsCap, SettingsValues(kidsCap = cap)))
        assertTrue(said(KidsCap.Kids).startsWith("Channels 1 to 33. "))
        assertTrue(said(KidsCap.Family).startsWith("Channels 1 to 66: Tots and Kids. "))
        assertTrue(said(KidsCap.Teen).startsWith("Channels 1 to 99: Tots, Kids and Teens. "))
        assertTrue(said(KidsCap.Teen).endsWith("Changing the cap while Family Mode is on needs your PIN."))
        assertNull(settingsHelpOf(SettingsRow.KidsCap, SettingsValues(kidsKnown = false)))
    }

    @Test
    fun familyModesExplainerIsOnOrOffAndNamesNoCap_andIsSilentUntilTheLockIsRead() {
        val off = "Keeps this TV to the channels of its cap. Turning it off needs your PIN."
        assertEquals(off, helpWords(SettingsRow.KidsMode, SettingsValues(kidsCap = KidsCap.Family)))
        val on = SettingsValues(kidsOn = true, kidsCap = KidsCap.Family)
        val said =
            "Only the channels of its cap play. Everything above stays hidden until the PIN turns Family Mode off."
        assertEquals(said, helpWords(SettingsRow.KidsMode, on))
        assertNull(settingsHelpOf(SettingsRow.KidsMode, SettingsValues(kidsOn = true, kidsKnown = false)))
    }

    @Test
    fun aRowWithAnExplainerIsSpokenAsLabelValueExplainer() {
        assertEquals("%1\$s, %2\$s. %3\$s", text(R.string.guide_settings_spoken_help))
    }
}
