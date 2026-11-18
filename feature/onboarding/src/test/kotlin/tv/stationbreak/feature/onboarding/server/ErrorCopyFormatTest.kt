// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.core.model.ServerVersion
import tv.stationbreak.feature.onboarding.R
import tv.stationbreak.feature.onboarding.signin.SignInCopy
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Every error's message and details line, formatted with the arguments the screens pass, leaves no `%` behind. */
class ErrorCopyFormatTest {
    private val templates: Map<String, String> =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
            .getElementsByTagName("string")
            .let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }

    private val names: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    private fun template(id: Int) = templates.getValue(names.getValue(id))

    @Suppress("SpreadOperator") // String.format takes its arguments as a vararg.
    @Test
    fun everyMessageAndDetailsLineIsFullyFormatted() {
        val unformatted = mutableListOf<String>()
        for ((type, copy) in ErrorCopyTable.entries) {
            val messageArgs = ErrorCopyTable.messageFormatArgs(errorOf(type), PRODUCT).toTypedArray()
            val message = String.format(Locale.ROOT, template(copy.message), *messageArgs)
            val detailsArgs = ErrorCopyTable.detailsArgs(PRODUCT).toTypedArray()
            val details = String.format(Locale.ROOT, template(copy.details), *detailsArgs)
            if ('%' in message || '%' in details) unformatted += "${type.simpleName}: $message | $details"
        }
        assertEquals(emptyList<String>(), unformatted)
    }

    /**
     * `FieldError`, the server address field, and sign-in's username field, Quick Connect disabled notice and session
     * ended notice show `stringResource(copy.message)` with no arguments (#853). A placeholder in one of these copies
     * would reach the screen as a literal `%1$s`: route that site through `messageText` before adding one.
     */
    @Test
    fun copiesShownWithoutArgumentsHaveNoPlaceholder() {
        val shownWithoutArguments =
            mapOf(
                "blankAddress" to ErrorCopyTable.blankAddress,
                "blankUsername" to SignInCopy.blankUsername,
                "InvalidAddress" to ErrorCopyTable.copyFor(MediaServerError.InvalidAddress()),
                "QuickConnectDisabled" to ErrorCopyTable.copyFor(MediaServerError.QuickConnectDisabled()),
                // The only errors launch routing hands sign-in as SessionEnded (SessionRepository's fatal ones).
                "SessionExpired" to ErrorCopyTable.copyFor(MediaServerError.SessionExpired()),
                "ServerMismatch" to ErrorCopyTable.copyFor(MediaServerError.ServerMismatch()),
            )
        val withPlaceholder =
            shownWithoutArguments.filterValues { '%' in template(it.message) }.map { (name, copy) ->
                "$name: ${template(copy.message)}"
            }
        assertEquals(emptyList<String>(), withPlaceholder)
    }

    private fun errorOf(type: Class<*>): MediaServerError =
        if (type == MediaServerError.UnsupportedVersion::class.java) {
            MediaServerError.UnsupportedVersion(ServerVersion(10, 11, 8))
        } else {
            type.getDeclaredConstructor().newInstance() as MediaServerError
        }

    private companion object {
        const val PRODUCT = "Jellyfin"
    }
}
