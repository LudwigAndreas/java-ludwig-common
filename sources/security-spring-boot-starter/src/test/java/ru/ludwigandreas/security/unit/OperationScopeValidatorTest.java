package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.querydsl.core.types.dsl.Expressions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.data.DataAction;
import ru.ludwigandreas.security.data.DataScopeMapping;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationCatalog;
import ru.ludwigandreas.security.operation.OperationGrants;
import ru.ludwigandreas.security.operation.OperationScopeValidator;
import ru.ludwigandreas.security.operation.Sensitivity;

class OperationScopeValidatorTest {

    private record Product(String createdBy) {
    }

    private static final OperationCatalog CATALOG = new OperationCatalog(List.of(() -> List.of(
            Operation.business("catalog.product.update", Sensitivity.CHANGE, "k").on("product", DataAction.WRITE),
            Operation.business("catalog.price.publish", Sensitivity.CHANGE, "k"),
            Operation.admin("cache.performance.evict", Sensitivity.CHANGE, "k"),
            Operation.admin("reconciliation.run.start", Sensitivity.CHANGE, "k"))));

    private static final DataScopeRegistry REGISTRY = new DataScopeRegistry(List.of(
            DataScopeMapping.forResource("product", Product.class)
                    .owner(Expressions.stringPath("createdBy"), Product::createdBy).build()), Set.of());

    private static void validate(String operation, String scope, DataScopeRegistry registry) {
        OperationGrants grants = OperationGrants.compile(
                Map.of("R", OperationGrantsTest.role(Map.of(operation, scope))), CATALOG);
        new OperationScopeValidator(grants, registry).afterPropertiesSet();
    }

    @Test
    @DisplayName("a dimension the resource binds, OBJECT on an administrative operation, and ALL anywhere are accepted")
    void accepts() {
        assertThatCode(() -> validate("catalog.product.update", "OWN", REGISTRY)).doesNotThrowAnyException();
        assertThatCode(() -> validate("reconciliation.run.start", "OBJECT", REGISTRY)).doesNotThrowAnyException();
        assertThatCode(() -> validate("cache.performance.evict", "ALL", null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a dimension the resource does not bind is refused, naming role, operation and dimension")
    void unboundDimension() {
        assertThatThrownBy(() -> validate("catalog.product.update", "TENANT", REGISTRY))
                .hasMessageContaining("ROLE_R").hasMessageContaining("catalog.product.update")
                .hasMessageContaining("tenant");
    }

    @Test
    @DisplayName("an operation without rows admits only ALL, and OBJECT only on the administrative surface")
    void noRows() {
        assertThatThrownBy(() -> validate("cache.performance.evict", "OWN", REGISTRY))
                .hasMessageContaining("touches no rows");
        assertThatThrownBy(() -> validate("catalog.price.publish", "OBJECT", REGISTRY))
                .hasMessageContaining("not administrative");
    }

    @Test
    @DisplayName("with row scoping off no dimension can be enforced")
    void rowScopingOff() {
        assertThatThrownBy(() -> validate("catalog.product.update", "OWN", null))
                .hasMessageContaining("row scoping is off");
    }
}
