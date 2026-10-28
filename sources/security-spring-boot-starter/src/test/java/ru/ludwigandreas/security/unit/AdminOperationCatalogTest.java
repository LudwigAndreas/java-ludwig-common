package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.security.admin.AdminOperationContributor;
import ru.ludwigandreas.security.admin.AdminTier;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

class AdminOperationCatalogTest {

    static AdminOperationContributor area(String area) {
        return () -> List.of(
                AdminOperation.of(area + ".read", "test." + area + ".read"),
                AdminOperation.of(area + ".operate", "test." + area + ".operate"),
                AdminOperation.of(area + ".privileged", "test." + area + ".privileged"));
    }

    @Test
    @DisplayName("an id is parsed into its area and tier")
    void parsesTheId() {
        AdminOperation operation = AdminOperation.of("outbox.operate", "key");

        assertThat(operation.area()).isEqualTo("outbox");
        assertThat(operation.tier()).isEqualTo(AdminTier.OPERATE);
    }

    @Test
    @DisplayName("an id outside <area>.<tier> is refused, naming the id")
    void refusesMalformedIds() {
        for (String id : List.of("outbox", "outbox.write", "Outbox.read", "out-box.read", "outbox.read.x", ".read")) {
            assertThatThrownBy(() -> AdminOperation.of(id, "key"))
                    .as(id)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("'" + id + "'");
        }
    }

    @Test
    @DisplayName("the catalog is the union of every contributor")
    void collectsTheUnion() {
        AdminOperationCatalog catalog = new AdminOperationCatalog(List.of(area("outbox"), area("jobs")));

        assertThat(catalog.all()).extracting(AdminOperation::id).containsExactly(
                "jobs.operate", "jobs.privileged", "jobs.read",
                "outbox.operate", "outbox.privileged", "outbox.read");
        assertThat(catalog.contains("outbox.read")).isTrue();
        assertThat(catalog.find("outbox.write")).isEmpty();
        assertThat(catalog.idsOf("outbox")).containsExactly("outbox.operate", "outbox.privileged", "outbox.read");
    }

    @Test
    @DisplayName("two contributors declaring the same id fail startup, naming the id and both contributors")
    void refusesDuplicates() {
        AdminOperationContributor first = area("outbox");
        AdminOperationContributor second = area("outbox");

        assertThatThrownBy(() -> new AdminOperationCatalog(List.of(first, second)))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("'outbox.")
                .hasMessageContaining(first.getClass().getName())
                .hasMessageContaining(second.getClass().getName());
    }

    @Test
    @DisplayName("an area missing a tier fails startup, naming the area and the missing tier")
    void requiresEveryTier() {
        AdminOperationContributor partial = () -> List.of(
                AdminOperation.of("messaging.read", "k1"),
                AdminOperation.of("messaging.operate", "k2"));

        assertThatThrownBy(() -> new AdminOperationCatalog(List.of(partial)))
                .isInstanceOf(SecurityConfigurationException.class)
                .hasMessageContaining("'messaging'")
                .hasMessageContaining("messaging.privileged");
    }

    @Test
    @DisplayName("a service with no administrative module has an empty catalog")
    void emptyIsValid() {
        assertThat(new AdminOperationCatalog(List.of()).all()).isEmpty();
    }
}
