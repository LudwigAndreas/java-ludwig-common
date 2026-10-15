package ru.ludwigandreas.grpc.client.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import ru.ludwigandreas.grpc.client.channel.GrpcChannelFactory;
import ru.ludwigandreas.grpc.client.error.GrpcClientConfigurationException;
import ru.ludwigandreas.grpc.client.error.GrpcClientMessages;
import ru.ludwigandreas.grpc.client.properties.ClientSettings;
import ru.ludwigandreas.grpc.client.properties.GrpcClientProperties;
import ru.ludwigandreas.grpc.client.properties.GrpcClientPropertiesMerger;

/**
 * Refuses a client configuration that is valid key by key and wrong as a whole, reporting every
 * problem in every client at once - fixing them one restart at a time is the cost this avoids.
 */
@Slf4j
public class GrpcClientConfigurationValidator {

    /** The built-in authentication types. */
    public static final Set<String> BUILT_IN_AUTH =
            Set.of("none", "bearer", "oauth2-client-credentials", "oauth2-token-relay");

    private static final String ALLOW_PLAINTEXT = "ludwig.grpc.allow-plaintext";
    private static final String PRODUCTION_PROFILES = "ludwig.grpc.production-profiles";
    private static final List<String> DEFAULT_PRODUCTION_PROFILES = List.of("prod", "production");
    private static final float MAX_RATE = 100f;
    private static final String SCHEME_SEPARATOR = "://";

    private final Environment environment;

    /**
     * Creates the validator.
     *
     * @param environment where the shared plaintext and production keys are read from
     */
    public GrpcClientConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    /**
     * Validates every client.
     *
     * @param properties        the bound properties
     * @param providedAuthTypes auth types published by provider beans
     * @param security          the security integration, or {@code null}
     * @param clientCredentials whether machine tokens can be minted
     * @param sslBundles        Spring Boot's SSL bundles, or {@code null}
     * @throws GrpcClientConfigurationException listing every problem
     */
    public void validate(GrpcClientProperties properties, Set<String> providedAuthTypes,
                         GrpcClientIntegrations.Security security, boolean clientCredentials, SslBundles sslBundles) {
        List<String> problems = new ArrayList<>();
        List<String> production = productionProfiles();
        if (properties.getDefaults() != null && StringUtils.hasText(properties.getDefaults().getTarget())) {
            problems.add(GrpcClientMessages.startup("target-in-defaults"));
        }
        for (Map.Entry<String, ClientSettings> client : properties.getClients().entrySet()) {
            String name = client.getKey();
            ClientSettings settings = GrpcClientPropertiesMerger.resolve(properties.getDefaults(), client.getValue());
            target(problems, name, settings, production);
            Duration deadline = settings.getDeadline();
            if (deadline.isZero() || deadline.isNegative()) {
                problems.add(GrpcClientMessages.startup("deadline-not-positive", name, deadline));
            }
            auth(problems, name, settings.getAuth(), providedAuthTypes, security, clientCredentials);
            breaker(problems, name, settings.getResilience().getCircuitBreaker());
            if ("payload".equals(settings.getLogging().getLevel()) && !production.isEmpty()) {
                problems.add(GrpcClientMessages.startup("payload-in-production", name, production));
            }
            String bundle = settings.getTls().getBundle();
            if (StringUtils.hasText(bundle) && !bundleExists(sslBundles, bundle)) {
                problems.add(GrpcClientMessages.startup("tls-bundle-unknown", name, bundle));
            }
            if ("pick_first".equals(settings.getLoadBalancing())) {
                log.warn(GrpcClientMessages.startup("pick-first", name));
            }
        }
        if (!problems.isEmpty()) {
            StringBuilder listed = new StringBuilder();
            problems.forEach(problem -> listed.append(System.lineSeparator()).append("  - ").append(problem));
            throw new GrpcClientConfigurationException(
                    GrpcClientMessages.startup("invalid", Integer.toString(problems.size()), listed));
        }
    }

