package ru.ludwigandreas.restclient.admin.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ru.ludwigandreas.job.core.control.ReplicaControl;
import ru.ludwigandreas.restclient.admin.RestClientAdminOperations;
import ru.ludwigandreas.restclient.resilience.ResilienceRegistries;
import ru.ludwigandreas.security.operation.Operation;

/**
 * The {@code restclientbreakers} control: every circuit breaker's state and failure rate, from each replica. A
 * control rather than a listing because breaker state is per replica - the one replica still {@code OPEN} is
 * exactly what an operator is looking for. Changes nothing.
 */
public class RestClientBreakersControl implements ReplicaControl<RestClientBreakersControl.Arguments> {

    /** The control's name. */
    public static final String NAME = "restclientbreakers";

    private final ResilienceRegistries registries;

    /** No arguments: every breaker is read. */
    public record Arguments() {
    }

    /**
     * Creates the control.
     *
     * @param registries where the breakers live
     */
    public RestClientBreakersControl(ResilienceRegistries registries) {
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
        return RestClientAdminOperations.BREAKER_READ_OPERATION;
    }

    @Override
    public Set<Operation> possibleOperations() {
        return Set.of(RestClientAdminOperations.BREAKER_READ_OPERATION);
    }

    @Override
    public Optional<Object> apply(Arguments arguments) {
        // A failure rate of -1 is Resilience4j's "fewer calls than the minimum yet".
        List<Map<String, Object>> breakers = registries.circuitBreakers().getAllCircuitBreakers().stream()
                .sorted(Comparator.comparing(breaker -> breaker.getName()))
                .<Map<String, Object>>map(breaker -> Map.of("name", breaker.getName(),
                        "state", breaker.getState().name(), "failureRate", breaker.getMetrics().getFailureRate()))
                .toList();
        return Optional.of(Map.of("breakers", breakers));
    }
}
