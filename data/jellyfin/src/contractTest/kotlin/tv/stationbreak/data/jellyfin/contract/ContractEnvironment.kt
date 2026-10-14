// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.data.jellyfin.contract

import org.junit.jupiter.api.Assumptions
import org.testcontainers.DockerClientFactory

/** What the contract suite needs from its surroundings, and the images it starts besides Jellyfin. */
internal object ContractEnvironment {
    /** The Jellyfin image to test, by digest (the workflow's matrix sets it). */
    const val IMAGE_VARIABLE = "JELLYFIN_IMAGE"

    /**
     * Caddy 2.11.7 (alpine), pinned by its multi-arch index digest (R14). It is only a reverse proxy in front of the
     * server under test; to move it, resolve a new tag with `docker buildx imagetools inspect caddy:<tag>`.
     */
    const val CADDY_IMAGE = "caddy@sha256:d8542f48d34a9cf4e4c11a478865229840e87e4c96ea3f439101f31a5d35f75f"

    /** Jellyfin's Base URL behind the proxy, and the path the proxy forwards. */
    const val SUBPATH = "/jf"

    /** Skips the whole suite (not a failure) when there is no Docker to start containers with. */
    fun requireDocker() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker isn't available: skipped")
    }

    fun jellyfinImage(): String =
        System.getenv(IMAGE_VARIABLE)?.takeIf { it.isNotBlank() }
            ?: error("Set $IMAGE_VARIABLE to the image to test (CONTRIBUTING.md, \"Container contract tests\")")
}
