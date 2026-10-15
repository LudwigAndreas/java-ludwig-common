package ru.ludwigandreas.grpc.server.server;

import ru.ludwigandreas.grpc.server.error.GrpcServerConfigurationException;
import ru.ludwigandreas.grpc.server.error.GrpcServerMessages;
import ru.ludwigandreas.grpc.server.properties.GrpcServerProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Refuses a server configuration that would be unsafe to serve, and reports every problem at once.
 *
 * <p>Run when the server is about to bind, not when the context starts: an application with the
 * starter and no gRPC service binds nothing, so it has nothing to get wrong.
 *
 * <p>{@code ludwig.grpc.allow-plaintext} and {@code ludwig.grpc.production-profiles} are read
 * straight from the environment because they belong to neither starter: the client starter obeys
 * the same two keys, and a property class in either module would make the other depend on it.
 */
@Slf4j
public class GrpcServerConfigurationValidator {

    /**
     * The second plaintext switch, shared with the client starter.
     */
    public static final String ALLOW_PLAINTEXT = "ludwig.grpc.allow-plaintext";

    /**
     * The profiles under which plaintext and reflection are refused, shared with the client starter.
     */
    public static final String PRODUCTION_PROFILES = "ludwig.grpc.production-profiles";

    private static final List<String> DEFAULT_PRODUCTION_PROFILES = List.of("prod", "production");

    private final GrpcServerProperties properties;
    private final Environment environment;
    private final ObjectProvider<SslBundles> sslBundles;

    /**
     * Creates the validator.
     *
     * @param properties  the server configuration
     * @param environment where the two shared keys and the active profiles are read from
     * @param sslBundles  Spring Boot's SSL bundles, when there are any
     */
    public GrpcServerConfigurationValidator(GrpcServerProperties properties, Environment environment,
                                            ObjectProvider<SslBundles> sslBundles) {
        this.properties = properties;
        this.environment = environment;
        this.sslBundles = sslBundles;
    }

    /**
     * Validates the configuration of a server that is about to bind.
     *
     * @param authenticationAvailable whether there is a way to authenticate a caller
     * @throws GrpcServerConfigurationException listing every problem found
     */
    public void validate(boolean authenticationAvailable) {
        List<String> problems = new ArrayList<>();
        List<String> production = activeProductionProfiles();

        GrpcServerProperties.Security security = properties.getSecurity();
        if (!security.isEnabled()) {
            log.warn(GrpcServerMessages.startup("unauthenticated"));
        } else if (!authenticationAvailable) {
            problems.add(GrpcServerMessages.startup("no-authentication"));
        } else {
            security.getPermitUnauthenticated()
                    .forEach(method -> log.warn(GrpcServerMessages.startup("permit-unauthenticated", method)));
        }
        if (properties.getReflection().isEnabled() && !production.isEmpty()) {
            problems.add(GrpcServerMessages.startup("reflection-in-production", production));
        }
        if (!StringUtils.hasText(properties.getInProcessName())) {
            validateTransport(problems, production);
        }
        if (!problems.isEmpty()) {
            StringBuilder listed = new StringBuilder();
            problems.forEach(problem -> listed.append(System.lineSeparator()).append("  - ").append(problem));
            throw new GrpcServerConfigurationException(
                    GrpcServerMessages.startup("invalid", Integer.toString(problems.size()), listed));
        }
    }

    private void validateTransport(List<String> problems, List<String> production) {
        String bundle = properties.getTls().getBundle();
        if (StringUtils.hasText(bundle)) {
            SslBundles bundles = sslBundles.getIfAvailable();
            try {
                if (bundles == null) {
                    throw new NoSuchSslBundleException(bundle, "no SSL bundles are configured");
                }
                bundles.getBundle(bundle);
            } catch (NoSuchSslBundleException unknown) {
                problems.add(GrpcServerMessages.startup("tls-bundle-unknown", bundle));
            }
            return;
        }
        // No bundle means the server would speak plaintext, whether or not anyone asked for it.
        if (!production.isEmpty()) {
            problems.add(GrpcServerMessages.startup("plaintext-in-production", production));
        } else if (!loopback(properties.getAddress()) && !(properties.isPlaintext() && allowPlaintext())) {
            String address = StringUtils.hasText(properties.getAddress()) ? properties.getAddress() : "*";
            problems.add(GrpcServerMessages.startup("plaintext-switches", address));
        }
    }

    private boolean allowPlaintext() {
        return environment.getProperty(ALLOW_PLAINTEXT, Boolean.class, Boolean.FALSE);
    }

    private List<String> activeProductionProfiles() {
        List<String> production = Binder.get(environment)
                .bind(PRODUCTION_PROFILES, Bindable.listOf(String.class))
                .orElse(DEFAULT_PRODUCTION_PROFILES);
        return List.of(environment.getActiveProfiles()).stream().filter(production::contains).toList();
    }

    private static boolean loopback(String address) {
        if (!StringUtils.hasText(address)) {
            // Unset binds every interface, which is as far from loopback as an address gets.
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException unresolvable) {
            return false;
        }
    }
}
