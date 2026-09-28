package ru.ludwigandreas.restclient.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import ru.ludwigandreas.restclient.config.TlsProperties;

/**
 * Builds one named client's {@link SSLContext} from its {@code tls} block.
 *
 * <p>Per client, never shared. Two clients that trust different internal CAs, or that present
 * different client certificates, are the normal case in an estate with more than one partner, and a
 * process-wide {@code javax.net.ssl.trustStore} cannot express it - setting it for one dependency
 * changes the trust of every other outbound call in the JVM, including the OIDC issuer.
 */
public final class SslContexts {

    private SslContexts() {
    }

    /**
     * The context for {@code tls}, or {@code null} when nothing is configured and the JVM defaults
     * are correct.
     *
     * <p>Returning {@code null} rather than {@code SSLContext.getDefault()} is deliberate: it lets
     * each engine leave its own SSL configuration untouched, which is how a deployment using an
     * agent or a customized default context keeps working.
     *
     * @throws IllegalStateException if the configured material cannot be read; a client whose
     *                               keystore is missing must not start and then fail every call
     */
    public static SSLContext build(TlsProperties tls, ResourceLoader resourceLoader, String clientName) {
        boolean trustAll = Boolean.TRUE.equals(tls.getTrustAll());
        if (!trustAll && tls.getTruststorePath() == null && tls.getKeystorePath() == null) {
            return null;
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers(tls, resourceLoader), trustManagers(tls, resourceLoader, trustAll), null);
            return context;
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException(
                    "Client '" + clientName + "': TLS material could not be loaded. The client would "
                            + "start and then fail every call, so the context is failed instead.", ex);
        }
    }

    /**
     * The {@link KeyManagerFactory} for this client's own certificate, or {@code null}.
     *
     * <p>Exposed separately because Netty builds its SSL context from the factories rather than from
     * a {@code javax.net.ssl.SSLContext}, and converting one into the other is not possible.
     */
    public static KeyManagerFactory keyManagerFactory(TlsProperties tls, ResourceLoader resourceLoader,
                                                      String clientName) {
        if (tls.getKeystorePath() == null) {
            return null;
        }
        try {
            KeyStore keyStore = load(tls.getKeystorePath(), tls.getKeystorePassword(),
                    tls.getKeystoreType(), resourceLoader);
            KeyManagerFactory factory =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(keyStore, chars(tls.getKeyPassword() != null
                    ? tls.getKeyPassword() : tls.getKeystorePassword()));
            return factory;
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException("Client '" + clientName + "': keystore could not be loaded.", ex);
        }
    }

    /** The {@link TrustManagerFactory} for this client's truststore, or {@code null}. */
    public static TrustManagerFactory trustManagerFactory(TlsProperties tls, ResourceLoader resourceLoader,
                                                          String clientName) {
        if (tls.getTruststorePath() == null) {
            return null;
        }
        try {
            KeyStore trustStore = load(tls.getTruststorePath(), tls.getTruststorePassword(),
                    tls.getTruststoreType(), resourceLoader);
            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore);
            return factory;
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException("Client '" + clientName + "': truststore could not be loaded.", ex);
        }
    }

    private static KeyManager[] keyManagers(TlsProperties tls, ResourceLoader resourceLoader)
            throws GeneralSecurityException, IOException {
        if (tls.getKeystorePath() == null) {
            return null;
        }
        KeyStore keyStore = load(tls.getKeystorePath(), tls.getKeystorePassword(),
                tls.getKeystoreType(), resourceLoader);
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        // The key password falls back to the store password, which is how nearly every PKCS12 in
        // practice is built - a separate key password is a JKS-era distinction.
        char[] keyPassword = chars(tls.getKeyPassword() != null
                ? tls.getKeyPassword() : tls.getKeystorePassword());
        factory.init(keyStore, keyPassword);
        return factory.getKeyManagers();
    }

    private static TrustManager[] trustManagers(TlsProperties tls, ResourceLoader resourceLoader,
                                                boolean trustAll)
            throws GeneralSecurityException, IOException {
        if (trustAll) {
            return new TrustManager[]{new TrustEverything()};
        }
        if (tls.getTruststorePath() == null) {
            return null;
        }
        KeyStore trustStore = load(tls.getTruststorePath(), tls.getTruststorePassword(),
                tls.getTruststoreType(), resourceLoader);
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        return factory.getTrustManagers();
    }

    private static KeyStore load(String location, String password, String type,
                                 ResourceLoader resourceLoader)
            throws GeneralSecurityException, IOException {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IOException("keystore/truststore not found: " + location);
        }
        KeyStore store = KeyStore.getInstance(type == null ? "PKCS12" : type);
        try (InputStream in = resource.getInputStream()) {
            store.load(in, chars(password));
        }
        return store;
    }

    private static char[] chars(String value) {
        return value == null ? null : value.toCharArray();
    }

    /**
     * A trust manager that accepts anything.
     *
     * <p>It exists for one reason: a developer calling a partner's sandbox that presents a
     * self-signed certificate. Reaching it requires {@code tls.trust-all: true} on the client,
     * {@code ludwig.rest-client.allow-trust-all: true} at the top level, and no production profile
     * active - three independent statements, checked by
     * {@code RestClientConfigurationValidator}, because this is the switch that silently turns TLS
     * into an expensive way of sending plaintext.
     *
     * <p>Extends {@code X509ExtendedTrustManager} rather than implementing {@code X509TrustManager}:
     * the two-argument overloads are the ones the JDK calls when endpoint identification is on, and
     * a plain {@code X509TrustManager} gets silently wrapped in one that still verifies.
     */
    private static final class TrustEverything extends X509ExtendedTrustManager {

        // Nothing in this class checks anything: it exists to check nothing. All six overloads are
        // overridden because the JDK picks a different one depending on whether endpoint
        // identification is on and whether the transport is a Socket or an SSLEngine.

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
