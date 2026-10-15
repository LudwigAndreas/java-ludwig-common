package ru.ludwigandreas.grpc.client.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.grpc.client.properties.ClientSettings;
import ru.ludwigandreas.grpc.client.properties.GrpcClientPropertiesMerger;

/**
 * One test per merge rule.
 */
class GrpcClientPropertiesMergerTest {

    private static ClientSettings breaker(Float threshold, Integer window) {
        ClientSettings.CircuitBreaker breaker = new ClientSettings.CircuitBreaker();
        breaker.setFailureRateThreshold(threshold);
        breaker.setSlidingWindowSize(window);
        ClientSettings.Resilience resilience = new ClientSettings.Resilience();
        resilience.setCircuitBreaker(breaker);
        ClientSettings settings = new ClientSettings();
        settings.setResilience(resilience);
        return settings;
    }

    @Test
    @DisplayName("nothing written: the built-in values, deadline 5s and round_robin")
    void builtIn() {
        ClientSettings resolved = GrpcClientPropertiesMerger.resolve(null, new ClientSettings());

        assertThat(resolved.getDeadline()).isEqualTo(Duration.ofSeconds(5));
        assertThat(resolved.getLoadBalancing()).isEqualTo("round_robin");
        assertThat(resolved.getAuth().getType()).isEqualTo("none");
        assertThat(resolved.getResilience().getCircuitBreaker().getEnabled()).isTrue();
    }

    @Test
    @DisplayName("a scalar the client sets overrides defaults; one it does not set is inherited")
    void scalars() {
        ClientSettings defaults = new ClientSettings();
        defaults.setDeadline(Duration.ofSeconds(2));
        defaults.setLoadBalancing("pick_first");
        ClientSettings client = new ClientSettings();
        client.setDeadline(Duration.ofSeconds(3));

        ClientSettings resolved = GrpcClientPropertiesMerger.resolve(defaults, client);

        assertThat(resolved.getDeadline()).isEqualTo(Duration.ofSeconds(3));
        assertThat(resolved.getLoadBalancing()).isEqualTo("pick_first");
    }

    @Test
    @DisplayName("a nested block merges field by field: the client's window, the inherited threshold")
    void nestedBlocks() {
        ClientSettings resolved = GrpcClientPropertiesMerger.resolve(breaker(30f, 50), breaker(null, 10));

        assertThat(resolved.getResilience().getCircuitBreaker().getSlidingWindowSize()).isEqualTo(10);
        assertThat(resolved.getResilience().getCircuitBreaker().getFailureRateThreshold()).isEqualTo(30f);
        assertThat(resolved.getResilience().getCircuitBreaker().getMinimumNumberOfCalls()).isEqualTo(20);
    }

    @Test
    @DisplayName("a list replaces, it does not append")
    void listsReplace() {
        ClientSettings defaults = new ClientSettings();
        defaults.setRetry(new ClientSettings.Retry());
        defaults.getRetry().setRetryOn(List.of("UNAVAILABLE", "RESOURCE_EXHAUSTED"));
        ClientSettings client = new ClientSettings();
        client.setRetry(new ClientSettings.Retry());
        client.getRetry().setRetryOn(List.of("INTERNAL"));

        assertThat(GrpcClientPropertiesMerger.resolve(defaults, client).getRetry().getRetryOn())
                .containsExactly("INTERNAL");
    }

    @Test
    @DisplayName("target is never inherited from defaults")
    void targetNotInherited() {
        ClientSettings defaults = new ClientSettings();
        defaults.setTarget("dns:///shared:9090");

        assertThat(GrpcClientPropertiesMerger.resolve(defaults, new ClientSettings()).getTarget()).isNull();
    }

    @Test
    @DisplayName("auth.relay-enabled is never inherited from defaults")
    void relayNotInherited() {
        ClientSettings defaults = new ClientSettings();
        defaults.setAuth(new ClientSettings.Auth());
        defaults.getAuth().setRelayEnabled(true);
        defaults.getAuth().setType("oauth2-token-relay");

        ClientSettings resolved = GrpcClientPropertiesMerger.resolve(defaults, new ClientSettings());

        assertThat(resolved.getAuth().getRelayEnabled()).isNull();
        assertThat(resolved.getAuth().getType()).isEqualTo("oauth2-token-relay");
    }

    @Test
    @DisplayName("bulkhead and rate limiter are off until a block is written, in defaults or the client")
    void declaredBlockEnables() {
        assertThat(GrpcClientPropertiesMerger.resolve(null, new ClientSettings()).getResilience().getBulkhead())
                .isNull();

        ClientSettings defaults = new ClientSettings();
        defaults.setResilience(new ClientSettings.Resilience());
        defaults.getResilience().setBulkhead(new ClientSettings.Bulkhead());
        defaults.getResilience().getBulkhead().setMaxConcurrentCalls(5);

        assertThat(GrpcClientPropertiesMerger.resolve(defaults, new ClientSettings()).getResilience().getBulkhead()
                .getMaxConcurrentCalls()).isEqualTo(5);
    }

    @Test
    @DisplayName("neither argument is modified")
    void inputsUntouched() {
        ClientSettings defaults = breaker(30f, 50);
        ClientSettings client = breaker(null, 10);

        GrpcClientPropertiesMerger.resolve(defaults, client);

        assertThat(client.getResilience().getCircuitBreaker().getFailureRateThreshold()).isNull();
        assertThat(defaults.getDeadline()).isNull();
    }
}
