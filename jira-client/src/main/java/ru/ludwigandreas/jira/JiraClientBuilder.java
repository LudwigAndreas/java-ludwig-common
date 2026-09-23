package ru.ludwigandreas.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import ru.ludwigandreas.jira.auth.JiraCredentials;
import ru.ludwigandreas.jira.auth.BasicAuthCredentials;
import ru.ludwigandreas.jira.auth.PersonalAccessTokenCredentials;
import ru.ludwigandreas.jira.field.CustomFieldRegistry;
import ru.ludwigandreas.jira.http.JdkJiraTransport;
import ru.ludwigandreas.jira.http.JiraClientMetrics;
import ru.ludwigandreas.jira.http.JiraInterceptor;
import ru.ludwigandreas.jira.http.JiraTransport;
import ru.ludwigandreas.jira.http.RetryPolicy;
import ru.ludwigandreas.jira.json.JiraJson;

/**
 * Builds a {@link JiraClient}.
 *
 * <p>Two things are required and everything else has a working default:
 *
 * <pre>{@code
 * JiraClient client = JiraClient.builder()
 *         .baseUrl("https://jira.example.com")
 *         .personalAccessToken(token)
 *         .build();
 * }</pre>
 *
 * <p>What the builder validates before it returns, rather than leaving for the first request to discover:
 *
 * <ul>
 *   <li>The base URL parses, is absolute, and is {@code http} or {@code https}. A base URL of
 *       {@code jira.example.com} - no scheme - otherwise produces a {@code URISyntaxException} from
 *       somewhere deep inside the first call, hours after deployment.</li>
 *   <li>The base URL is normalized to end in a slash. Without it, {@code URI.resolve} discards the last
 *       path segment, so an instance served from a context path
 *       ({@code https://example.com/jira}) silently has its requests sent to {@code https://example.com/rest/...}
 *       and every one of them 404s.</li>
 *   <li>Credentials were chosen. There is no implicit anonymous default: an anonymous client against a
 *       permissioned Jira gets 404s rather than 401s, which reads as "wrong issue keys" rather than as
 *       "not authenticated". {@link #anonymous()} says so explicitly when that is what is wanted.</li>
 * </ul>
 *
 * <p>{@link #verifyOnBuild(boolean)} extends that to the things only the server can answer - that it is
 * reachable, that the credentials work, that it is the Jira version expected. It is off by default because
 * a constructor that makes a network call surprises people, and worth turning on in any long-lived service,
 * where failing at startup beats failing at 3am on the first request.
 */
public final class JiraClientBuilder {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(60);

    private URI baseUrl;
    private JiraCredentials credentials;
    private JiraTransport transport;
    private JiraJson json;
    private RetryPolicy retryPolicy = RetryPolicy.defaults();
    private JiraClientMetrics metrics = JiraClientMetrics.noop();
    private CustomFieldRegistry customFieldRegistry;
    private final List<JiraInterceptor> interceptors = new ArrayList<>();
    private final Map<String, String> defaultHeaders = new LinkedHashMap<>();
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private Duration responseTimeout = DEFAULT_RESPONSE_TIMEOUT;
    private SSLContext sslContext;
    private ProxySelector proxySelector;
    private String userAgent = "ludwig-jira-client";
    private boolean verifyOnBuild;
    private boolean loadCustomFields;

    JiraClientBuilder() {
    }

