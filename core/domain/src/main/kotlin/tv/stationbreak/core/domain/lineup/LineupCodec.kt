// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.domain.lineup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import tv.stationbreak.core.domain.lineup.repair.RepairsCodec
import tv.stationbreak.core.domain.lineup.repair.TodayRepairs
import tv.stationbreak.core.engine.lineup.ChannelSource
import tv.stationbreak.core.engine.lineup.ChannelState
import tv.stationbreak.core.engine.lineup.CurationBasis
import tv.stationbreak.core.engine.lineup.Lineup
import tv.stationbreak.core.engine.lineup.LineupChannel
import tv.stationbreak.core.engine.lineup.ShelfReason
import tv.stationbreak.core.engine.lineup.ViewerChoice
import tv.stationbreak.core.engine.lineup.canHoldAddedMark
import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.ChannelId
import tv.stationbreak.core.rules.BroadcastDay
import tv.stationbreak.core.rules.ChannelRules
import tv.stationbreak.core.rules.RulesReader
import tv.stationbreak.core.rules.RulesResult
import tv.stationbreak.core.rules.RulesWriter
import tv.stationbreak.core.rules.StudioFold2Gate
import tv.stationbreak.core.rules.upgradedToStudioFold2

/**
 * The stored form of a [Lineup] (spec 12): compact JSON with `"version"` 3, 4, 5 or 6 ([VERSION] is the newest). Only
 * `core:domain` reads or writes it. A version-1 or 2 file is read as it is (version 1 has no meta `extrasFromMs`,
 * which reads as null; before version 3 there are no viewer choices, so `viewerChoicesKnown` is false and each
 * channel's `chosenByViewer` is inferred from its state and overrides); it is written back as version 3, and the
 * service keeps a copy of the old bytes first (`LineupStore.keepCopy`). Version 3 added `useMyCollections`,
 * `viewerChoicesKnown`, `curationBasis`, the channel fields `chosenByViewer`, `leavesAtMs` and `shelfReason`, and the
 * starter source fields `previousRules` and `rulesFromMs`, and the meta field `replanFromMs`. A field of version 3 in
 * an older file makes it [Decoded.Unreadable].
 *
 * Version 4 (spec 26) adds the optional top-level `repairs` object, the per-TV repair record ([TodayRepairs], read and
 * written by [RepairsCodec]). A file with no repairs is written as version 3, the lowest version that holds its
 * content, so a version-4 file exists only while a record is held (and no channel holds a version-5 mark). A
 * `repairs` key in a file below version 4 makes it
 * [Decoded.Unreadable], and an older app sees version 4 as [Decoded.NewerVersion]. A `repairs` object that does not
 * read loads the lineup with no record ([Decoded.Loaded.repairsUnreadable]): the record is per TV and rebuilt by the
 * next repair, so it is never a reason to set the lineup aside. The service keeps a copy of a
 * version-3 file (`keepCopy`, tag `v3`) before the first version-4 save.
 *
 * Version 5 (the viewer's added channels, ADR-0023) adds the per-channel `addedByViewer: true`, written only for a
 * channel marked [ViewerChoice.Added] and always beside `chosenByViewer: true`. The version of a file is the highest
 * any feature in it needs ([versionFor]), not a rule of its own: a lineup with no added channel and no repairs is
 * version 3, one with repairs 4, one with an added channel 5 (a file that has both holds both). An older build reads
 * a version-5 file as [Decoded.NewerVersion]: unusable, never wrongly read, and never written over. The key is
 * checked before anything is read from a channel: below version 5, or present and not `true` (`false`, `null`, any
 * other type), the file is [Decoded.Unreadable]. Present on a channel that cannot hold it ([storedCanHoldAddedMark]:
 * not an unedited starter, whether this app can read its rules or the JSON says so) it is dropped quietly and the
 * channel reads as a chosen one; on one that can hold it without `chosenByViewer: true` the file is unreadable. The
 * encoder writes the key only where the mark can be held, so a lineup is version 5 only then. A version-5 file whose
 * marks are all gone loads and the next save writes version 3 or 4. Before the first save of a higher version the
 * service keeps a copy of the file it replaces (`LineupStore.keepCopy`, tag `v{from}-to{to}`).
 *
 * Version 6 (the `noNumber` shelf reason, #798) adds the wire name `"noNumber"` for a starter that fits but has no free
 * channel number. It is written only while some channel holds it (the same [AddedMark.versionFor] max: the highest any
 * feature needs), so a lineup without one is still 3, 4 or 5. An older build reads a version-6 file as
 * [Decoded.NewerVersion]: unusable, never wrongly read, never written over; `"noNumber"` in a file below version 6 is
 * [Decoded.Unreadable]. A file that only says `"belowCap"` reads as before. The service keeps the usual copy
 * (`keepCopy`, tag `v{from}-to6`) before the first version-6 save.
 *
 * **Any** change to the schema, adding a field or an enum value included, bumps [VERSION]. So an older app sees
 * [Decoded.NewerVersion] for a newer file and never saves over fields it doesn't know, and a file with an unknown key
 * or an unknown `state` or `kind` value is [Decoded.Unreadable] (set aside).
 *
 * One explicit exception (owner decision 2026-11-13, ADR-0025): the contents of `repairs` are a per-TV, disposable
 * record, so the station-break block index (-2, [RepairsCodec]) was added inside version 4 without a bump. Only a
 * channel at rules `formatVersion` 5 lays out breaks, and an older build already shows that channel as unusable. An
 * older build that meets a block index it does not know drops the **entire** repair record (every channel's selection
 * repairs and lost fixes on that TV, until the next repair) and loads the lineup; as for any unreadable record it
 * reports `LineupEvent.RepairsUnreadable`, keeps the one-time `repairs-unreadable` copy (`UNREADABLE_RECORD_COPY`) and
 * writes the file at once without the record.
 *
 * What is rejected and what is dropped: an unknown key or value, and a field of a later version in an older file,
 * make the file [Decoded.Unreadable]. A few values this app could have written itself are read leniently and dropped
 * instead, so a save writes them back without them: `leavesAtMs` and `shelfReason` on a channel that is not a starter
 * (version 3; only a starter has them), a `previousRules` without its `rulesFromMs` or the other way round, or
 * `previousRules` that do not read (both dropped: the starter keeps its current rules), and a version 3 field written
 * as JSON `null` in a version 1 or 2 file (read as absent).
 *
 * The rules of a starter or custom channel are a rules-format file (version 1 to 5) held as a JSON string; they are
 * read and written by the rules codec, so they keep their extras (the fields this app does not know) but not their
 * bytes.
 *
 * One bad channel never discards the rest: when a channel's `source` cannot be used (its rules fail [RulesReader] or
 * need a newer app, a leftovers ceiling is not on the rating ladder, a field is missing or of the wrong type), that
 * channel decodes as [ChannelSource.Unusable] holding the source's JSON, and keeps its ID, numbers, name, state and
 * time. Encoding writes that JSON back unchanged, so `encode(decode(x))` keeps it byte for byte. What the channel's
 * own fields or the lineup's fields get wrong (a missing field, a wrong type, an ID that is not canonical, two channels
 * at one number) makes the whole file unreadable, because nothing safe could be kept of it.
 *
 * A channel `source` nested deeper than 32 levels is [ChannelSource.Unusable] without being re-encoded (its JSON is not
 * kept: a source that deep is not one this app or an older one wrote).
 *
 * Beside the lineup the file may hold a `meta` object, the refresher's bookkeeping about the lineup ([RefreshMeta]).
 * Version 1 gained it before any release shipped, so it is an optional key and not a new version: a file without it
 * loads with an empty [RefreshMeta], and an unknown key inside it is [Decoded.Unreadable] like any other. Version 2
 * added the meta field `extrasFromMs`. The meta field `replanFromMs` came with version 3, before it shipped.
 *
 * Decoding never throws.
 */
