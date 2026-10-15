// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.app.net

import android.net.http.X509TrustManagerExtensions
import tv.stationbreak.data.jellyfin.trust.SystemTrust
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * The system's own trust for one chain and host (spec 05, Unit 3, Angua (Security)). The delegate is the default
 * `TrustManagerFactory.init(null)`, which is the Network Security Config's trust: the system store plus the bundled
 * ISRG roots (`network_security_config.xml`). It is asked through `X509TrustManagerExtensions.checkServerTrusted(chain,
 * authType, host)`, which applies the config's per-host rules and returns the validated chain. It is never replaced,
 * widened or skipped here: whether a pin may accept a chain the system refused is the pinning trust manager's rule.
 */
internal class AndroidSystemTrust : SystemTrust {
    private val extensions = X509TrustManagerExtensions(SystemTrust.defaultX509TrustManager())

    @Throws(CertificateException::class)
    override fun check(
        chain: Array<X509Certificate>,
        authType: String,
        host: String,
    ) {
        extensions.checkServerTrusted(chain, authType, host)
    }
}
