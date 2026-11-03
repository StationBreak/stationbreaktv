// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.channels.builder.ui

import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.feature.channels.builder.BuilderEvent

/** The channel to tune for how the builder ended: the new one for Save and watch, else null. */
internal fun BuilderEvent.tune(): ChannelId? = (this as? BuilderEvent.Saved)?.id?.takeIf { andWatch }
