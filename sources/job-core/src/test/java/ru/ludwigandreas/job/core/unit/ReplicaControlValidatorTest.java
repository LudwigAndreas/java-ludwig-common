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
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.security.admin.AdminOperationContributor;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

class ReplicaControlValidatorTest {

    private static final AdminOperationCatalog CATALOG = new AdminOperationCatalog(List.<AdminOperationContributor>of(
            () -> List.of(AdminOperation.of("caches.read", "k"), AdminOperation.of("caches.operate", "k"),
                    AdminOperation.of("caches.privileged", "k"))));

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
            public AdminOperation operationFor(Object arguments) {
                return AdminOperation.of(operation, "k");
            }

            @Override
            public Set<AdminOperation> possibleOperations() {
                return Set.of(AdminOperation.of(operation, "k"));
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
        assertThat(ReplicaControlValidator.index(List.of(control("cacheevict", "caches.operate")), CATALOG))
                .containsOnlyKeys("cacheevict");
    }

    @Test
    @DisplayName("a duplicate name, an uncatalogued operation and a malformed name all fail startup, each named")
    void refuses() {
        assertThatThrownBy(() -> ReplicaControlValidator.index(List.of(control("cacheevict", "caches.operate"),
                control("cacheevict", "caches.operate"), control("tokens", "tokens.operate"),
                control("Cache-Evict", "caches.operate")), CATALOG))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("two controls are named 'cacheevict'")
                .hasMessageContaining("tokens.operate")
                .hasMessageContaining("'Cache-Evict' is not a lower-case word");
    }
}
