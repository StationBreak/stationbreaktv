// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * How much of the screen's edge the app keeps text and pictures out of: the viewer's choice, because TVs differ. Safe
 * keeps [OVERSCAN_SAFE] in from every edge, which no TV crops. Edge keeps only [OVERSCAN_EDGE], for a TV that shows the
 * whole picture; surfaces may still run to the screen's edge in either.
 */
enum class ScreenFit(
    /** The fraction of the width (and of the height) kept clear at each edge. */
    val overscan: Float,
) {
    Safe(OVERSCAN_SAFE),
    Edge(OVERSCAN_EDGE),
    ;

    companion object {
        /** Safe unless the stored value is Edge: a missing or unknown value is the default. */
        fun decode(stored: String?): ScreenFit = if (stored == encode(Edge)) Edge else Safe

        fun encode(fit: ScreenFit): String = fit.name
    }
}

private const val OVERSCAN_SAFE = 0.05f
private const val OVERSCAN_EDGE = 0.015f

/** The fit the screens lay out with. The app provides the saved choice; Safe where nothing does (tests, previews). */
val LocalScreenFit = compositionLocalOf { ScreenFit.Safe }

/** The saved [ScreenFit]. */
interface ScreenFitStore {
    val fit: StateFlow<ScreenFit>

    fun choose(fit: ScreenFit)
}

/** A preference file of its own, next to the guide's theme one. The app has one store, shared by live and the guide. */
internal class DataStoreScreenFitStore(
    data: DataStore<Preferences>,
    scope: CoroutineScope,
) : ScreenFitStore {
    private val preference = ViewerPreference(data, KEY, ScreenFit::decode, ScreenFit::encode, scope)

    override val fit: StateFlow<ScreenFit> = preference.value

    override fun choose(fit: ScreenFit) = preference.choose(fit)

    private companion object {
        val KEY = stringPreferencesKey("fit")
    }
}

private val Context.screenFitFile by viewerPreferencesFile("screen-fit")

/** The one store of the process, so a choice made in the guide reaches live TV's picture at once. */
object ScreenFits {
    private var store: ScreenFitStore? = null

    @Synchronized
    fun get(context: Context): ScreenFitStore =
        store ?: DataStoreScreenFitStore(context.applicationContext.screenFitFile, PreferenceWrites).also { store = it }
}

@Composable
fun rememberScreenFitStore(): ScreenFitStore {
    val context = LocalContext.current
    return remember(context) { ScreenFits.get(context) }
}
