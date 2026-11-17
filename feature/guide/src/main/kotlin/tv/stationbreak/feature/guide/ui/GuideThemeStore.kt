// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.guide.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.StateFlow
import tv.stationbreak.core.ui.ViewerPreference
import tv.stationbreak.core.ui.viewerPreferencesFile

/**
 * The guide's saved appearance. Dark until the viewer picks Light. The system theme is not read.
 */
internal interface GuideThemeStore {
    val theme: StateFlow<GuideTheme>

    fun choose(theme: GuideTheme)
}

/** A preference file of the guide's own, not the server settings. */
internal class DataStoreGuideThemeStore(
    private val preference: ViewerPreference<String, GuideTheme>,
) : GuideThemeStore {
    override val theme: StateFlow<GuideTheme> = preference.value

    override fun choose(theme: GuideTheme) = preference.choose(theme)

    companion object {
        val KEY = stringPreferencesKey("theme")
    }
}

private val Context.guideThemeFile by viewerPreferencesFile("guide-theme")

/** The one store of the process: a store keeps its file's writer for as long as the process runs. */
internal object GuideThemes {
    private var store: GuideThemeStore? = null

    @Synchronized
    fun get(context: Context): GuideThemeStore {
        val existing = store
        if (existing != null) return existing
        val file = context.applicationContext.guideThemeFile
        val preference = ViewerPreference(file, DataStoreGuideThemeStore.KEY, GuideTheme::decode, GuideTheme::encode)
        return DataStoreGuideThemeStore(preference).also { store = it }
    }
}

@Composable
internal fun rememberGuideThemeStore(): GuideThemeStore {
    val context = LocalContext.current
    return remember(context) { GuideThemes.get(context) }
}
