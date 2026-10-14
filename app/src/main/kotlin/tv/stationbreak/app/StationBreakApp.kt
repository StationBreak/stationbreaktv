// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** The Hilt root. The bindings live in `di/`; the server screen's ViewModel consumes `MediaServer` (F0.5). */
@HiltAndroidApp
class StationBreakApp : Application()
