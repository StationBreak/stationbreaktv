// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.bench

/**
 * What [BenchSetupActivity] was asked to do, from the launching intent's extras. [parse] takes the raw strings so it
 * needs no Android type. Whether the server address is a usable one is not decided here: the domain's `discover`
 * does that, the same as on the onboarding server screen.
 */
sealed interface SetupArgs {
    /** Sign in to [server] with Quick Connect and accept the default libraries. */
    data class Setup(
        val server: String,
    ) : SetupArgs {
        // The address names the user's server: it is never printed.
        override fun toString(): String = "Setup(***)"
    }

    /** Sign out and revoke the token. With [force], also clear the session when the server cannot be asked. */
    data class SignOut(
        val force: Boolean = false,
    ) : SetupArgs

    companion object {
        const val MAX_SERVER = 512
        private val SCHEMES = listOf("https://", "http://")

        /**
         * Throws [IllegalArgumentException] for an unknown action, or a setup whose server does not start with
         * `https://` or `http://`: the scheme is the operator's explicit choice, never guessed.
         */
        fun parse(
            action: String?,
            server: String?,
            force: Boolean = false,
        ): SetupArgs =
            when (action) {
                "setup" -> {
                    val typed = server.orEmpty().trim()
                    require(typed.isNotEmpty()) { "no server" }
                    require(typed.length <= MAX_SERVER) { "server is longer than $MAX_SERVER" }
                    require(SCHEMES.any { typed.startsWith(it, ignoreCase = true) }) { "no scheme" }
                    Setup(typed)
                }

                "signout" -> {
                    SignOut(force)
                }

                else -> {
                    throw IllegalArgumentException("unknown action")
                }
            }
    }
}
