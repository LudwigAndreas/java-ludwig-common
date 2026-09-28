package ru.ludwigandreas.restclient.config;

import jakarta.validation.Valid;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Everything one named HTTP client is.
 *
 * <p>The same class binds {@code ludwig.rest-client.defaults} and every entry under
 * {@code ludwig.rest-client.clients}, which is what makes the {@code defaults} block able to set any
 * key a client can set, without a second, drifting schema.
 *
 * <p><strong>Every scalar is a wrapper type and no scalar has an initializer.</strong> That is
 * load-bearing, not an oversight: {@link ClientPropertiesMerger} needs to distinguish "this client
 * did not mention read-timeout" from "this client set read-timeout to the same value the built-in
 * default happens to have", and a primitive {@code int} cannot express the difference. The built-in
 * values are applied by the merger's base layer, which is also the only place they are written down.
 */
@Getter
@Setter
public class ClientProperties {

    /**
     * Base URL every relative path is resolved against, e.g. {@code https://billing.internal/api/v1}.
     *
     * <p>Required for a client; meaningless in the {@code defaults} block, where a shared base URL
     * would be wrong for every client but one. Setting it there is a startup error.
     */
    private String baseUrl;

    /** {@code sync} (default) or {@code async}. Requires a restart to change. */
    private ClientMode mode;

    /**
     * {@code http-client} (default), {@code apache}, or {@code reactor-netty}.
     *
     * <p>Ignored - and forced to {@code reactor-netty} - when {@code mode} is {@code async}. Requires
     * a restart to change.
     */
    private TransportEngine transport;

    /** TCP connect timeout. Built-in default: 2s. */
    private Duration connectTimeout;

    /**
     * Socket read timeout: the longest gap tolerated between bytes. Built-in default: 10s.
     *
     * <p>Not a deadline for the whole call - a peer that trickles one byte every nine seconds never
     * trips it. {@code request-timeout} is the deadline.
     */
    private Duration readTimeout;

    /** How long a caller waits for a connection from the pool. Built-in default: 2s. */
    private Duration connectionRequestTimeout;

    /**
     * Deadline for one attempt, connect and read included. Built-in default: unset.
     *
     * <p>The {@code apache} transport enforces it as its response timeout and Reactor Netty as its
     * response timeout; the JDK client enforces it as {@code HttpRequest.timeout()}. It is the
     * setting that actually bounds an attempt, and the one most deployments forget.
     */
    private Duration requestTimeout;

    /** {@code NORMAL} (default), {@code NEVER} or {@code ALWAYS}. */
    private RedirectPolicy redirects;

    /** Offer and transparently decode gzip/deflate. Built-in default: true. */
    private Boolean compression;

    /**
     * Negotiate HTTP/2 where the peer offers it. Built-in default: false.
     *
     * <p>Off by default even though every engine here can do it: HTTP/2 multiplexes, so a pool sized
     * for HTTP/1.1 concurrency suddenly means something entirely different, and a middlebox that
     * mishandles it fails in ways that look like packet loss. Turn it on per client, deliberately.
     */
    private Boolean http2;

    /**
     * {@code User-Agent} sent by this client. Built-in default:
     * {@code <spring.application.name>/<version> (ludwig-rest-client; client=<name>)}.
     *
     * <p>Identifying the caller by service <em>and</em> by named client is what lets a partner - or
     * this platform's own gateway - attribute traffic to a dependency rather than to a host.
     */
    private String userAgent;

    /**
     * Headers added to every request unless the call sets them itself.
     *
     * <p>Merged key by key with the {@code defaults} block: a client adding one header keeps the
     * ones defaults declared, and a client redeclaring a key overrides just that key. Maps are the
     * one structure the merger merges rather than replaces, because "the platform's headers plus
     * mine" is the only thing anyone ever means here.
     */
    private Map<String, String> defaultHeaders = new LinkedHashMap<>();

    /** Query parameters appended to every request. Merged key by key, like the headers. */
    private Map<String, List<String>> defaultQueryParams = new LinkedHashMap<>();

    @Valid
    @NestedConfigurationProperty
    private PoolProperties pool = new PoolProperties();

    @Valid
    @NestedConfigurationProperty
    private TlsProperties tls = new TlsProperties();

    @Valid
    @NestedConfigurationProperty
    private ProxyProperties proxy = new ProxyProperties();

    @Valid
    @NestedConfigurationProperty
    private AuthProperties auth = new AuthProperties();

    @Valid
    @NestedConfigurationProperty
    private ResilienceProperties resilience = new ResilienceProperties();

    @Valid
    @NestedConfigurationProperty
    private LoggingProperties logging = new LoggingProperties();

    @Valid
    @NestedConfigurationProperty
    private AuditProperties audit = new AuditProperties();

    @Valid
    @NestedConfigurationProperty
    private SerializationProperties serialization = new SerializationProperties();
}
