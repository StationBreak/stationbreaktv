// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.model

/**
 * Marks the few places that may read a secret's raw value ([AccessToken.reveal], [QuickConnectSecret.reveal]). Using
 * one needs an opt-in, and a source scan (`SecretAccessScanTest`) allows the opt-in in the header builder, the
 * Quick Connect request and the session serialiser only (Angua (Security) R4).
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message =
        "Reads a secret's raw value. " +
            "Only the header builder, the session serialiser and the Quick Connect request may.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION)
annotation class SecretAccess

/** A user-scoped Jellyfin access token. [toString] never shows it; [reveal] is the one way to read it. */
@OptIn(SecretAccess::class)
class AccessToken(
    @property:SecretAccess val reveal: String,
) {
    init {
        require(reveal.isNotBlank()) { "A token can't be blank" }
    }

    override fun equals(other: Any?): Boolean = other is AccessToken && other.reveal == reveal

    override fun hashCode(): Int = reveal.hashCode()

    override fun toString(): String = "AccessToken(***)"
}

/** The secret of a Quick Connect request. It is a credential: it never reaches a UI state, a log or an error. */
@OptIn(SecretAccess::class)
class QuickConnectSecret(
    @property:SecretAccess val reveal: String,
) {
    init {
        require(reveal.isNotBlank()) { "A secret can't be blank" }
    }

    override fun equals(other: Any?): Boolean = other is QuickConnectSecret && other.reveal == reveal

    override fun hashCode(): Int = reveal.hashCode()

    override fun toString(): String = "QuickConnectSecret(***)"
}