    private void target(List<String> problems, String name, ClientSettings settings, List<String> production) {
        String target = settings.getTarget();
        if (!StringUtils.hasText(target)) {
            problems.add(GrpcClientMessages.startup("target-missing", name));
            return;
        }
        if (target.startsWith(GrpcChannelFactory.IN_PROCESS) || !Boolean.TRUE.equals(settings.getPlaintext())) {
            return;
        }
        boolean loopback = loopback(target);
        if (!production.isEmpty()) {
            problems.add(GrpcClientMessages.startup("plaintext-in-production", name, production));
        } else if (!loopback && !environment.getProperty(ALLOW_PLAINTEXT, Boolean.class, Boolean.FALSE)) {
            problems.add(GrpcClientMessages.startup("plaintext-switches", name, target));
        }
        String type = settings.getAuth().getType();
        if (!loopback && !"none".equals(type)) {
            problems.add(GrpcClientMessages.startup("credential-over-plaintext", name, type, target));
        }
    }

    private static void auth(List<String> problems, String name, ClientSettings.Auth auth, Set<String> provided,
                             GrpcClientIntegrations.Security security, boolean clientCredentials) {
        String type = auth.getType();
        switch (type) {
            case "none" -> {
            }
            case "bearer" -> {
                if (!StringUtils.hasText(auth.getToken())) {
                    problems.add(GrpcClientMessages.startup("bearer-without-token", name));
                }
            }
            case "oauth2-client-credentials" -> {
                if (!StringUtils.hasText(auth.getRegistrationId())) {
                    problems.add(GrpcClientMessages.startup("client-credentials-without-registration", name));
                } else if (!clientCredentials) {
                    problems.add(GrpcClientMessages.startup("client-credentials-unavailable", name));
                }
            }
            case "oauth2-token-relay" -> {
                if (!Boolean.TRUE.equals(auth.getRelayEnabled())) {
                    problems.add(GrpcClientMessages.startup("relay-not-enabled", name));
                }
                if (security == null || !security.resourceServer()) {
                    problems.add(GrpcClientMessages.startup("relay-without-resource-server", name));
                }
            }
            default -> {
                if (!provided.contains(type)) {
                    List<String> known = new ArrayList<>(BUILT_IN_AUTH);
                    known.addAll(provided);
                    problems.add(GrpcClientMessages.startup("auth-type-unknown", name, type,
                            known.stream().sorted().toList()));
                }
            }
        }
    }

    private static void breaker(List<String> problems, String name, ClientSettings.CircuitBreaker breaker) {
        if (!Boolean.TRUE.equals(breaker.getEnabled())) {
            return;
        }
        if (breaker.getMinimumNumberOfCalls() > breaker.getSlidingWindowSize()) {
            problems.add(GrpcClientMessages.startup("breaker-cannot-open", name,
                    Integer.toString(breaker.getMinimumNumberOfCalls()),
                    Integer.toString(breaker.getSlidingWindowSize())));
        }
        float threshold = breaker.getFailureRateThreshold();
        if (threshold <= 0f || threshold > MAX_RATE) {
            problems.add(GrpcClientMessages.startup("breaker-threshold", name, Float.toString(threshold)));
        }
    }

    private List<String> productionProfiles() {
        List<String> production = Binder.get(environment)
                .bind(PRODUCTION_PROFILES, Bindable.listOf(String.class))
                .orElse(DEFAULT_PRODUCTION_PROFILES);
        return List.of(environment.getActiveProfiles()).stream().filter(production::contains).toList();
    }

    private static boolean bundleExists(SslBundles bundles, String bundle) {
        try {
            return bundles != null && bundles.getBundle(bundle) != null;
        } catch (NoSuchSslBundleException unknown) {
            return false;
        }
    }

    /**
     * Whether a target's host is a loopback address. {@code dns:///host:port}, {@code dns://auth/host:port},
     * {@code host:port} and {@code [::1]:port} are understood; anything unresolvable is not loopback.
     */
    static boolean loopback(String target) {
        String authority = target;
        int scheme = authority.indexOf(SCHEME_SEPARATOR);
        if (scheme >= 0) {
            authority = authority.substring(scheme + SCHEME_SEPARATOR.length());
            authority = authority.substring(authority.indexOf('/') + 1);
        }
        String host = authority.startsWith("[")
                ? authority.substring(1, Math.max(1, authority.indexOf(']')))
                : authority.contains(":") ? authority.substring(0, authority.lastIndexOf(':')) : authority;
        try {
            return !host.isEmpty() && InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException unresolvable) {
            return false;
        }
    }
}
