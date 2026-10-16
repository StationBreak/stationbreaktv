// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.rules

import tv.stationbreak.core.model.CanonicalId
import tv.stationbreak.core.model.Seed
import tv.stationbreak.core.model.ordering.fnv1a64
import tv.stationbreak.core.model.ordering.splitmix64

/** What an automatic channel is made from. [wireName] is the ASCII word that goes into the seed's bytes. */
enum class AutoSeedKind(
    val wireName: String,
) {
    Library("library"),
    Genre("genre"),
    Collection("collection"),
}

/**
 * The seed of an automatic channel (docs/rules-format.md, "Seed"): `splitmix64(fnv1a64(bytes))`, where the bytes are
 * the 32 ASCII characters of the server ID, `0x00`, the kind in ASCII, `0x00`, and the key. The key is the 32 ASCII
 * characters of the canonical ID for a library or a collection, and the exact UTF-8 bytes of the name for a genre: no
 * case folding and no Unicode normalisation. The channel's own ID and name are never inputs, so two TVs on one
 * account get one seed.
 */
object AutoSeed {
    private const val SEPARATOR: Byte = 0

    /**
     * The seed for [kind] and [key] on the server [serverId]. Throws [IllegalArgumentException] for a server ID that is
     * not in canonical form (32 lower-case hex characters), a library or collection key that is not, and a genre name
     * that is empty or has an unpaired surrogate (which would be encoded as a different character, silently).
     */
    fun derive(
        serverId: String,
        kind: AutoSeedKind,
        key: String,
    ): Seed = Seed(splitmix64(fnv1a64(bytes(serverId, kind, key))))

    /** The bytes that are hashed. Internal: tests compare them with the published vectors. */
    internal fun bytes(
        serverId: String,
        kind: AutoSeedKind,
        key: String,
    ): ByteArray {
        requireNotNull(CanonicalId.parseCanonicalOrNull(serverId)) { "The server ID is not in canonical form" }
        when (kind) {
            AutoSeedKind.Genre -> {
                require(key.isNotEmpty()) { "A genre name is not empty" }
                require(!RulesLimits.hasUnpairedSurrogate(key)) { "A genre name has a character that is not text" }
            }

            AutoSeedKind.Library, AutoSeedKind.Collection -> {
                requireNotNull(CanonicalId.parseCanonicalOrNull(key)) { "The ID is not in canonical form" }
            }
        }
        val head = serverId.encodeToByteArray() + SEPARATOR + kind.wireName.encodeToByteArray() + SEPARATOR
        return head + key.encodeToByteArray()
    }
}
