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

    /** Sign out and revoke the token. */
    data object SignOut : SetupArgs

    companion object {
        const val MAX_SERVER = 512

        /** Throws [IllegalArgumentException] for an unknown action, or a setup without a plausible server. */
        fun parse(
            action: String?,
            server: String?,
        ): SetupArgs =
            when (action) {
                "setup" -> {
                    val typed = server.orEmpty().trim()
                    require(typed.isNotEmpty()) { "no server" }
                    require(typed.length <= MAX_SERVER) { "server is longer than $MAX_SERVER" }
                    Setup(typed)
                }

                "signout" -> {
                    SignOut
                }

                else -> {
                    throw IllegalArgumentException("unknown action")
                }
            }
    }
}
