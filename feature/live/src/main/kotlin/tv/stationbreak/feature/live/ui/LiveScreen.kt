// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.feature.live.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import tv.stationbreak.core.ui.BroadcastColors
import tv.stationbreak.core.ui.ProgramCopy
import tv.stationbreak.core.ui.ProgramInfoOverlay
import tv.stationbreak.core.ui.ProgramText
import tv.stationbreak.core.ui.ScreenLayout
import tv.stationbreak.feature.live.LiveCommand
import tv.stationbreak.feature.live.LiveInfo
import tv.stationbreak.feature.live.LiveState
import tv.stationbreak.feature.live.TransportView
import java.time.ZoneId

/**
 * What the live channel shows for [state] (spec 14). Stateless: the machine in `LiveChannel` decides, this draws.
 *
 * [surface] is the video surface and is always the bottom layer, composed in the same place for every state, so the
 * picture is there the moment the player has one. Everything else is drawn over it and opaque, except the banner while
 * the picture plays, so snow and slates never let a half-ready picture show through.
 *
 * [transport], while the viewer is paused or behind, draws the placeholder transport strip over the picture.
 *
 * [info] is the program info overlay (spec 17), over the picture in place of the banner; the actions banner's buttons
 * send their [LiveCommand] to [onCommand].
 *
 * [guide], while the program guide is open (spec 17), is drawn over the picture, which moves to the guide's corner
 * (`ScreenLayout.pictureRect` of the safe area): the guide is told that rectangle and leaves it clear. The surface
 * stays where it is in the composition and only its modifier changes, so the one surface is never made again and the
 * picture plays on. What stands in for a picture (snow, the cards) is drawn in the corner too, cut to it; the banners
 * and the info overlay make way for the guide, which says the same.
 */
@Suppress("LongParameterList") // The state, its overlays and the transport, and the surface.
@Composable
internal fun LiveScreen(
    state: LiveState,
    info: LiveInfo? = null,
    onCommand: (LiveCommand) -> Unit = {},
    guide: (@Composable (pictureHole: DpRect) -> Unit)? = null,
    transport: TransportView? = null,
    surface: @Composable () -> Unit,
) {
    // The frames are made off the main thread the first time the screen is up, ready before the first tune needs them.
    LaunchedEffect(Unit) { SnowFrames.get() }
    BoxWithConstraints(Modifier.fillMaxSize().background(BroadcastColors.Ink)) {
        val corner = remember(maxWidth, maxHeight) { pictureCorner(DpSize(maxWidth, maxHeight)) }
        Box(if (guide != null) Modifier.inRect(corner) else Modifier.fillMaxSize()) { surface() }
        if (guide != null) {
            Box(Modifier.inRect(corner).clipToBounds()) { PictureLayers(state) }
            guide(corner)
        } else {
            LiveLayers(state, info, onCommand)
            TransportStrip(transport, System::currentTimeMillis)
        }
    }
}

/** Where the picture goes while the guide is open, on a [screen] of that size: the guide's corner. */
private fun pictureCorner(screen: DpSize): DpRect = ScreenLayout.pictureRect(ScreenLayout.safeArea(screen))

/**
 * Exactly [rect] of the parent, which starts at the screen's top-left. Absolute: the corner is the same top-right in a
 * right-to-left layout, as the guide's hole is. The element takes the parent's whole size and places its content at
 * [rect] itself, so the parent's alignment (start, which a right-to-left layout mirrors) cannot move it; an offset
 * alone would be added to that mirrored place. An element equal for an equal [rect], so a recomposition (each key, each
 * state) leaves the node and its layout alone.
 */
private fun Modifier.inRect(rect: DpRect): Modifier = this then InRectElement(rect)

private data class InRectElement(
    val rect: DpRect,
) : ModifierNodeElement<InRectNode>() {
    override fun create() = InRectNode(rect)

    override fun update(node: InRectNode) {
        if (node.rect != rect) {
            node.rect = rect
            node.invalidateMeasurement()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "inRect"
        properties["rect"] = rect
    }
}

private class InRectNode(
    var rect: DpRect,
) : Modifier.Node(),
    LayoutModifierNode {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val content = measurable.measure(Constraints.fixed(rect.width.roundToPx(), rect.height.roundToPx()))
        val x = rect.left.roundToPx()
        val y = rect.top.roundToPx()
        return layout(constraints.maxWidth, constraints.maxHeight) { content.place(x, y) }
    }
}

/**
 * What live TV shows in the guide's corner for [state] when there is no picture to show: snow while tuning, the cards
 * without their banners. Nothing over a picture (the guide says what is on), nor while signed out.
 */
@Composable
private fun PictureLayers(state: LiveState) {
    when (state) {
        is LiveState.Tuning -> Snow()
        is LiveState.Choosing -> if (!state.overPicture) Snow()
        is LiveState.TechnicalDifficulties -> DifficultiesSlate(upNext = null, channel = null)
        is LiveState.Ident -> IdentSlate(upNext = null, channel = null)
        LiveState.NoChannels -> NoChannelsSlate()
        is LiveState.Watching, LiveState.SignedOut -> Unit
    }
}

/** What live TV draws over its picture for [state]: snow, the banners, the slates and the info overlay. */
@Composable
private fun LiveLayers(
    state: LiveState,
    info: LiveInfo?,
    onCommand: (LiveCommand) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        when (state) {
            is LiveState.Tuning -> {
                Snow()
                CableBanner(state.banner)
            }

            is LiveState.Watching -> {
                state.banner?.let {
                    CableBanner(
                        it,
                        onInfo = { onCommand(LiveCommand.ShowInfo) },
                        onLast = { onCommand(LiveCommand.Previous) },
                        onLive = { onCommand(LiveCommand.GoLive) },
                    )
                }
            }

            is LiveState.Choosing -> {
                // The number moving (spec 15): the old picture shows through untouched, or snow when there is none.
                if (!state.overPicture) Snow()
                Box(Modifier.fillMaxSize().testTag(LiveTags.BANNER_CHOOSING)) { ChannelBanner(state.target) }
            }

            is LiveState.TechnicalDifficulties -> {
                DifficultiesSlate(state.upNext, state.channel)
            }

            is LiveState.Ident -> {
                IdentSlate(state.upNext, state.channel)
            }

            LiveState.NoChannels -> {
                NoChannelsSlate()
            }

            LiveState.SignedOut -> {
                // Handing over to sign-in: nothing to show but the ground.
                Box(Modifier.fillMaxSize())
            }
        }
        info?.let { InfoPanel(it) }
    }
}

/** [info] as the shared overlay: the banner's own words, and what the server added once it has. */
@Composable
private fun InfoPanel(info: LiveInfo) {
    val context = LocalContext.current
    val copy = remember(context) { ProgramCopy.from(context.resources) }
    val is24h =
        remember(context) {
            android.text.format.DateFormat
                .is24HourFormat(context)
        }
    val banner = info.banner
    val slot =
        remember(banner, is24h) {
            ProgramText.slot(banner.slotStartMs, banner.slotEndMs, ZoneId.systemDefault(), is24h)
        }
    val episode = remember(banner) { BannerText.episodeLine(banner.info, copy) }
    val details = info.details
    ProgramInfoOverlay(
        channel = channelLine(banner.number, banner.channelName),
        title = banner.title.orEmpty(),
        episodeLine = episode,
        slot = slot,
        overview = details?.overview,
        year = details?.year,
        genres = details?.genres.orEmpty(),
        runtimeMinutes = details?.runtime?.inWholeMinutes?.toInt(),
        loading = info.loading,
        modifier = Modifier.testTag(LiveTags.INFO),
    )
}