internal object LineupCodec {
    /** The version written when nothing needs more: the lowest that holds the content. */
    const val BASE_VERSION = 3

    /** The version that holds the repair record (the top-level `repairs` object). */
    const val REPAIRS_VERSION = 4

    /** The version that holds the per-channel `addedByViewer` mark. */
    const val SWITCH_ONS_VERSION = 5

    /** The version that holds the `noNumber` shelf reason. */
    const val NO_NUMBER_VERSION = 6

    /** The newest schema version this app reads and writes (6: the `noNumber` shelf reason). */
    const val VERSION = NO_NUMBER_VERSION

    /** What [decode] found. */
    sealed interface Decoded {
        /**
         * A lineup of [fileVersion] (1 up to [VERSION]), perhaps with [ChannelSource.Unusable] channels, its [meta]
         * and its [repairs] (none below version 4). A [fileVersion] below what the next save writes is an upgrade: the
         * caller keeps the old bytes before it saves. The default [fileVersion] is [BASE_VERSION]: what a lineup with
         * no repairs is written as. [repairsUnreadable] is true when the file's `repairs` object did not read: the
         * lineup is loaded with no record ([repairs] is none), the file is not set aside, and the caller reports it.
         * [upgradedRules] is how many rules files (a channel's rules, a starter's previous rules) the read upgraded to
         * format 4 (ADR-0026, the upgrade): more than none means the file on disk still says 3 for them.
         */
        data class Loaded(
            val lineup: Lineup,
            val meta: RefreshMeta = RefreshMeta(),
            val repairs: TodayRepairs = TodayRepairs.NONE,
            val fileVersion: Int = BASE_VERSION,
            val repairsUnreadable: Boolean = false,
            val upgradedRules: Int = 0,
        ) : Decoded

