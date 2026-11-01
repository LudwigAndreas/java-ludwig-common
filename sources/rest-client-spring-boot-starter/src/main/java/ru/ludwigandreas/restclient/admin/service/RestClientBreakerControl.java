package ru.ludwigandreas.restclient.admin.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ru.ludwigandreas.job.core.control.ReplicaControl;
import ru.ludwigandreas.restclient.admin.RestClientAdminOperations;
import ru.ludwigandreas.restclient.resilience.ResilienceRegistries;
import ru.ludwigandreas.security.operation.Operation;

/**
 * The {@code restclientbreaker} control: sets one client's circuit breaker on every replica. Privileged, because
 * forcing it open takes a partner out of service - calls through the client fail without contacting it - and a reason
 * is required. The state lives in memory: a restart returns every breaker to {@code CLOSED}. Idempotent: setting the
 * state a breaker is in does nothing.
 */
public class RestClientBreakerControl implements ReplicaControl<RestClientBreakerControl.Arguments> {

    /** The control's name. */
    public static final String NAME = "restclientbreaker";

    private final RestClientAdminService clients;
    private final ResilienceRegistries registries;

    /** The states an operator may set. */
    public enum State {
        /** Normal operation; failures are counted again from zero. */
        CLOSED,
        /** Every call is refused without contacting the partner, until set otherwise or restarted. */
        FORCED_OPEN,
        /** Every call goes through and nothing is counted. */
        DISABLED
    }

    /**
     * The breaker to set.
     *
     * @param client the client it protects
     * @param state  the state to set
     */
    public record Arguments(@NotBlank String client, @NotNull State state) {
    }

    /**
     * Creates the control.
     *
     * @param clients    the named clients
     * @param registries where the breakers live
     */
    public RestClientBreakerControl(RestClientAdminService clients, ResilienceRegistries registries) {
        this.clients = clients;
        this.registries = registries;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Class<Arguments> argumentType() {
        return Arguments.class;
    }

    @Override
    public Operation operationFor(Arguments arguments) {
        if (!Boolean.TRUE.equals(clients.configured(arguments.client()).getResilience().getCircuitBreaker()
                .getEnabled())) {
            throw new IllegalArgumentException("REST client '" + arguments.client() + "' has no circuit breaker");
        }
        return RestClientAdminOperations.BREAKER_SET_OPERATION;
    }

    @Override
    public Set<Operation> possibleOperations() {
        return Set.of(RestClientAdminOperations.BREAKER_SET_OPERATION);
    }

    @Override
    public Optional<Object> apply(Arguments arguments) {
        // A replica that has not called through the client yet has no breaker; building the client registers it.
        clients.ensureBuilt(arguments.client());
        CircuitBreaker breaker = registries.circuitBreakers().find(arguments.client()).orElseThrow(
                () -> new IllegalStateException("REST client '" + arguments.client() + "' built no circuit breaker"));
        switch (arguments.state()) {
            case CLOSED -> breaker.transitionToClosedState();
            case FORCED_OPEN -> breaker.transitionToForcedOpenState();
            case DISABLED -> breaker.transitionToDisabledState();
            default -> throw new IllegalArgumentException("unsupported state " + arguments.state());
        }
        return Optional.of(Map.of("client", arguments.client(), "state", breaker.getState().name()));
    }
}
