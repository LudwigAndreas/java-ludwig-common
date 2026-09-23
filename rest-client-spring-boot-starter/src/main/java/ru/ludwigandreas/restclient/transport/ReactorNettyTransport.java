package ru.ludwigandreas.restclient.transport;

import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;
import ru.ludwigandreas.restclient.config.ClientProperties;
import ru.ludwigandreas.restclient.config.ProxyProperties;
import ru.ludwigandreas.restclient.config.RedirectPolicy;
import ru.ludwigandreas.restclient.config.TlsProperties;

/**
 * The {@code async} transport: Reactor Netty behind a {@code WebClient}.
 *
 * <p>Each named client gets its own {@link ConnectionProvider}, named after the client. That is what
 * makes the isolation promise true in reactive mode - Reactor Netty's default provider is a
 * process-wide singleton, so without this every {@code WebClient} in the service would share one
 * pool and a saturated dependency would starve the others, which is precisely the failure this
 * starter exists to prevent. It also makes the pool legible: with metrics on, the provider's name is
 * the {@code id} tag on {@code reactor.netty.connection.provider.*}, so a dashboard can attribute
 * pending acquisitions to a dependency.
 *
 * <p>Pool metrics are Reactor Netty's own rather than a re-implementation. The Apache engine has no
 * equivalent and gets {@code ludwig.restclient.pool.*} gauges instead; the README's metric catalogue
 * lists both, because pretending one shape fits both engines would mean publishing a gauge that is
 * always zero for one of them.
 */
public final class ReactorNettyTransport {

    private ReactorNettyTransport() {
    }