        /** Not a lineup this version can read. The caller sets it aside before writing another. */
        data object Unreadable : Decoded

        /** A lineup from a newer app. It must never be written over. */
        data class NewerVersion(
            val version: Int,
        ) : Decoded
    }

    private val json = Json

    /**
     * The bytes to store for [lineup], its [meta] and its [repairs]: the lowest version that holds all of it
     * ([BASE_VERSION], [REPAIRS_VERSION] when there are repairs, [SWITCH_ONS_VERSION] when a channel holds the added
     * mark, [NO_NUMBER_VERSION] when one holds the `noNumber` reason). A mark on a channel that cannot hold it
     * ([canHoldAddedMark]) is written as an ordinary choice.
     */
    fun encode(
        lineup: Lineup,
        meta: RefreshMeta = RefreshMeta(),
        repairs: TodayRepairs = TodayRepairs.NONE,
    ): ByteArray {
        val ids = lineup.channels.mapTo(HashSet()) { it.id }
        val file =
            FileV1(
                version = AddedMark.versionFor(lineup, repairs),
                serverId = lineup.serverId.value,
                userId = lineup.userId.value,
                catalogVersion = lineup.catalogVersion,
                utcOffsetMinutes = lineup.utcOffsetMinutes,
                finalised = lineup.finalised,
                channels = lineup.channels.map(::channelOf),
                meta = MetaV1.of(meta),
                useMyCollections = if (lineup.useMyCollections) null else false,
                viewerChoicesKnown = if (lineup.viewerChoicesKnown) null else false,
                curationBasis = lineup.curationBasis?.let { BasisV1.of(it, ids) },
                repairs = if (repairs.isEmpty) null else RepairsCodec.encode(repairs),
            )
        return json.encodeToString(FileV1.serializer(), file).encodeToByteArray()
    }

    /**
     * What [bytes] hold. Never throws: whatever else goes wrong, even a stack overflow, is [Decoded.Unreadable].
     *
     * Once the format 4 gate ([gatePassed], `StudioFold2Gate`) has passed, a channel's rules, and a starter's previous
     * rules, that say `formatVersion` 3 and hold a `studio` source, a `studio` exclusion or a `studios` filter come out
     * saying 4 with nothing else changed (ADR-0026, the upgrade), so the next save writes them as 4. Before it, the
     * rules come out as they were written.
     */
    @Suppress("TooGenericExceptionCaught")
    fun decode(
        bytes: ByteArray,
        gatePassed: Boolean = StudioFold2Gate.WRITES_FORMAT_4,
    ): Decoded =
        try {
            val root = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
            when (val version = versionOf(root)) {
                null -> {
                    Decoded.Unreadable
                }

                in 1..VERSION -> {
                    val hasRepairs = (root as JsonObject).containsKey("repairs")
                    val file = json.decodeFromJsonElement(FileV1.serializer(), root)
                    lineupOf(file, version, hasRepairs, gatePassed, AddedMark.raw(root))
                }

                else -> {
                    Decoded.NewerVersion(version)
                }
            }
        } catch (_: IllegalArgumentException) {
            // SerializationException is one: bad JSON, an unknown key or enum value, a missing field, a wrong type.
            Decoded.Unreadable
        } catch (_: CharacterCodingException) {
            Decoded.Unreadable
        } catch (_: StackOverflowError) {
            Decoded.Unreadable
        } catch (_: Exception) {
            Decoded.Unreadable
        }

