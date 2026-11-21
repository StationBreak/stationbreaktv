// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The mark of a custom channel that is adult content (#984): an unknown top-level field of the rules, [KEY], set to
 * `true`. It is not a rules-format feature, so the format version does not change; the rules carry it as any unknown
 * field is carried. Rules without it (every channel stored before the mark) are not adult. Only the builder sets it,
 * for a copy of an adult built-in channel, and it never clears it.
 *
 * Accepted limitation: an older build, from before the mark, shows it as an "other settings" kept item and could drop
 * it if the channel is edited and saved there after a rollback.
 */
object AdultMark {
    /** The recipe key of After Hours, the one built-in channel whose copies are adult. */
    const val AFTER_HOURS_KEY: String = "after-hours"

    /** The name of the unknown field. */
    const val KEY: String = "stationbreakAdult"

    /** True when [rules] carry the mark. */
    fun isMarked(rules: ChannelRules): Boolean = isMarked(rules.extras)

    /** True when [extras] hold the mark. */
    fun isMarked(extras: JsonObject): Boolean = extras[KEY] == JsonPrimitive(true)

    /** [rules] with the mark set. */
    fun marked(rules: ChannelRules): ChannelRules {
        val extras = rules.extras + (KEY to JsonPrimitive(true))
        return rules.copy(extras = JsonObject(extras))
    }

    /** [rules] with the mark set when [adult], and with no mark otherwise. */
    fun with(
        rules: ChannelRules,
        adult: Boolean,
    ): ChannelRules = if (adult) marked(rules) else rules.copy(extras = JsonObject(rules.extras - KEY))
}
