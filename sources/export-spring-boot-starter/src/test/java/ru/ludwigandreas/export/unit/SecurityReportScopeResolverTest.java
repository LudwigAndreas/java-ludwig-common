package ru.ludwigandreas.export.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.export.security.SecurityReportScopeResolver;
import ru.ludwigandreas.security.data.DataAction;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationAuthority;
import ru.ludwigandreas.security.operation.OperationCatalog;
import ru.ludwigandreas.security.operation.OperationGrants;
import ru.ludwigandreas.security.operation.Sensitivity;

class SecurityReportScopeResolverTest {

    private static final OperationCatalog CATALOG = new OperationCatalog(List.of(() -> List.of(
            Operation.business("catalog.order.read", Sensitivity.READ, "k").on("order", DataAction.READ),
            Operation.business("catalog.order.update", Sensitivity.CHANGE, "k").on("order", DataAction.WRITE),
            Operation.business("catalog.price.read", Sensitivity.READ, "k"))));

    private final SecurityReportScopeResolver resolver = new SecurityReportScopeResolver(
            new OperationAuthority(OperationGrants.compile(Map.of(), CATALOG), () -> null),
            new DataScopeRegistry(List.of(), Set.of()), null);

    @Test
    @DisplayName("a report scoped by a catalogued read over a resource, or not scoped at all, starts")
    void acceptsReadOperations() {
        assertThatCode(() -> resolver.validate(List.of(TestReports.orders().scopedBy("catalog.order.read").build(),
                TestReports.orders().build()))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a report scoped by an uncatalogued, a change, or a resource-less operation fails startup")
    void refusesOtherOperations() {
        for (String operation : List.of("catalog.order.reed", "catalog.order.update", "catalog.price.read")) {
            assertThatThrownBy(() -> resolver.validate(List.of(TestReports.orders().scopedBy(operation).build())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("catalog.orders")
                    .hasMessageContaining(operation);
        }
    }
}
