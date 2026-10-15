// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.trust

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import tv.stationbreak.core.model.ServerAddress
import tv.stationbreak.core.model.SpkiPin
import tv.stationbreak.data.jellyfin.HttpClientSource
import java.security.SecureRandom
import javax.net.ssl.SSLContext

/** Where pins are added and removed. The adapter's `trust` uses it; the app's settings keep it in step with storage. */
interface PinRegistry {
    /** Trusts [pin] for exactly [address]'s scheme, host and port, in a new client. A no-op for `http`. */
    fun pin(
        address: ServerAddress,
        pin: SpkiPin,
    )

    /** Removes the pin for [address], in a new client. A no-op when there is none. */
    fun unpin(address: ServerAddress)

    /** The pin in force for exactly [address], or null (always for `http`). */
    fun pinFor(address: ServerAddress): SpkiPin?

    companion object {
        /** A registry that holds nothing: for an adapter on a client that does no pinning. */
        val NONE: PinRegistry =
            object : PinRegistry {
                override fun pin(
                    address: ServerAddress,
                    pin: SpkiPin,
                ) = Unit

                override fun unpin(address: ServerAddress) = Unit

                override fun pinFor(address: ServerAddress): SpkiPin? = null
            }
    }
}

/**
 * The one pinned client (spec 05, Unit 3, Angua (Security)): the base the adapter derives every client from, and so the
 * trust that images, subtitles and the player share.
 *
 * Whenever the set of pins changes, it builds a new `SSLContext` (and with it a new TLS session cache), a new client,
 * a new connection pool and a new dispatcher, swaps them in, evicts the old pool and cancels the old client's running
 * calls. A pooled connection or a resumed TLS session made under the old pins is never used under the new ones.
 * Callers ask [current] for every call and never keep the client. [template] brings the timeouts, DNS and so on; its
 * trust settings are replaced.
 */
class PinnedHttpClients(
    private val system: SystemTrust,
    private val template: OkHttpClient = OkHttpClient(),
) : HttpClientSource,
    PinRegistry {
    private class Built(
        val pins: Map<PinKey, SpkiPin>,
        val client: OkHttpClient,
    )

    @Volatile
    private var built = build(emptyMap())

    override fun current(): OkHttpClient = built.client

    /**
     * For a library that keeps its [Call.Factory] (Media3's data source, Coil): every call is made on the client in
     * force at that moment, so a kept factory never trusts what the pins no longer allow. Never hand such a library
     * an [OkHttpClient] from [current].
     */
    val callFactory: Call.Factory = Call.Factory { request -> built.client.newCall(request) }

    @Synchronized
    override fun pin(
        address: ServerAddress,
        pin: SpkiPin,
    ) {
        val key = PinKey.of(address) ?: return
        replace(built.pins + (key to pin))
    }

    @Synchronized
    override fun unpin(address: ServerAddress) {
        val key = PinKey.of(address) ?: return
        replace(built.pins - key)
    }

    override fun pinFor(address: ServerAddress): SpkiPin? = PinKey.of(address)?.let { built.pins[it] }

    /** Makes [pins] exactly the pins in force: a stored record applied at launch, or a cleared one. */
    @Synchronized
    fun setPins(pins: Map<ServerAddress, SpkiPin>) {
        replace(pins.mapNotNull { (address, pin) -> PinKey.of(address)?.let { it to pin } }.toMap())
    }

    // Callers hold the monitor.
    private fun replace(pins: Map<PinKey, SpkiPin>) {
        if (pins == built.pins) return
        val old = built
        built = build(pins)
        old.client.connectionPool.evictAll()
        // evictAll() closes only idle connections; a call already running would finish under the old trust. The old
        // client has a dispatcher of its own (see build), so this cancels exactly its calls and nobody else's.
        old.client.dispatcher.cancelAll()
    }

    private fun build(pins: Map<PinKey, SpkiPin>): Built {
        val policy = PinPolicy(pins)
        val trustManager = PinningTrustManager(system, policy)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustManager), SecureRandom())
        val client =
            template
                .newBuilder()
                .sslSocketFactory(context.socketFactory, trustManager)
                .hostnameVerifier(PinnedHostnameVerifier(policy))
                .connectionPool(ConnectionPool())
                .dispatcher(ownDispatcher())
                .build()
        return Built(pins, client)
    }

    // One dispatcher per build, so cancelling the old build's calls never touches the new one's (or a shared one's). It
    // shares the template's thread pool and keeps its limits.
    private fun ownDispatcher(): Dispatcher =
        Dispatcher(template.dispatcher.executorService).also {
            it.maxRequests = template.dispatcher.maxRequests
            it.maxRequestsPerHost = template.dispatcher.maxRequestsPerHost
        }
}
