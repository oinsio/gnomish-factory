package com.github.oinsio.gnomish.adapter.check

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/**
 * A TLS endpoint on the loopback address that the production http check provider will actually talk
 * to, and the JVM trust window that makes it reachable.
 *
 * <p>Why this exists: the {@code http} provider's production path is always {@code
 * GuardedHttpCheckExchange(JdkHttpCheckExchange, EgressAllowlist)}, and that guard permits {@code
 * https} and nothing else (NFR-S2). So an acceptance spec that wants the <em>real</em>
 * registry-built client — not a stand-in exchange — needs a real certificate. WireMock's bundled
 * keystore cannot serve one: its certificate carries no {@code SubjectAltName} at all, and
 * WireMock's on-the-fly generator keys on SNI, which a client never sends for an IP literal. Hence a
 * certificate minted here with {@code SAN=ip:127.0.0.1}.
 *
 * <p>What is staged is only <em>which certificate authorities this JVM trusts</em> — precisely the
 * act an operator performs to point the factory at a private SonarQube. The code under test is
 * untouched: the guard still refuses plain http, still judges the resolved address, and the
 * allowlist must still name {@code 127.0.0.1} literally for the loopback block to be waived.
 *
 * <p>The trust manager installed is a <em>composite</em>, not a trust-all: the platform default is
 * consulted first and this certificate is only a fallback, so inside the window nothing that used to
 * be trusted stops being, and nothing else starts being. {@link #restore} puts the previous default
 * back.
 */
class LoopbackTlsFixture {

    static final String PASSWORD = 'password'

    /** Mints a PKCS12 holding one self-signed certificate valid for the loopback address. */
    static Path keystore(Path directory) {
        Path store = directory.resolve('loopback.p12')
        def keytool = Path.of(System.getProperty('java.home'), 'bin', 'keytool').toString()
        def process = new ProcessBuilder(
                keytool, '-genkeypair', '-alias', 'loopback', '-keyalg', 'RSA', '-keysize', '2048',
                '-validity', '3650', '-storetype', 'PKCS12', '-keystore', store.toString(),
                '-storepass', PASSWORD, '-keypass', PASSWORD, '-dname', 'CN=localhost',
                '-ext', 'san=ip:127.0.0.1,dns:localhost')
                .redirectErrorStream(true)
                .start()
        def output = process.inputStream.text
        assert process.waitFor() == 0: "keytool failed: ${output}"
        assert Files.isRegularFile(store)
        store
    }

    /**
     * Installs a default {@link SSLContext} trusting {@code keystore} in addition to the platform's
     * own authorities.
     *
     * @return the previous default context, for {@link #restore}
     */
    static SSLContext install(Path keystore) {
        def previous = SSLContext.getDefault()
        def context = SSLContext.getInstance('TLS')
        context.init(null, [
            new CompositeTrustManager(managerOf(null), managerOf(loaded(keystore)))
        ] as X509ExtendedTrustManager[], null)
        SSLContext.setDefault(context)
        previous
    }

    static void restore(SSLContext previous) {
        SSLContext.setDefault(previous)
    }

    /**
     * A truststore holding the keystore's certificate. The minted PKCS12 carries a private-key
     * entry, and {@link TrustManagerFactory} accepts only trusted-certificate entries — so the
     * certificate is lifted out into a store of its own rather than handed over as it sits.
     */
    private static KeyStore loaded(Path keystore) {
        def signed = KeyStore.getInstance('PKCS12')
        Files.newInputStream(keystore).withCloseable { stream ->
            signed.load(stream, PASSWORD.toCharArray())
        }
        def trusted = KeyStore.getInstance('PKCS12')
        trusted.load(null, null)
        trusted.setCertificateEntry('loopback', signed.getCertificate('loopback'))
        trusted
    }

    /** The trust manager of a keystore, or of the platform's own authorities when given null. */
    private static X509ExtendedTrustManager managerOf(KeyStore store) {
        def factory = TrustManagerFactory.getInstance(TrustManagerFactory.defaultAlgorithm)
        factory.init((KeyStore) store)
        factory.trustManagers.find {
            it instanceof X509ExtendedTrustManager
        } as X509ExtendedTrustManager
    }
}

/** Trusts what the platform trusts, and — only when the platform does not — what the fallback does. */
class CompositeTrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager platform
    private final X509ExtendedTrustManager fallback

    CompositeTrustManager(X509ExtendedTrustManager platform, X509ExtendedTrustManager fallback) {
        this.platform = platform
        this.fallback = fallback
    }

    @Override
    X509Certificate[] getAcceptedIssuers() {
        platform.acceptedIssuers + fallback.acceptedIssuers
    }

    @Override
    void checkServerTrusted(X509Certificate[] chain, String authType) {
        either { it.checkServerTrusted(chain, authType) }
    }

    @Override
    void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        either { it.checkServerTrusted(chain, authType, socket) }
    }

    @Override
    void checkServerTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) {
        either { it.checkServerTrusted(chain, authType, engine) }
    }

    @Override
    void checkClientTrusted(X509Certificate[] chain, String authType) {
        either { it.checkClientTrusted(chain, authType) }
    }

    @Override
    void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        either { it.checkClientTrusted(chain, authType, socket) }
    }

    @Override
    void checkClientTrusted(X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine) {
        either { it.checkClientTrusted(chain, authType, engine) }
    }

    private void either(Closure<?> check) {
        try {
            check(platform)
        } catch (CertificateException platformRefused) {
            try {
                check(fallback)
            } catch (CertificateException fallbackRefused) {
                fallbackRefused.addSuppressed(platformRefused)
                throw fallbackRefused
            }
        }
    }
}
