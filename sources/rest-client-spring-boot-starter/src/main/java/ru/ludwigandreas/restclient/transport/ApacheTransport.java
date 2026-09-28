package ru.ludwigandreas.restclient.transport;

import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.client5.http.socket.LayeredConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import ru.ludwigandreas.restclient.config.ClientProperties;
import ru.ludwigandreas.restclient.config.PoolProperties;
import ru.ludwigandreas.restclient.config.ProxyProperties;
import ru.ludwigandreas.restclient.config.RedirectPolicy;
import ru.ludwigandreas.restclient.config.TlsProperties;

/**
 * The {@code apache} transport: Apache HttpClient 5, and the only engine here whose connection pool
 * is a real, observable, configurable thing.
 *
 * <p>Choose it when the pool matters - a dependency that must be bounded, a partner that limits
 * concurrent connections, a deployment that needs pool gauges on a dashboard. The
 * {@link PoolingHttpClientConnectionManager} is returned as the transport's pool handle so
 * {@code ConnectionPoolGauges} can publish leased/pending/available without this class knowing that
 * metrics exist.
 *
 * <p>Two deliberate omissions. <strong>Automatic retries are disabled</strong>: HttpClient's own
 * retry handler would sit below this starter's, so a call configured for three attempts would make
 * up to nine, and the retry metrics would count a third of what actually happened.
 * <strong>HTTP/2 is not offered</strong>: the classic (blocking) client speaks HTTP/1.1 only, so a
 * client that needs h2 runs on the JDK engine or in {@code async} mode - the validator says so
 * rather than letting the flag sit there doing nothing.
 */
public final class ApacheTransport {

    private ApacheTransport() {
    }

    /** Builds the Apache-backed transport for one named client. */
    public static Transport create(String clientName, ClientProperties props,
                                   ResourceLoader resourceLoader) {
        PoolingHttpClientConnectionManager connectionManager =
                connectionManager(clientName, props, resourceLoader);

        HttpClientBuilder builder = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig(props))
                // See the class comment: our retry is the only retry.
                .disableAutomaticRetries()
                .evictIdleConnections(TimeValue.of(
                        props.getPool().getIdleEviction().toMillis(), TimeUnit.MILLISECONDS))
                .setKeepAliveStrategy((response, context) ->
                        TimeValue.of(props.getPool().getKeepAlive().toMillis(), TimeUnit.MILLISECONDS));

        if (!Boolean.TRUE.equals(props.getCompression())) {
            builder.disableContentCompression();
        }
        if (props.getRedirects() == RedirectPolicy.NEVER) {
            builder.disableRedirectHandling();
        }
        applyProxy(builder, props.getProxy());

        CloseableHttpClient httpClient = builder.build();
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectionRequestTimeout(props.getConnectionRequestTimeout());
        return new Transport(factory, connectionManager, () -> closeQuietly(httpClient, connectionManager));
    }

    private static PoolingHttpClientConnectionManager connectionManager(
            String clientName, ClientProperties props, ResourceLoader resourceLoader) {
        PoolProperties pool = props.getPool();
        PoolingHttpClientConnectionManagerBuilder builder = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(pool.getMaxTotal())
                .setMaxConnPerRoute(pool.getMaxPerRoute())
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(timeout(props.getConnectTimeout()))
                        // Apache's "socket timeout" is the inter-byte gap, which is exactly what
                        // read-timeout means here; the whole-call deadline is the response timeout
                        // set on the RequestConfig below.
                        .setSocketTimeout(timeout(props.getReadTimeout()))
                        .setTimeToLive(TimeValue.of(pool.getTimeToLive().toMillis(), TimeUnit.MILLISECONDS))
                        .setValidateAfterInactivity(TimeValue.of(
                                pool.getValidateAfterInactivity().toMillis(), TimeUnit.MILLISECONDS))
                        .build());

        LayeredConnectionSocketFactory socketFactory = socketFactory(clientName, props.getTls(), resourceLoader);
        if (socketFactory != null) {
            builder.setSSLSocketFactory(socketFactory);
        }
        return builder.build();
    }

    private static LayeredConnectionSocketFactory socketFactory(String clientName, TlsProperties tls,
                                                                ResourceLoader resourceLoader) {
        SSLContext sslContext = SslContexts.build(tls, resourceLoader, clientName);
        boolean verifyHost = !Boolean.FALSE.equals(tls.getHostnameVerification());
        boolean customProtocols = tls.getProtocols() != null && !tls.getProtocols().isEmpty();
        boolean customCiphers = tls.getCipherSuites() != null && !tls.getCipherSuites().isEmpty();
        if (sslContext == null && verifyHost && !customProtocols && !customCiphers) {
            return null;
        }
        SSLConnectionSocketFactoryBuilder builder = SSLConnectionSocketFactoryBuilder.create();
        if (sslContext != null) {
            builder.setSslContext(sslContext);
        }
        if (customProtocols) {
            builder.setTlsVersions(tls.getProtocols().toArray(new String[0]));
        }
        if (customCiphers) {
            builder.setCiphers(tls.getCipherSuites().toArray(new String[0]));
        }
        if (!verifyHost) {
            builder.setHostnameVerifier(NoopHostnameVerifier.INSTANCE);
        }
        return builder.build();
    }

    private static RequestConfig requestConfig(ClientProperties props) {
        RequestConfig.Builder builder = RequestConfig.custom()
                .setConnectionRequestTimeout(timeout(props.getConnectionRequestTimeout()))
                .setRedirectsEnabled(props.getRedirects() != RedirectPolicy.NEVER);
        if (props.getRequestTimeout() != null) {
            builder.setResponseTimeout(timeout(props.getRequestTimeout()));
        }
        return builder.build();
    }

    private static void applyProxy(HttpClientBuilder builder, ProxyProperties proxy) {
        if (proxy == null || proxy.getHost() == null) {
            return;
        }
        HttpHost proxyHost = new HttpHost(proxy.getHost(), proxy.getPort());
        NonProxyHosts bypass = new NonProxyHosts(proxy.getNonProxyHosts());
        // A route planner rather than setProxy(): setProxy has no notion of exceptions, and the
        // bypass list is the whole reason an egress proxy is tolerable in the first place.
        builder.setRoutePlanner(new DefaultProxyRoutePlanner(proxyHost) {
            @Override
            protected HttpHost determineProxy(HttpHost target, HttpContext context) {
                return bypass.bypasses(target.getHostName()) ? null : proxyHost;
            }
        });
        if (proxy.getUsername() != null) {
            BasicCredentialsProvider credentials = new BasicCredentialsProvider();
            credentials.setCredentials(new AuthScope(proxyHost), new UsernamePasswordCredentials(
                    proxy.getUsername(),
                    proxy.getPassword() == null ? new char[0] : proxy.getPassword().toCharArray()));
            builder.setDefaultCredentialsProvider(credentials);
        }
    }

    private static Timeout timeout(java.time.Duration duration) {
        return Timeout.of(duration.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void closeQuietly(CloseableHttpClient httpClient,
                                     PoolingHttpClientConnectionManager connectionManager) {
        // Closing at context shutdown rather than relying on finalization: an un-closed pool keeps
        // its sockets and its eviction thread, which turns a rolling restart into a slow leak of
        // both on the machine.
        try {
            httpClient.close();
        } catch (java.io.IOException ex) {
            connectionManager.close();
            return;
        }
        connectionManager.close();
    }
}
