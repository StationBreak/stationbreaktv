// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import tv.stationbreak.core.model.Scheme
import tv.stationbreak.core.model.ServerAddress
import java.time.Duration

/** A user the suite signs in as. The password is random per run and is never printed. */
internal class Account(
    val name: String,
    private val password: String,
) {
    fun password(): CharArray = password.toCharArray()

    fun rawPassword(): String = password

    override fun toString(): String = "Account(name=$name)"
}

/** What the seeded server holds, by ID, for the cases to ask about. */
internal class Seeded(
    val moviesLibrary: String,
    val tvLibrary: String,
    val mixedLibrary: String,
    val movieIds: List<String>,
    val episodeIds: List<String>,
)

/**
 * A running, fully set up Jellyfin (the image the workflow chose) that a suite can sign in to: wizard done, Quick
 * Connect on, a Movies, a TV and a mixed-content library of synthetic media, an admin that exists only in this
 * container, and a non-admin viewer plus a disabled user. Nothing is mounted from the host and no container log is
 * read; the passwords are random per run.
 */
internal class JellyfinServer private constructor(
    private val containers: List<AutoCloseable>,
    val address: ServerAddress,
    val http: Harness,
    val viewer: Account,
    val disabled: Account,
    val seeded: Seeded,
) : AutoCloseable {
    override fun close() = containers.asReversed().forEach { runCatching { it.close() } }

    companion object {
        private const val JELLYFIN_PORT = 8096
        private const val HTTP_PORT = 80
        private val STARTUP: Duration = Duration.ofMinutes(3)

        // The Base URL is read from this file at start. The image keeps its configuration under /config/config.
        private val NETWORK_XML =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <NetworkConfiguration xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
              <BaseUrl>${ContractEnvironment.SUBPATH}</BaseUrl>
            </NetworkConfiguration>
            """.trimIndent()

        /** A server answering directly on its own mapped port, at no subpath. */
        fun direct(image: String): JellyfinServer = start(image, proxy = false)

        /** A server whose Base URL is [ContractEnvironment.SUBPATH], reached only through a Caddy container. */
        fun behindProxy(image: String): JellyfinServer = start(image, proxy = true)

        private fun start(
            image: String,
            proxy: Boolean,
        ): JellyfinServer {
            val started = mutableListOf<AutoCloseable>()
            var ready = false
            try {
                val network = if (proxy) Network.newNetwork().also { started += it } else null
                val jellyfin = jellyfinContainer(image, network)
                jellyfin.start()
                started += jellyfin
                val address =
                    if (network == null) {
                        ServerAddress(Scheme.Http, jellyfin.host.lowercase(), jellyfin.getMappedPort(JELLYFIN_PORT))
                    } else {
                        val caddy = caddyContainer(network)
                        caddy.start()
                        started += caddy
                        ServerAddress(
                            Scheme.Http,
                            caddy.host.lowercase(),
                            caddy.getMappedPort(HTTP_PORT),
                            ContractEnvironment.SUBPATH,
                        )
                    }
                val http = Harness("${address.baseUrl}/".toHttpUrl())
                val accounts = ServerSeeding.Accounts()
                val seeded = ServerSeeding.seed(jellyfin, http, accounts)
                ready = true
                return JellyfinServer(started, address, http, accounts.viewer, accounts.disabled, seeded)
            } finally {
                if (!ready) started.asReversed().forEach { runCatching { it.close() } }
            }
        }

        private fun jellyfinContainer(
            image: String,
            network: Network?,
        ): GenericContainer<*> {
            val ready = if (network == null) "" else ContractEnvironment.SUBPATH
            return GenericContainer(DockerImageName.parse(image))
                .withExposedPorts(JELLYFIN_PORT)
                .onLoopbackOnly(JELLYFIN_PORT)
                .apply {
                    if (network != null) {
                        withNetwork(network).withNetworkAliases("jellyfin")
                        withCopyToContainer(Transferable.of(NETWORK_XML), "/config/config/network.xml")
                    }
                }.waitingFor(
                    Wait
                        .forHttp("$ready/System/Info/Public")
                        .forStatusCode(HTTP_OK)
                        .withStartupTimeout(STARTUP),
                )
        }

        private fun caddyContainer(network: Network): GenericContainer<*> =
            GenericContainer(DockerImageName.parse(ContractEnvironment.CADDY_IMAGE))
                .withExposedPorts(HTTP_PORT)
                .onLoopbackOnly(HTTP_PORT)
                .withNetwork(network)
                .withCopyToContainer(MountableFile.forClasspathResource("Caddyfile"), "/etc/caddy/Caddyfile")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(STARTUP))

        private const val HTTP_OK = 200

        /**
         * Publishes [port] on 127.0.0.1 only. Before the wizard completes, anyone who can reach a throwaway server can
         * claim its admin account, so a local run must not offer it to the LAN (Docker publishes on all interfaces by
         * default). The tests reach it through the host name Testcontainers reports, which is the same machine.
         */
        private fun GenericContainer<*>.onLoopbackOnly(port: Int): GenericContainer<*> =
            withCreateContainerCmdModifier { command ->
                val binding = PortBinding(Ports.Binding.bindIp("127.0.0.1"), ExposedPort(port))
                command.hostConfig?.withPortBindings(binding)
            }
    }
}