    /** [source] as the file holds it (the `source` of a channel): what the days' key ([LineupDays.inputsOf]) hashes. */
    fun sourceOf(source: ChannelSource): JsonElement = SourceCodec.encode(source)

    // The version as a whole number of 1 or more (a too-big one is newer than any app), or null.
    private fun versionOf(root: JsonElement): Int? {
        val primitive = (root as? JsonObject)?.get("version") as? JsonPrimitive
        val digits = primitive?.takeIf { !it.isString && WHOLE.matches(it.content) }?.content ?: return null
        val value = digits.toIntOrNull() ?: if (digits.startsWith("-")) 0 else Int.MAX_VALUE
        return value.takeIf { it >= 1 }
    }

    private fun lineupOf(
        file: FileV1,
        fileVersion: Int,
        hasRepairs: Boolean,
        gatePassed: Boolean,
        rawAdded: List<JsonElement?>,
    ): Decoded {
        val server = CanonicalId.parseCanonicalOrNull(file.serverId)
        val user = CanonicalId.parseCanonicalOrNull(file.userId)
        if (server == null || user == null || !validOffset(file.utcOffsetMinutes)) return Decoded.Unreadable
        val inferred = fileVersion < 3
        val read = file.channels.mapIndexed { i, c -> channelOf(c, inferred, rawAdded.getOrNull(i) != null) }
        val channels = read.map { upgraded(it, gatePassed) }
        val upgradedRules = read.indices.sumOf { upgradesIn(read[it], channels[it]) }
        val ids = channels.mapTo(HashSet()) { it.id }
        val lineup =
            Lineup.ofOrNull(
                server,
                user,
                file.catalogVersion,
                file.utcOffsetMinutes,
                file.finalised,
                channels,
                useMyCollections = file.useMyCollections ?: true,
                curationBasis = file.curationBasis?.basis(ids),
                viewerChoicesKnown = !inferred && file.viewerChoicesKnown != false,
            )
        val meta = file.meta?.refreshMeta ?: RefreshMeta()
        return if (lineup != null && !hasStrayField(file, meta, fileVersion, hasRepairs, rawAdded)) {
            val repairs = if (hasRepairs) repairsOf(file.repairs ?: JsonNull) else TodayRepairs.NONE
            val unreadable = repairs == null
            Decoded.Loaded(
                lineup,
                meta,
                repairs ?: TodayRepairs.NONE,
                fileVersion,
                repairsUnreadable = unreadable,
                upgradedRules = upgradedRules,
            )
        } else {
            Decoded.Unreadable
        }
    }

    // The record, or null when it does not read (whatever its decoding throws): the lineup is loaded all the same,
    // with none (spec 26).
    @Suppress("TooGenericExceptionCaught") // Only the record's own decoding is guarded; the lineup's is strict.
    private fun repairsOf(element: JsonElement): TodayRepairs? =
        try {
            RepairsCodec.decode(element)
        } catch (_: RuntimeException) {
            null
        }

    // Older versions are strict: a field of a later version in them was not written by this app.
    private fun hasStrayField(
        file: FileV1,
        meta: RefreshMeta,
        fileVersion: Int,
        hasRepairs: Boolean,
        rawAdded: List<JsonElement?>,
    ): Boolean =
        AddedMark.stray(rawAdded, fileVersion) ||
            (fileVersion < NO_NUMBER_VERSION && file.channels.any { it.shelfReason == NO_NUMBER_JSON }) ||
            (fileVersion < 2 && meta.extrasFromMs != null) ||
            (fileVersion < BASE_VERSION && (hasVersion3Field(file) || meta.replanFromMs != null)) ||
            (fileVersion < REPAIRS_VERSION && hasRepairs)

    private fun hasVersion3Field(file: FileV1): Boolean =
        file.useMyCollections != null ||
            file.viewerChoicesKnown != null ||
            file.curationBasis != null ||
            file.channels.any {
                it.chosenByViewer != null ||
                    it.leavesAtMs != null ||
                    (it.shelfReason != null && it.shelfReason !is JsonNull) ||
                    (it.source as? JsonObject)?.let { source ->
                        SourceCodec.PREVIOUS_RULES in source || SourceCodec.RULES_FROM_MS in source
                    } == true
            }

