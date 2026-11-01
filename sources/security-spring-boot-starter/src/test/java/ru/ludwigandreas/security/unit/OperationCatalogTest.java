package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.data.DataAction;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationCatalog;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;
import ru.ludwigandreas.security.operation.Surface;

class OperationCatalogTest {

    /** A small area of every sensitivity, the shape most of these tests need. */
    static OperationContributor outbox() {
        return () -> List.of(
                Operation.admin("outbox.message.read", Sensitivity.READ, "k.r"),
                Operation.admin("outbox.message.retry", Sensitivity.CHANGE, "k.c"),
                Operation.admin("outbox.message.discard", Sensitivity.PRIVILEGED, "k.p"));
    }

    @Test
    @DisplayName("an operation carries its area, sensitivity, surface and, when it names one, its resource")
    void declares() {
        Operation operation = Operation.business("catalog.product.update", Sensitivity.CHANGE, "k")
                .on("product", DataAction.WRITE);

        assertThat(operation.area()).isEqualTo("catalog");
        assertThat(operation.surface()).isEqualTo(Surface.BUSINESS);
        assertThat(operation.touchesRows()).isTrue();
        assertThat(operation.scopeResource()).isEqualTo("product");
        assertThat(Operation.admin("cache.performance.evict", Sensitivity.CHANGE, "k").scopeAction())
                .isEqualTo(Operation.INVOKE);
    }

    @Test
    @DisplayName("an id outside <area>.<object>.<verb> is refused, naming the id")
    void refusesMalformedIds() {
        for (String id : List.of("outbox.discard", "Outbox.Message.Discard", "outbox.message.discard.now",
                "outbox..discard", "1outbox.message.discard", "outbox-x.message.discard")) {
            assertThatThrownBy(() -> Operation.admin(id, Sensitivity.READ, "k")).as(id)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(id);
        }
    }

    @Test
    @DisplayName("a READ operation reads, and only a READ operation does")
    void sensitivityMatchesTheAction() {
        assertThatThrownBy(() -> Operation.business("catalog.product.read", Sensitivity.READ, "k")
                .on("product", DataAction.WRITE)).hasMessageContaining("catalog.product.read");
        assertThatThrownBy(() -> Operation.business("catalog.product.update", Sensitivity.CHANGE, "k")
                .on("product", DataAction.READ)).hasMessageContaining("catalog.product.update");
    }

    @Test
    @DisplayName("the catalog is the union of every contributor, ordered by id")
    void collectsTheUnion() {
        OperationCatalog catalog = new OperationCatalog(List.of(outbox(),
                () -> List.of(Operation.business("catalog.product.read", Sensitivity.READ, "k"))));

        assertThat(catalog.all()).extracting(Operation::id).containsExactly("catalog.product.read",
                "outbox.message.discard", "outbox.message.read", "outbox.message.retry");
        assertThat(catalog.idsOf("outbox")).hasSize(3);
    }

    @Test
    @DisplayName("two contributors declaring the same id fail startup, naming the id and both contributors")
    void refusesDuplicates() {
        assertThatThrownBy(() -> new OperationCatalog(List.of(outbox(), outbox())))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("outbox.message.read").hasMessageContaining("declared twice");
    }

    @Test
    @DisplayName("a service whose modules declare nothing has an empty catalog")
    void emptyIsValid() {
        assertThat(new OperationCatalog(List.of()).all()).isEmpty();
    }
}