    /**
     * The Jira base URL, with any context path but without {@code /rest}.
     *
     * @param baseUrl for example {@code https://jira.example.com} or {@code https://example.com/jira}
     * @return this builder
     */
    public JiraClientBuilder baseUrl(String baseUrl) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        try {
            return baseUrl(new URI(baseUrl.trim()));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Jira base URL is not a valid URI: '" + baseUrl + "'", e);
        }
    }

    /** The Jira base URL. */
    public JiraClientBuilder baseUrl(URI baseUrl) {
        this.baseUrl = normalize(Objects.requireNonNull(baseUrl, "baseUrl"));
        return this;
    }

    /** Authenticates with a Jira Server personal access token - the recommended scheme on 9.12. */
    public JiraClientBuilder personalAccessToken(String token) {
        return credentials(PersonalAccessTokenCredentials.of(token));
    }

    /** Authenticates with a username and password over HTTP Basic. */
    public JiraClientBuilder basicAuth(String username, String password) {
        return credentials(BasicAuthCredentials.of(username, password));
    }

    /** Sends no credentials at all. Say so explicitly rather than leaving credentials unset. */
    public JiraClientBuilder anonymous() {
        return credentials(JiraCredentials.anonymous());
    }

    /** Authenticates with a caller-supplied scheme. */
    public JiraClientBuilder credentials(JiraCredentials credentials) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        return this;
    }

    /**
     * Replaces the HTTP stack. When set, the timeout, proxy and TLS settings on this builder no longer
     * apply - they configure the default transport, which this replaces.
     *
     * @param transport the transport to use
     * @return this builder
     */
    public JiraClientBuilder transport(JiraTransport transport) {
        this.transport = transport;
        return this;
    }

    /** How long to wait for a connection and TLS handshake. */
    public JiraClientBuilder connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    /** How long to wait for a complete response. */
    public JiraClientBuilder responseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
        return this;
    }

    /** Trust material for an instance behind a private certificate authority. */
    public JiraClientBuilder sslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    /** An explicit proxy; pass {@code ProxySelector.getDefault()} to honour the JVM's proxy properties. */
    public JiraClientBuilder proxySelector(ProxySelector proxySelector) {
        this.proxySelector = proxySelector;
        return this;
    }

    /** Replaces the retry policy. */
    public JiraClientBuilder retryPolicy(RetryPolicy retryPolicy) {
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        return this;
    }

    /**
     * Publishes call, retry and rate-limit signals somewhere.
     *
     * <p>Takes the client's own interface rather than a Micrometer registry, so that this class carries no
     * reference to Micrometer and the dependency can stay optional. For Micrometer, pass
     * {@code new MicrometerJiraClientMetrics(registry)}.
     *
     * @param metrics the sink
     * @return this builder
     */
    public JiraClientBuilder metrics(JiraClientMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        return this;
    }

    /** Appends an interceptor; the first one added is the outermost. */
    public JiraClientBuilder addInterceptor(JiraInterceptor interceptor) {
        interceptors.add(Objects.requireNonNull(interceptor, "interceptor"));
        return this;
    }

    /** Adds a header sent on every request - a tenant id, a change-management ticket reference. */
    public JiraClientBuilder defaultHeader(String name, String value) {
        defaultHeaders.put(name, value);
        return this;
    }

    /** Replaces the {@code User-Agent}. Worth setting to the calling service's name: Jira logs it. */
    public JiraClientBuilder userAgent(String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    /**
     * Replaces the JSON mapper.
     *
     * <p>Start from {@link JiraJson#defaultObjectMapper()} and adjust it. A mapper that fails on unknown
     * properties, or that omits Jira's timestamp module, breaks this client on the first issue it reads -
     * see {@link JiraJson} for what each setting is load-bearing for.
     *
     * @param objectMapper the mapper
     * @return this builder
     */
    public JiraClientBuilder objectMapper(ObjectMapper objectMapper) {
        this.json = new JiraJson(Objects.requireNonNull(objectMapper, "objectMapper"));
        return this;
    }

    /**
     * Supplies a pre-built custom field registry, for a service that caches one across restarts or shares
     * one between clients.
     *
     * @param registry the registry
     * @return this builder
     */
    public JiraClientBuilder customFieldRegistry(CustomFieldRegistry registry) {
        this.customFieldRegistry = registry;
        return this;
    }

    /**
     * Loads the instance's field definitions during {@link #build()} rather than on first use.
     *
     * <p>Turns a mistyped custom field name into a startup failure instead of a null six hours into a batch
     * run. Costs one request and a few hundred kilobytes.
     *
     * @param loadCustomFields whether to load eagerly
     * @return this builder
     */
    public JiraClientBuilder loadCustomFields(boolean loadCustomFields) {
        this.loadCustomFields = loadCustomFields;
        return this;
    }

    /**
     * Calls the instance during {@link #build()} to confirm it is reachable, that the credentials work, and
     * that it is a Jira Server of at least the targeted build.
     *
     * @param verifyOnBuild whether to verify
     * @return this builder
     */
    public JiraClientBuilder verifyOnBuild(boolean verifyOnBuild) {
        this.verifyOnBuild = verifyOnBuild;
        return this;
    }

    /**
     * Builds the client.
     *
     * @return a configured client; close it when the owning component shuts down
     * @throws IllegalStateException when the base URL or the credentials were not set
     */
    public JiraClient build() {
        if (baseUrl == null) {
            throw new IllegalStateException("A Jira base URL is required, for example "
                    + "baseUrl(\"https://jira.example.com\")");
        }
        if (credentials == null) {
            throw new IllegalStateException("Credentials are required. Use personalAccessToken(...) on Jira "
                    + "8.14 and newer, basicAuth(...) on older instances, or anonymous() to say explicitly "
                    + "that no credentials should be sent");
        }
        JiraTransport effectiveTransport = transport != null ? transport : JdkJiraTransport.builder()
                .connectTimeout(connectTimeout)
                .responseTimeout(responseTimeout)
                .sslContext(sslContext)
                .proxySelector(proxySelector)
                .build();
        JiraJson effectiveJson = json != null ? json : JiraJson.createDefault();
        Map<String, String> headers = new LinkedHashMap<>(defaultHeaders);
        headers.putIfAbsent("User-Agent", userAgent);
        JiraRestClient rest = new JiraRestClient(
                baseUrl, credentials, effectiveTransport, effectiveJson, retryPolicy,
                List.copyOf(interceptors), metrics, headers);
        JiraClient client = new JiraClient(rest, credentials, customFieldRegistry);
        if (verifyOnBuild) {
            client.verifyConnection();
        }
        if (loadCustomFields) {
            client.customFields();
        }
        return client;
    }

    private static URI normalize(URI raw) {
        if (raw.getScheme() == null || raw.getHost() == null) {
            throw new IllegalArgumentException("Jira base URL must be absolute and include a scheme and host, "
                    + "for example https://jira.example.com - got '" + raw + "'");
        }
        String scheme = raw.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Jira base URL must be http or https, got '" + scheme + "'");
        }
        String text = raw.toString();
        return text.endsWith("/") ? raw : URI.create(text + "/");
    }
}
