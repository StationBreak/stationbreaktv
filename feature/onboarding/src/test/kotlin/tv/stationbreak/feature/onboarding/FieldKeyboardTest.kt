// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** What each onboarding field asks the keyboard for (#810), and where the remote-app tip is shown. */
class FieldKeyboardTest {
    private fun check(
        options: KeyboardOptions,
        type: KeyboardType,
        action: ImeAction,
    ) {
        assertEquals(type, options.keyboardType)
        assertEquals(action, options.imeAction)
        assertEquals(KeyboardCapitalization.None, options.capitalization)
        assertEquals(false, options.autoCorrectEnabled)
    }

    @Test
    fun theAddressIsAUriWithGo() = check(FieldKeyboard.Address, KeyboardType.Uri, ImeAction.Go)

    @Test
    fun theUsernameIsTextWithNext() = check(FieldKeyboard.Username, KeyboardType.Text, ImeAction.Next)

    @Test
    fun thePasswordIsAPasswordWithDone() = check(FieldKeyboard.Password, KeyboardType.Password, ImeAction.Done)

    @Test
    fun onlyTheServerAndSignInScreensShowTheTip() {
        val users =
            File("src/main/kotlin")
                .walkTopDown()
                .filter { it.isFile && it.name != "TvTextField.kt" && it.readText().contains("RemoteAppTip(") }
                .map { it.name }
                .toSet()
        assertEquals(setOf("ServerScreen.kt", "SignInScreen.kt"), users)
    }

    @Test
    fun bothTipWordingsStartTheSameWayAndDoNotNameTheProduct() {
        val files = listOf("src/main/res/values/strings.xml", "../../app/src/amazon/res/values/strings.xml")
        for (path in files) {
            val nodes =
                DocumentBuilderFactory
                    .newInstance()
                    .newDocumentBuilder()
                    .parse(File(path))
                    .getElementsByTagName("string")
            val tip =
                (0 until nodes.length)
                    .map { nodes.item(it) }
                    .single { it.attributes.getNamedItem("name").nodeValue == "remote_app_tip" }
            assertTrue(path, tip.textContent.startsWith("Tip: you can type with your phone"))
            assertFalse(path, tip.textContent.contains("jellyfin", ignoreCase = true))
        }
    }
}
