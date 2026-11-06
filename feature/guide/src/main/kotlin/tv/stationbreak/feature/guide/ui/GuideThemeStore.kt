// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The guide's saved appearance. Dark until the viewer picks Light. The system theme is not read.
 */
internal interface GuideThemeStore {
    val theme: StateFlow<GuideTheme>

    fun choose(theme: GuideTheme)
}

/** A preference file of the guide's own, not the server settings. */
internal class SharedPrefsGuideThemeStore(
    context: Context,
) : GuideThemeStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(GuideTheme.decode(prefs.getString(KEY, null)))

    override val theme: StateFlow<GuideTheme> = state.asStateFlow()

    override fun choose(theme: GuideTheme) {
        prefs.edit().putString(KEY, GuideTheme.encode(theme)).apply()
        state.value = theme
    }

    private companion object {
        const val PREFS = "guide-theme"
        const val KEY = "theme"
    }
}

@Composable
internal fun rememberGuideThemeStore(): GuideThemeStore {
    val context = LocalContext.current
    return remember(context) { SharedPrefsGuideThemeStore(context) }
}
