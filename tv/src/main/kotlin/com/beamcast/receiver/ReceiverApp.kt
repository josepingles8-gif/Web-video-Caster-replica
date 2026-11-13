package com.beamcast.receiver

import android.app.Application
import android.os.Build
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class ReceiverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Old TVs ship an outdated certificate store (Android 7 lacks Let's Encrypt's root, among
        // others), so many HTTPS video hosts fail with "Trust anchor for certification path not found".
        if (Build.VERSION.SDK_INT < 29) runCatching { trustBundledRoots() }
    }

    /** Accepts servers trusted by the system store or by the bundled, current Mozilla root list. */
    private fun trustBundledRoots() {
        val bundled = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        resources.openRawResource(R.raw.cacerts).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input)
                .forEachIndexed { i, cert -> bundled.setCertificateEntry("ca$i", cert) }
        }
        val system = trustManager(null)
        val extra = trustManager(bundled)
        val both = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (_: CertificateException) {
                    extra.checkServerTrusted(chain, authType)
                }
            }

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + extra.acceptedIssuers
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(both), null) }
        HttpsURLConnection.setDefaultSSLSocketFactory(context.socketFactory)
    }

    private fun trustManager(store: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.first { it is X509TrustManager } as X509TrustManager
}
