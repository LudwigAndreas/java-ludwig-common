package ru.ludwigandreas.job.core.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.job.core.control.ReplicaControl;
import ru.ludwigandreas.job.core.control.ReplicaControlValidator;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationCatalog;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

class ReplicaControlValidatorTest {

    private static final OperationCatalog CATALOG = new OperationCatalog(List.<OperationContributor>of(
            () -> List.of(Operation.admin("cache.definition.read", Sensitivity.READ, "k"),
                    Operation.admin("cache.performance.evict", Sensitivity.CHANGE, "k"))));

    private static ReplicaControl<Object> control(String name, String operation) {
        return new ReplicaControl<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Class<Object> argumentType() {
                return Object.class;
            }

            @Override
            public Operation operationFor(Object arguments) {
                return Operation.admin(operation, Sensitivity.CHANGE, "k");
            }

            @Override
            public Set<Operation> possibleOperations() {
                return Set.of(Operation.admin(operation, Sensitivity.CHANGE, "k"));
            }

            @Override
            public Optional<Object> apply(Object arguments) {
                return Optional.empty();
            }
        };
    }

    @Test
    @DisplayName("valid controls are indexed by name")
    void indexes() {
        assertThat(ReplicaControlValidator.index(List.of(control("cacheevict", "cache.performance.evict")), CATALOG))
                .containsOnlyKeys("cacheevict");
    }

    @Test
    @DisplayName("a duplicate name, an uncatalogued operation and a malformed name all fail startup, each named")
    void refuses() {
        assertThatThrownBy(() -> ReplicaControlValidator.index(List.of(control("cacheevict", "cache.performance.evict"),
                control("cacheevict", "cache.performance.evict"), control("tokens", "tokens.token.reset"),
                control("Cache-Evict", "cache.performance.evict")), CATALOG))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("two controls are named 'cacheevict'")
                .hasMessageContaining("tokens.token.reset")
                .hasMessageContaining("'Cache-Evict' is not a lower-case word");
    }
}
