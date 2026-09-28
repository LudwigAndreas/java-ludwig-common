package ru.ludwigandreas.restclient.transport;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import ru.ludwigandreas.restclient.config.ClientProperties;
import ru.ludwigandreas.restclient.config.RedirectPolicy;
import ru.ludwigandreas.restclient.config.TlsProperties;

/**
 * The default {@code sync} transport: the JDK's own {@code java.net.http.HttpClient}.
 *
 * <h2>What it does not do, and why that is stated rather than papered over</h2>
 *
 * <p><strong>No configurable pool.</strong> The JDK keeps connections alive and reuses them, and
 * exposes not one knob for how many or for how long. A client that needs to bound concurrency per
 * dependency uses the {@code apache} engine, or - better, because it bounds the thing that actually
 * matters - a bulkhead. The startup validator says so when a pool block is written against this
 * engine, rather than letting the numbers sit in the YAML looking effective.
 *
 * <p><strong>One timeout, not two.</strong> The JDK has no socket read timeout distinct from a
 * request deadline: {@code HttpRequest.timeout()} bounds the whole exchange. This transport sets it
 * from {@code request-timeout} when that is configured and from {@code read-timeout} otherwise,
 * which is the interpretation that cannot produce a call that outlives its configured budget.
 *
 * <p><strong>No transparent gzip.</strong> The JDK client neither advertises nor decodes it. The
 * pipeline adds the header and decodes the body itself - see
 * {@link GzipDecodingClientHttpResponse} - so {@code compression: true} means the same thing on
 * every engine.
 */
public final class JdkTransport {

    private JdkTransport() {
    }

    /** Builds the JDK-backed transport for one named client. */
    public static Transport create(String clientName, ClientProperties props,
                                   ResourceLoader resourceLoader) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(props.getConnectTimeout())
                .followRedirects(redirects(props.getRedirects()))
                .version(Boolean.TRUE.equals(props.getHttp2())
                        ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1);

        SSLContext sslContext = SslContexts.build(props.getTls(), resourceLoader, clientName);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        SSLParameters sslParameters = sslParameters(props.getTls());
        if (sslParameters != null) {
            builder.sslParameters(sslParameters);
        }
        if (props.getProxy() != null && props.getProxy().getHost() != null) {
            builder.proxy(proxySelector(props));
        }

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(builder.build());
        factory.setReadTimeout(props.getRequestTimeout() != null
                ? props.getRequestTimeout() : props.getReadTimeout());
        return Transport.of(factory);
    }

    private static HttpClient.Redirect redirects(RedirectPolicy policy) {
        return switch (policy) {
            case NEVER -> HttpClient.Redirect.NEVER;
            case ALWAYS -> HttpClient.Redirect.ALWAYS;
            case NORMAL -> HttpClient.Redirect.NORMAL;
        };
    }

    /**
     * Protocols, ciphers and endpoint identification, or {@code null} when none of the three is
     * configured.
     *
     * <p>Returning {@code null} matters: handing the builder a blank {@code SSLParameters} replaces
     * the JDK's defaults with nothing, which is how a client ends up negotiating a protocol set the
     * platform never chose.
     */
    private static SSLParameters sslParameters(TlsProperties tls) {
        boolean verifyHost = !Boolean.FALSE.equals(tls.getHostnameVerification());
        List<String> protocols = tls.getProtocols();
        List<String> ciphers = tls.getCipherSuites();
        if (verifyHost && (protocols == null || protocols.isEmpty()) && (ciphers == null || ciphers.isEmpty())) {
            return null;
        }
        SSLParameters parameters = new SSLParameters();
        if (protocols != null && !protocols.isEmpty()) {
            parameters.setProtocols(protocols.toArray(new String[0]));
        }
        if (ciphers != null && !ciphers.isEmpty()) {
            parameters.setCipherSuites(ciphers.toArray(new String[0]));
        }
        // "HTTPS" is what turns on hostname verification in the JDK's TLS stack; null turns it off.
        // There is no third value, and the absence of the algorithm is exactly the silent hole this
        // property exists to make explicit.
        parameters.setEndpointIdentificationAlgorithm(verifyHost ? "HTTPS" : null);
        return parameters;
    }

    /**
     * A selector that proxies everything except the configured bypass list.
     *
     * <p>A {@code ProxySelector} rather than a single {@code Proxy} because the JDK offers no other
     * way to express "all of these hosts, except those".
     */
    private static ProxySelector proxySelector(ClientProperties props) {
        InetSocketAddress address = InetSocketAddress.createUnresolved(
                props.getProxy().getHost(), props.getProxy().getPort());
        NonProxyHosts bypass = new NonProxyHosts(props.getProxy().getNonProxyHosts());
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return bypass.bypasses(uri.getHost())
                        ? List.of(Proxy.NO_PROXY)
                        : List.of(new Proxy(Proxy.Type.HTTP, address));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, java.io.IOException ioe) {
                // Nothing to fail over to: there is one proxy. The failure is reported to the caller
                // by the exchange that is about to throw, and swallowing it here only hides it.
            }
        };
    }
}
