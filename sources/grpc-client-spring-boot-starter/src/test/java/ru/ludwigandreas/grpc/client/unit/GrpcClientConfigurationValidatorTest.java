package ru.ludwigandreas.grpc.client.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ru.ludwigandreas.grpc.client.config.GrpcClientConfigurationValidator;
import ru.ludwigandreas.grpc.client.config.GrpcClientIntegrations;
import ru.ludwigandreas.grpc.client.error.GrpcClientConfigurationException;
import ru.ludwigandreas.grpc.client.properties.ClientSettings;
import ru.ludwigandreas.grpc.client.properties.GrpcClientProperties;

/**
 * Every refusal, and that independent mistakes are reported together.
 */
class GrpcClientConfigurationValidatorTest {

    private static final GrpcClientIntegrations.Security RESOURCE_SERVER =
            new GrpcClientIntegrations.Security(() -> null, () -> null, true);

    private final MockEnvironment environment = new MockEnvironment();
    private final GrpcClientProperties properties = new GrpcClientProperties();

    private ClientSettings client(String name, String target) {
        ClientSettings settings = new ClientSettings();
        settings.setTarget(target);
        properties.getClients().put(name, settings);
        return settings;
    }

    private static ClientSettings.Auth auth(ClientSettings settings, String type) {
        settings.setAuth(new ClientSettings.Auth());
        settings.getAuth().setType(type);
        return settings.getAuth();
    }

    private void validate() {
        new GrpcClientConfigurationValidator(environment).validate(properties, Set.of(), RESOURCE_SERVER, true, null);
    }

    @Test
    @DisplayName("three independent mistakes produce one failure listing all three")
    void everyProblemAtOnce() {
        client("no-target", null);
        ClientSettings breaker = client("breaker", "dns:///b:9090");
        breaker.setResilience(new ClientSettings.Resilience());
        breaker.getResilience().setCircuitBreaker(new ClientSettings.CircuitBreaker());
        breaker.getResilience().getCircuitBreaker().setMinimumNumberOfCalls(50);
        breaker.getResilience().getCircuitBreaker().setSlidingWindowSize(10);
        auth(client("auth", "dns:///c:9090"), "kerberos");

        assertThatThrownBy(this::validate).isInstanceOf(GrpcClientConfigurationException.class)
                .hasMessageContaining("3 problem(s)")
                .hasMessageContaining("\"no-target\" has no target")
                .hasMessageContaining("\"breaker\": the circuit breaker")
                .hasMessageContaining("\"kerberos\"");
    }

    @Test
    @DisplayName("a target in defaults is refused: it cannot be shared")
    void targetInDefaults() {
        properties.getDefaults().setTarget("dns:///shared:9090");

        assertThatThrownBy(this::validate).hasMessageContaining("defaults.target");
    }

    @Test
    @DisplayName("a deadline of zero is refused, naming the client")
    void zeroDeadline() {
        client("slow", "dns:///s:9090").setDeadline(Duration.ZERO);

        assertThatThrownBy(this::validate).hasMessageContaining("\"slow\" has deadline");
    }

    @Test
    @DisplayName("a credential to a non-loopback plaintext target is refused, naming the client")
    void credentialOverPlaintext() {
        environment.setProperty("ludwig.grpc.allow-plaintext", "true");
        ClientSettings settings = client("leaky", "dns:///peer:9090");
        settings.setPlaintext(true);
        auth(settings, "bearer").setToken("t");

        assertThatThrownBy(this::validate).hasMessageContaining("\"leaky\" would send a bearer credential");
    }

    @Test
    @DisplayName("plaintext needs both switches off loopback, and is fine on loopback")
    void plaintextSwitches() {
        client("remote", "dns:///peer:9090").setPlaintext(true);
        assertThatThrownBy(this::validate).hasMessageContaining("ludwig.grpc.allow-plaintext");

        properties.getClients().clear();
        client("local", "localhost:9090").setPlaintext(true);
        assertThatCode(this::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("plaintext is refused under a production profile, loopback included")
    void plaintextInProduction() {
        environment.setActiveProfiles("prod");
        client("local", "127.0.0.1:9090").setPlaintext(true);

        assertThatThrownBy(this::validate).hasMessageContaining("production profile");
    }

    @Test
    @DisplayName("relay enabled only in defaults: the client does not relay, so its relay type is refused")
    void relayFromDefaults() {
        properties.getDefaults().setAuth(new ClientSettings.Auth());
        properties.getDefaults().getAuth().setRelayEnabled(true);
        auth(client("relay", "dns:///r:9090"), "oauth2-token-relay");

        assertThatThrownBy(this::validate).hasMessageContaining("relay-enabled=true");
    }

    @Test
    @DisplayName("relay in a service that is not a resource server is refused")
    void relayWithoutResourceServer() {
        auth(client("relay", "dns:///r:9090"), "oauth2-token-relay").setRelayEnabled(true);

        assertThatThrownBy(() -> new GrpcClientConfigurationValidator(environment).validate(properties, Set.of(),
                new GrpcClientIntegrations.Security(() -> null, () -> null, false), true, null))
                .hasMessageContaining("not a resource server");
    }

    @Test
    @DisplayName("payload logging is refused under a production profile, naming the client")
    void payloadInProduction() {
        environment.setActiveProfiles("production");
        ClientSettings settings = client("chatty", "dns:///c:9090");
        settings.setLogging(new ClientSettings.Logging());
        settings.getLogging().setLevel("payload");

        assertThatThrownBy(this::validate).hasMessageContaining("\"chatty\" sets logging.level=payload");
    }

    @Test
    @DisplayName("a provider-defined auth type is accepted when a provider publishes it")
    void providedAuthType() {
        auth(client("custom", "dns:///c:9090"), "kerberos");

        assertThatCode(() -> new GrpcClientConfigurationValidator(environment)
                .validate(properties, Set.of("kerberos"), RESOURCE_SERVER, true, null)).doesNotThrowAnyException();
    }
}
