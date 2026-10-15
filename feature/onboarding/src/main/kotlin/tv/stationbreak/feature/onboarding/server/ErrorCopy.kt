// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.onboarding.server

import androidx.annotation.StringRes
import tv.stationbreak.core.domain.MediaServerError
import tv.stationbreak.feature.onboarding.R

/** How an error is grouped on the screen. TLS is placeholder copy until the trust screen (Unit 3). */
internal enum class ErrorGroup { Network, NotUsable, Address, Tls, Other }

/** The one next step an error offers (decision 5). Network errors also offer a second, [ErrorAction.EditAddress]. */
internal enum class ErrorAction { Retry, EditAddress }

/**
 * What the screen says about one [MediaServerError]: a plain sentence ([message]), a small details line naming the
 * technical cause ([details]) and the next step. The words live in `strings.xml`.
 */
internal data class ErrorCopy(
    @param:StringRes val message: Int,
    @param:StringRes val details: Int,
    val group: ErrorGroup,
    val primary: ErrorAction,
    val secondary: ErrorAction? = null,
)

/**
 * The error-copy table (decision 5): one entry per [MediaServerError], keyed by class. `ErrorCopyTest` fails when a new
 * error type has no entry, so a new error can't reach the screen unexplained.
 */
internal object ErrorCopyTable {
    private fun network(
        @StringRes message: Int,
        @StringRes details: Int,
    ) = ErrorCopy(message, details, ErrorGroup.Network, ErrorAction.Retry, ErrorAction.EditAddress)

    private fun notUsable(
        @StringRes message: Int,
        @StringRes details: Int,
        primary: ErrorAction = ErrorAction.EditAddress,
    ) = ErrorCopy(message, details, ErrorGroup.NotUsable, primary)

    private fun tls(
        @StringRes message: Int,
        @StringRes details: Int,
    ) = ErrorCopy(message, details, ErrorGroup.Tls, ErrorAction.EditAddress)

    private fun other(
        @StringRes message: Int,
        @StringRes details: Int,
        primary: ErrorAction = ErrorAction.EditAddress,
    ) = ErrorCopy(message, details, ErrorGroup.Other, primary)

    /**
     * The oldest Jellyfin version StationBreak works with (ADR-0006), shown in the "server too old" message. It
     * mirrors the adapter's `MIN_MAJOR` check in `:data:jellyfin`, which a feature module can't see.
     */
    const val MINIMUM_VERSION = "12.0"

    /** A blank field. No request was sent, so this isn't a [MediaServerError]; it has its own words. */
    val blankAddress =
        ErrorCopy(
            R.string.server_blank_message,
            R.string.server_blank_details,
            ErrorGroup.Address,
            ErrorAction.EditAddress,
        )

    val entries: Map<Class<out MediaServerError>, ErrorCopy> =
        mapOf(
            MediaServerError.DnsFailure::class.java to network(R.string.error_dns_message, R.string.error_dns_details),
            MediaServerError.ConnectionRefused::class.java to
                network(R.string.error_refused_message, R.string.error_refused_details),
            MediaServerError.NetworkUnreachable::class.java to
                network(R.string.error_unreachable_message, R.string.error_unreachable_details),
            MediaServerError.Timeout::class.java to
                network(R.string.error_timeout_message, R.string.error_timeout_details),
            MediaServerError.NotJellyfin::class.java to
                notUsable(R.string.error_not_jellyfin_message, R.string.error_not_jellyfin_details),
            MediaServerError.UnsupportedVersion::class.java to
                notUsable(R.string.error_unsupported_version_message, R.string.error_unsupported_version_details),
            MediaServerError.ServerNotSetUp::class.java to
                notUsable(R.string.error_not_set_up_message, R.string.error_not_set_up_details, ErrorAction.Retry),
            MediaServerError.Redirected::class.java to
                notUsable(R.string.error_redirected_message, R.string.error_redirected_details),
            MediaServerError.InvalidAddress::class.java to
                ErrorCopy(
                    R.string.error_invalid_address_message,
                    R.string.error_invalid_address_details,
                    ErrorGroup.Address,
                    ErrorAction.EditAddress,
                ),
            MediaServerError.TlsUntrusted::class.java to
                tls(R.string.error_tls_untrusted_message, R.string.error_tls_untrusted_details),
            MediaServerError.TlsHostnameMismatch::class.java to
                tls(R.string.error_tls_hostname_message, R.string.error_tls_hostname_details),
            MediaServerError.TlsCertificateExpired::class.java to
                tls(R.string.error_tls_expired_message, R.string.error_tls_expired_details),
            MediaServerError.TlsHandshakeFailed::class.java to
                tls(R.string.error_tls_handshake_message, R.string.error_tls_handshake_details),
            MediaServerError.NotTls::class.java to tls(R.string.error_not_tls_message, R.string.error_not_tls_details),
            MediaServerError.ServerError::class.java to
                other(R.string.error_server_error_message, R.string.error_server_error_details, ErrorAction.Retry),
            MediaServerError.NotFound::class.java to
                other(R.string.error_not_found_message, R.string.error_not_found_details),
            MediaServerError.Unknown::class.java to
                other(R.string.error_unknown_message, R.string.error_unknown_details, ErrorAction.Retry),
            // Sign-in and restore errors: not expected from a Test, but every error has copy.
            MediaServerError.HttpUnauthorized::class.java to
                other(R.string.error_unauthorized_message, R.string.error_unauthorized_details),
            MediaServerError.HttpForbidden::class.java to
                other(R.string.error_forbidden_message, R.string.error_forbidden_details),
            MediaServerError.CleartextNotConfirmed::class.java to
                other(R.string.error_cleartext_message, R.string.error_cleartext_details),
            MediaServerError.SessionExpired::class.java to
                other(R.string.error_session_expired_message, R.string.error_session_expired_details),
            MediaServerError.ServerMismatch::class.java to
                other(R.string.error_server_mismatch_message, R.string.error_server_mismatch_details),
            MediaServerError.QuickConnectDisabled::class.java to
                other(R.string.error_quick_connect_message, R.string.error_quick_connect_details),
        )

    fun copyFor(error: MediaServerError): ErrorCopy =
        entries[error::class.java] ?: error("No error copy for ${error::class.java.simpleName}")

    /** The arguments the error's message needs: only [MediaServerError.UnsupportedVersion] has any (found, minimum). */
    fun messageArgs(error: MediaServerError): List<Any> =
        when (error) {
            is MediaServerError.UnsupportedVersion -> listOf(error.found.toString(), MINIMUM_VERSION)
            else -> emptyList()
        }

    private val classNameShaped = Regex("""[A-Za-z_][A-Za-z0-9_$.]*""")

    /**
     * The cause class names for the details line, outermost first, as simple names. The port promises class names
     * only; anything that isn't shaped like one (a URL, a header) is dropped here as well.
     */
    fun causeText(error: MediaServerError): String =
        error.causes
            .filter { classNameShaped.matches(it) }
            .joinToString(", ") { it.substringAfterLast('.') }
}