    /**
     * Builds the connector for one named client.
     *
     * @param publishPoolMetrics whether Reactor Netty publishes its connection-provider meters
     */
    public static ReactiveTransport create(String clientName, ClientProperties props,
                                           ResourceLoader resourceLoader, boolean publishPoolMetrics) {
        ConnectionProvider provider = connectionProvider(clientName, props);
        HttpClient httpClient = HttpClient.create(provider)
                .compress(Boolean.TRUE.equals(props.getCompression()))
                .followRedirect(props.getRedirects() != RedirectPolicy.NEVER)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) props.getConnectTimeout().toMillis())
                // A read-timeout handler on the pipeline rather than a socket option: Netty has no
                // blocking read to time out, so the only way to express "no bytes for N seconds" is a
                // handler that fires on an idle channel.
                .doOnConnected(connection -> connection.addHandlerLast(
                        new ReadTimeoutHandler(props.getReadTimeout().toMillis(), TimeUnit.MILLISECONDS)))
                .protocol(Boolean.TRUE.equals(props.getHttp2())
                        ? new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}
                        : new HttpProtocol[]{HttpProtocol.HTTP11});

        if (props.getRequestTimeout() != null) {
            httpClient = httpClient.responseTimeout(props.getRequestTimeout());
        }
        httpClient = applyTls(httpClient, clientName, props.getTls(), resourceLoader);
        httpClient = applyProxy(httpClient, props.getProxy());
        if (publishPoolMetrics) {
            httpClient = httpClient.metrics(true, java.util.function.Function.identity());
        }
        // The provider is disposed at context shutdown: it owns the sockets and the eviction task,
        // and an un-disposed one turns a rolling restart into a slow leak of both.
        return new ReactiveTransport(new ReactorClientHttpConnector(httpClient), provider::dispose);
    }

    /**
     * A built reactive transport and the way to release it.
     *
     * @param connector what the {@code WebClient} exchanges through
     * @param closer    disposes the connection provider on context shutdown
     */
    public record ReactiveTransport(ClientHttpConnector connector, Runnable closer) {
    }

    private static ConnectionProvider connectionProvider(String clientName, ClientProperties props) {
        Duration idle = props.getPool().getIdleEviction();
        return ConnectionProvider.builder("ludwig-rest-client-" + clientName)
                .maxConnections(props.getPool().getMaxTotal())
                .pendingAcquireTimeout(props.getConnectionRequestTimeout())
                .maxIdleTime(idle)
                .maxLifeTime(props.getPool().getTimeToLive())
                // Evicting in the background rather than only on acquisition is what keeps a pool
                // from holding sockets a peer closed during a quiet period - the failure that shows
                // up as one "connection reset" after every lull and never reproduces under load.
                .evictInBackground(idle)
                .build();
    }

    private static HttpClient applyTls(HttpClient httpClient, String clientName, TlsProperties tls,
                                       ResourceLoader resourceLoader) {
        KeyManagerFactory keyManagers = SslContexts.keyManagerFactory(tls, resourceLoader, clientName);
        TrustManagerFactory trustManagers = SslContexts.trustManagerFactory(tls, resourceLoader, clientName);
        boolean trustAll = Boolean.TRUE.equals(tls.getTrustAll());
        List<String> protocols = tls.getProtocols();
        List<String> ciphers = tls.getCipherSuites();
        boolean verifyHost = !Boolean.FALSE.equals(tls.getHostnameVerification());
        if (keyManagers == null && trustManagers == null && !trustAll && verifyHost
                && isEmpty(protocols) && isEmpty(ciphers)) {
            return httpClient;
        }
        return httpClient.secure(spec -> spec.sslContext(buildNettySslContext(
                        keyManagers, trustManagers, trustAll, protocols, ciphers))
                // Netty verifies the host through the SSL parameters of the engine it creates, so
                // switching verification off means clearing the identification algorithm there.
                .handlerConfigurator(handler -> {
                    javax.net.ssl.SSLParameters parameters = handler.engine().getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm(verifyHost ? "HTTPS" : null);
                    handler.engine().setSSLParameters(parameters);
                }));
    }

    private static io.netty.handler.ssl.SslContext buildNettySslContext(
            KeyManagerFactory keyManagers, TrustManagerFactory trustManagers, boolean trustAll,
            List<String> protocols, List<String> ciphers) {
        SslContextBuilder builder = SslContextBuilder.forClient();
        if (keyManagers != null) {
            builder.keyManager(keyManagers);
        }
        if (trustAll) {
            builder.trustManager(io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE);
        } else if (trustManagers != null) {
            builder.trustManager(trustManagers);
        }
        if (!isEmpty(protocols)) {
            builder.protocols(protocols.toArray(new String[0]));
        }
        if (!isEmpty(ciphers)) {
            builder.ciphers(ciphers);
        }
        try {
            return builder.build();
        } catch (javax.net.ssl.SSLException ex) {
            throw new IllegalStateException("TLS context for the reactive transport could not be built", ex);
        }
    }

    private static HttpClient applyProxy(HttpClient httpClient, ProxyProperties proxy) {
        if (proxy == null || proxy.getHost() == null) {
            return httpClient;
        }
        return httpClient.proxy(spec -> {
            ProxyProvider.Builder builder = spec.type(ProxyProvider.Proxy.HTTP)
                    .host(proxy.getHost())
                    .port(proxy.getPort());
            if (proxy.getUsername() != null) {
                builder.username(proxy.getUsername());
                builder.password(user -> proxy.getPassword());
            }
            String bypass = nonProxyHostsRegex(proxy.getNonProxyHosts());
            if (bypass != null) {
                builder.nonProxyHosts(bypass);
            }
        });
    }

    /**
     * Translates the {@code *.suffix} bypass list into the regular expression Reactor Netty wants.
     *
     * <p>The same list, in the same syntax, works on all three engines - which matters because the
     * transport is meant to be an operational choice, not something that changes what a
     * configuration key means.
     */
    private static String nonProxyHostsRegex(List<String> patterns) {
        if (isEmpty(patterns)) {
            return null;
        }
        return patterns.stream()
                .map(pattern -> pattern.startsWith("*.")
                        ? "(.*\\.)?" + java.util.regex.Pattern.quote(pattern.substring(2))
                        : java.util.regex.Pattern.quote(pattern))
                .collect(Collectors.joining("|"));
    }

    private static boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }
}