    private fun validOffset(minutes: Int): Boolean =
        try {
            BroadcastDay(minutes, null)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun channelOf(channel: LineupChannel) =
        ChannelV1(
            id = channel.id.value,
            defaultNumber = channel.defaultNumber,
            numberOverride = channel.numberOverride,
            nameOverride = channel.nameOverride,
            state = StateV1.of(channel.state),
            onSinceMs = channel.onSinceMs,
            source = SourceCodec.encode(channel.source),
            chosenByViewer = if (channel.chosenByViewer) true else null,
            addedByViewer = if (AddedMark.holds(channel)) true else null,
            leavesAtMs = channel.leavesAtMs.takeIf { channel.source is ChannelSource.Starter },
            shelfReason =
                channel.shelfReason
                    .takeIf { channel.source is ChannelSource.Starter }
                    ?.let { JsonPrimitive(REASON_NAMES.getValue(it)) },
        )

    // Throws IllegalArgumentException for a blank ID; decode turns that into Unreadable. [added] is whether the file
    // wrote the `addedByViewer` key (already checked to be `true`): it is kept on a channel that can hold it and
    // needs the `chosenByViewer` that goes with it, and dropped quietly on one that cannot.
    private fun channelOf(
        channel: ChannelV1,
        inferChoice: Boolean,
        added: Boolean,
    ): LineupChannel {
        val state = channel.state.state
        val source = SourceCodec.decode(channel.source)
        val starter = source is ChannelSource.Starter
        val touched =
            if (inferChoice) {
                state == ChannelState.UserHidden || channel.numberOverride != null || channel.nameOverride != null
            } else {
                channel.chosenByViewer == true
            }
        val holds = added && storedCanHoldAddedMark(source)
        require(!holds || touched) { "An added channel is a chosen one" }
        return LineupChannel(
            id = ChannelId(channel.id),
            defaultNumber = channel.defaultNumber,
            numberOverride = channel.numberOverride,
            nameOverride = channel.nameOverride,
            state = state,
            source = source,
            onSinceMs = channel.onSinceMs,
            viewerChoice =
                when {
                    holds -> ViewerChoice.Added
                    touched -> ViewerChoice.Touched
                    else -> ViewerChoice.None
                },
            // Only a starter leaves the lineup or sits on the shelf for a reason.
            leavesAtMs = channel.leavesAtMs.takeIf { starter },
            shelfReason = reasonOf(channel.shelfReason, starter),
        )
    }

    private val WHOLE = Regex("^-?(0|[1-9][0-9]*)$")
}

// ADR-0026, the upgrade: once the gate has passed, rules that say 3 and hold a studio feature say 4 (a starter's
// previous rules too).
private fun upgraded(
    channel: LineupChannel,
    gatePassed: Boolean,
): LineupChannel {
    if (!gatePassed) return channel
    val upgrade = { rules: ChannelRules -> rules.upgradedToStudioFold2(gatePassed = true) }
    return when (val source = channel.source) {
        is ChannelSource.Starter -> channel.copy(source = source.mapRules(upgrade))
        is ChannelSource.Custom -> channel.copy(source = ChannelSource.Custom(upgrade(source.rules)))
        is ChannelSource.Leftovers, is ChannelSource.Unusable -> channel
    }
}

// How many of [before]'s rules files [after] holds upgraded: the upgrade gives back the very rules it leaves alone.
private fun upgradesIn(
    before: LineupChannel,
    after: LineupChannel,
): Int {
    val was = before.source
    val now = after.source
    return when {
        was is ChannelSource.Starter && now is ChannelSource.Starter -> {
            (if (was.rules !== now.rules) 1 else 0) + (if (was.previousRules !== now.previousRules) 1 else 0)
        }

        was is ChannelSource.Custom && now is ChannelSource.Custom -> {
            if (was.rules !== now.rules) 1 else 0
        }

        else -> {
            0
        }
    }
}

/** The "kind" a stored channel's raw JSON names ([CUSTOM_KIND] for the viewer's own), or null when it names none. */
@Suppress("SwallowedException") // Raw JSON that does not parse is not a custom channel's.
internal fun kindOf(rawJson: String): String? =
    try {
        val kind = (Json.parseToJsonElement(rawJson) as? JsonObject)?.get("kind") as? JsonPrimitive
        kind?.takeIf { it.isString }?.content
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * The `addedByViewer` key of version 5 (the viewer's added channels): which version a lineup needs for it, the checks
 * of the raw key and what a channel can hold. The raw checks run on the field as the file wrote it, before the holder
 * rule: below version 5, or present and not `true` (`false`, `null`, any other type), the file is unreadable.
 */
private object AddedMark {
    private const val KEY = "addedByViewer"

    // The version a file for [lineup] and [repairs] is written as: the highest any feature in it needs.
    fun versionFor(
        lineup: Lineup,
        repairs: TodayRepairs,
    ): Int =
        maxOf(
            LineupCodec.BASE_VERSION,
            if (repairs.isEmpty) 0 else LineupCodec.REPAIRS_VERSION,
            if (lineup.channels.any { holds(it) }) LineupCodec.SWITCH_ONS_VERSION else 0,
            if (lineup.channels.any { storesNoNumber(it) }) LineupCodec.NO_NUMBER_VERSION else 0,
        )

    // Whether the channel's stored reason is NoNumber: [channelOf] writes a reason on a starter only.
    private fun storesNoNumber(channel: LineupChannel) =
        channel.shelfReason == ShelfReason.NoNumber && channel.source is ChannelSource.Starter

    // Each channel's key as the file wrote it, in file order: null when it is absent. Read from the raw JSON, where a
    // key written as `null` stays a value (it is not the same as an absent key).
    fun raw(root: JsonElement): List<JsonElement?> =
        ((root as JsonObject)["channels"] as? JsonArray).orEmpty().map { (it as? JsonObject)?.get(KEY) }

    // True when the key is where it cannot be (a file below version 5) or is not `true`.
    fun stray(
        raw: List<JsonElement?>,
        fileVersion: Int,
    ): Boolean = raw.any { it != null && (fileVersion < LineupCodec.SWITCH_ONS_VERSION || !isTrue(it)) }

    // Whether the channel's mark is written: Added on a source that can hold it.
    fun holds(channel: LineupChannel): Boolean {
        val added = channel.viewerChoice == ViewerChoice.Added
        return added && storedCanHoldAddedMark(channel.source)
    }

    private fun isTrue(element: JsonElement): Boolean {
        val primitive = element as? JsonPrimitive
        return primitive != null && !primitive.isString && primitive.booleanOrNull == true
    }
}

/**
 * Whether a stored channel can carry the added mark: an unedited starter ([canHoldAddedMark], the engine's half), or
 * one this app cannot read whose JSON says it is a starter that is not edited (it keeps its mark, so a newer app's
 * rules do not cost it). The raw JSON is what the encoder keeps; a source nested deeper than 32 levels decodes as
 * [ChannelSource.Unusable] with none, so it cannot hold the mark and reads as a touched one.
 */
internal fun storedCanHoldAddedMark(source: ChannelSource): Boolean =
    source.canHoldAddedMark() ||
        (source is ChannelSource.Unusable && kindOf(source.rawJson) == "starter" && editedOf(source.rawJson) != true)

// `edited` as the starter source reads it (`SourceCodec.starter`): a JSON boolean, else null.
@Suppress("SwallowedException") // Raw JSON that does not parse says nothing.
private fun editedOf(rawJson: String): Boolean? =
    try {
        val fields = Json.parseToJsonElement(rawJson) as? JsonObject
        (fields?.get("edited") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    } catch (_: IllegalArgumentException) {
        null
    }

/** The `kind` the rules format gives a channel the viewer built. */
internal const val CUSTOM_KIND = "custom"

/**
 * A channel's `source`. Unknown keys and an unknown `kind` throw [IllegalArgumentException] (the file is
 * unreadable); anything else wrong gives [ChannelSource.Unusable].
 *
 * Its output is also hashed into the days' key ([LineupCodec.sourceOf], `LineupDays.inputsOf`): a change to how a
 * source is written changes every key, so the built days are built again once (and the day files are a miss).
 */
private object SourceCodec {
    private const val KIND = "kind"
    private const val KEY = "recipeKey"
    private const val EDITED = "edited"
    private const val RULES = "rules"
    private const val MAX_RATING = "maxRating"
    private const val MAX_DEPTH = 32
    const val PREVIOUS_RULES = "previousRules"
    const val RULES_FROM_MS = "rulesFromMs"

    private val keys =
        mapOf(
            "starter" to setOf(KIND, KEY, EDITED, RULES, PREVIOUS_RULES, RULES_FROM_MS),
            "leftovers" to setOf(KIND, KEY, MAX_RATING),
            CUSTOM_KIND to setOf(KIND, RULES),
        )

    fun encode(source: ChannelSource): JsonElement =
        when (source) {
            is ChannelSource.Starter -> {
                val fields =
                    linkedMapOf<String, JsonElement>(
                        KIND to JsonPrimitive("starter"),
                        KEY to JsonPrimitive(source.recipeKey),
                        EDITED to JsonPrimitive(source.edited),
                        RULES to JsonPrimitive(RulesWriter.write(source.rules)),
                    )
                val previous = source.previousRules
                val from = source.rulesFromMs
                if (previous != null && from != null) {
                    fields[PREVIOUS_RULES] = JsonPrimitive(RulesWriter.write(previous))
                    fields[RULES_FROM_MS] = JsonPrimitive(from)
                }
                JsonObject(fields)
            }

            is ChannelSource.Leftovers -> {
                JsonObject(
                    linkedMapOf(
                        KIND to JsonPrimitive("leftovers"),
                        KEY to JsonPrimitive(source.recipeKey),
                        MAX_RATING to JsonPrimitive(source.maxRating),
                    ),
                )
            }

            is ChannelSource.Custom -> {
                val rules = JsonPrimitive(RulesWriter.write(source.rules))
                JsonObject(linkedMapOf(KIND to JsonPrimitive(CUSTOM_KIND), RULES to rules))
            }

            // Only decode makes one, from JSON it parsed, so this gives back the very element it was made from.
            is ChannelSource.Unusable -> {
                try {
                    Json.parseToJsonElement(source.rawJson)
                } catch (_: IllegalArgumentException) {
                    JsonPrimitive(source.rawJson)
                }
            }
        }

    fun decode(element: JsonElement): ChannelSource {
        val fields = element as? JsonObject
        val kind = fields?.string(KIND)
        if (fields != null && kind != null) {
            val allowed = requireNotNull(keys[kind]) { "An unknown channel kind" }
            require(allowed.containsAll(fields.keys)) { "An unknown key in a channel source" }
        }
        if (nestsDeeperThan(element, MAX_DEPTH)) return ChannelSource.Unusable("null")
        val source = if (fields != null && kind != null) known(kind, fields) else null
        return source ?: ChannelSource.Unusable(Json.encodeToString(JsonElement.serializer(), element))
    }

    // True when [element] has more than [levels] levels of objects and arrays. Stops as soon as it is known.
    private fun nestsDeeperThan(
        element: JsonElement,
        levels: Int,
    ): Boolean =
        when (element) {
            is JsonObject -> levels <= 0 || element.values.any { nestsDeeperThan(it, levels - 1) }
            is JsonArray -> levels <= 0 || element.any { nestsDeeperThan(it, levels - 1) }
            else -> false
        }

    private fun known(
        kind: String,
        fields: JsonObject,
    ): ChannelSource? =
        try {
            when (kind) {
                "starter" -> starter(fields)
                "leftovers" -> leftovers(fields)
                else -> custom(fields)
            }
        } catch (_: IllegalArgumentException) {
            // A leftovers ceiling that is not on the ladder.
            null
        }

    private fun starter(fields: JsonObject): ChannelSource? {
        val key = fields.string(KEY)
        val edited = (fields[EDITED] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val rules = rulesOf(fields)
        if (key == null || edited == null || rules == null) return null
        // The old rules and their boundary go together; old rules this app cannot read drop both (E5).
        val previous = fields.string(PREVIOUS_RULES)?.let(RulesReader::read) as? RulesResult.Ok
        val from = (fields[RULES_FROM_MS] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        return if (previous != null && from != null) {
            ChannelSource.Starter(key, edited, rules, previous.rules, from)
        } else {
            ChannelSource.Starter(key, edited, rules)
        }
    }

    private fun leftovers(fields: JsonObject): ChannelSource? {
        val key = fields.string(KEY) ?: return null
        val ceiling = fields[MAX_RATING]
        return when {
            ceiling == JsonNull -> ChannelSource.Leftovers(key, null)
            ceiling is JsonPrimitive && ceiling.isString -> ChannelSource.Leftovers(key, ceiling.content)
            else -> null
        }
    }

    private fun custom(fields: JsonObject): ChannelSource? = rulesOf(fields)?.let { ChannelSource.Custom(it) }

    // Only rules this app can schedule (versions 1 to 5); any other result keeps the channel unusable.
    private fun rulesOf(fields: JsonObject) = (fields.string(RULES)?.let(RulesReader::read) as? RulesResult.Ok)?.rules

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

@Suppress("LongParameterList")
@Serializable
private class FileV1(
    val version: Int,
    val serverId: String,
    val userId: String,
    val catalogVersion: Int,
    val utcOffsetMinutes: Int,
    val finalised: Boolean,
    val channels: List<ChannelV1>,
    val meta: MetaV1? = null,
    val useMyCollections: Boolean? = null,
    val viewerChoicesKnown: Boolean? = null,
    val curationBasis: BasisV1? = null,
    val repairs: JsonElement? = null,
)

@Serializable
private class BasisV1(
    val fromMs: Long,
    val on: List<String>,
    val household: Boolean,
) {
    /** The basis, keeping only the IDs of channels of the lineup ([ids]). */
    fun basis(ids: Set<ChannelId>): CurationBasis {
        val known = on.mapNotNull { raw -> raw.takeIf { it.isNotBlank() }?.let(::ChannelId)?.takeIf { it in ids } }
        return CurationBasis(fromMs, known.toSet(), household)
    }

    companion object {
        /** Written sorted by ID, and only IDs of channels of the lineup. */
        fun of(
            basis: CurationBasis,
            ids: Set<ChannelId>,
        ): BasisV1 =
            BasisV1(
                basis.fromMs,
                basis.on
                    .filter { it in ids }
                    .map { it.value }
                    .sorted(),
                basis.household,
            )
    }
}

@Serializable
private class MetaV1(
    val plannedGeneration: Long? = null,
    val collectionPassActedOn: Long? = null,
    val plannedLibraries: List<String>? = null,
    val extrasFromMs: Long? = null,
    val replanFromMs: Long? = null,
) {
    val refreshMeta
        get() = RefreshMeta(plannedGeneration, collectionPassActedOn, plannedLibraries, extrasFromMs, replanFromMs)

    companion object {
        /** Null for an empty [meta], so a file with nothing to say holds no `meta` key. */
        fun of(meta: RefreshMeta): MetaV1? =
            if (meta == RefreshMeta()) {
                null
            } else {
                MetaV1(
                    meta.plannedGeneration,
                    meta.collectionPassActedOn,
                    meta.plannedLibraries,
                    meta.extrasFromMs,
                    meta.replanFromMs,
                )
            }
    }
}

@Suppress("LongParameterList")
@Serializable
private class ChannelV1(
    val id: String,
    val defaultNumber: Int?,
    val numberOverride: Int?,
    val nameOverride: String?,
    val state: StateV1,
    val onSinceMs: Long,
    val source: JsonElement,
    val chosenByViewer: Boolean? = null,
    val addedByViewer: Boolean? = null,
    val leavesAtMs: Long? = null,
    val shelfReason: JsonElement? = null,
)

private const val NO_NUMBER_NAME = "noNumber"
private val NO_NUMBER_JSON = JsonPrimitive(NO_NUMBER_NAME)

// The wire names of the shelf reasons. A new value is a new version (see the class comment).
private val REASON_NAMES =
    mapOf(
        ShelfReason.Empty to "empty",
        ShelfReason.TooThin to "tooThin",
        ShelfReason.BelowCap to "belowCap",
        ShelfReason.NoNumber to NO_NUMBER_NAME,
        ShelfReason.OptIn to "optIn",
    )

// The reason [element] names. On a starter an unknown or mistyped one throws (the file is unreadable); anywhere else
// the field is ignored, so null.
private fun reasonOf(
    element: JsonElement?,
    starter: Boolean,
): ShelfReason? {
    if (!starter || element == null || element is JsonNull) return null
    val name = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    return requireNotNull(REASON_NAMES.entries.firstOrNull { it.value == name }) { "An unknown shelf reason" }.key
}

@Serializable
private enum class StateV1(
    val state: ChannelState,
) {
    @SerialName("on")
    On(ChannelState.On),

    @SerialName("shelf")
    Shelf(ChannelState.Shelf),

    @SerialName("userHidden")
    UserHidden(ChannelState.UserHidden),
    ;

    companion object {
        fun of(state: ChannelState): StateV1 = entries.first { it.state == state }
    }
}
