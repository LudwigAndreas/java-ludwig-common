package ru.ludwigandreas.restclient.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * The whole of {@code ludwig.rest-client}.
 *
 * <p>The shape is two blocks and a map: what every client inherits, and the clients themselves.
 *
 * <pre>{@code
 * ludwig:
 *   rest-client:
 *     defaults:
 *       connect-timeout: 2s
 *       read-timeout: 10s
 *     clients:
 *       billing:
 *         base-url: https://billing.internal/api/v1
 *         read-timeout: 120s
 * }</pre>
 *
 * <p>Inheritance is a deep merge performed by {@link ClientPropertiesMerger} over three layers -
 * built-in defaults, the {@code defaults} block, then the client's own - and is implemented
 * explicitly rather than left to relaxed binding, which has no notion of one prefix inheriting from
 * another and would simply have produced a client with nine unset fields.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "ludwig.rest-client")
public class RestClientProperties {

    /** The prefix this class binds, quoted in every error message the module produces. */
    public static final String PREFIX = "ludwig.rest-client";

    /**
     * Master switch. {@code false} registers nothing at all: no clients, no interface proxies, no
     * meters, no validator.
     *
     * <p>It exists so that a service can keep the dependency and turn the feature off - in a test
     * slice, in a migration, in a profile that must not reach the network - without the "turn it
     * off" path being "delete the beans and hope nothing autowired them".
     */
    private boolean enabled = true;

    @Valid
    @NestedConfigurationProperty
    private ClientProperties defaults = new ClientProperties();

    /**
     * The named clients. The key is the client name: it is the injection qualifier, the value of
     * {@code @LudwigRestClient}, the {@code client} tag on every metric, and the name in every log
     * line and audit record.
     *
     * <p>A {@code LinkedHashMap}, so iteration order is stable for the lifetime of the context and
     * a startup log lists the same clients in the same order on every boot. It is not necessarily
     * <em>declaration</em> order: the binder discovers keys through the property sources, and a map
     * assembled from several of them - a YAML file, an environment variable, a command-line
     * override - has no single declaration order to preserve.
     */
    @Valid
    @NestedConfigurationProperty
    private Map<String, ClientProperties> clients = new LinkedHashMap<>();

    /**
     * Permits {@code tls.trust-all: true} and {@code tls.hostname-verification: false} to take
     * effect.
     *
     * <p>The second of the two switches those settings need. A single switch on the client itself is
     * one line in one YAML file, and YAML files get copied from a developer's laptop into a Helm
     * chart; requiring a second, differently-named, top-level switch means the copy does not work,
     * and somebody has to notice why.
     */
    private boolean allowTrustAll;

    /**
     * Profiles in which {@link #isAllowTrustAll()} is refused outright. Built-in: prod, production.
     *
     * <p>Belt and braces: even with both switches set, a context whose active profiles intersect this
     * list fails to start. The cost of being wrong in the other direction - a staging environment
     * that happens to be called {@code production} - is a clear startup error, which is cheap.
     */
    private List<String> productionProfiles = List.of("prod", "production");

    @Valid
    @NestedConfigurationProperty
    private Metrics metrics = new Metrics();

    /** Metric emission, shared by every client. */
    @Getter
    @Setter
    public static class Metrics {

        /** Built-in default: true. */
        private boolean enabled = true;

        /**
         * Cap on distinct {@code uri} tag values for {@code ludwig.restclient.requests}. Built-in
         * default: 100.
         *
         * <p>The tag is the URI <em>template</em>, so in normal operation the cardinality is the
         * number of endpoints this service calls and the cap never binds. It binds when a template
         * could not be determined - an ad-hoc {@code RestClient} call built from a concatenated
         * string - and then the expanded path, chosen by whatever data the service is processing,
         * would grow the registry without bound. The overflow folds into {@code OTHER}; the
         * implementation is observability-spring-boot-starter's {@code
         * UriCardinalityLimitingMeterFilter}, reused rather than rewritten.
         */
        @Positive
        private int maxUriTags = 100;

        /**
         * Publish connection-pool gauges (leased, pending, available, max). Built-in default: true.
         *
         * <p>Only the {@code apache} and {@code reactor-netty} engines can report them; the JDK
         * client exposes no pool statistics, and no gauge is published for it rather than a
         * plausible-looking zero.
         */
        private boolean pool = true;
    }
}
