package ru.ludwigandreas.reconciliation.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.config.EnumerationSettingsResolver;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.engine.enumeration.EnumerationRegistry;
import ru.ludwigandreas.reconciliation.engine.enumeration.RegisteredEnumeration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The registry is a lookup by name, in declaration order, and nothing else. */
class EnumerationRegistryTest {

    @Test
    void enumerationsAreFoundByNameAndListedInDeclarationOrder() {
        EnumerationRegistry registry = new EnumerationRegistry(List.of(registered("second"), registered("first")));

        assertThat(registry.names()).containsExactly("second", "first");
        assertThat(registry.find("first")).map(RegisteredEnumeration::name).contains("first");
        assertThat(registry.find("missing")).isEmpty();
        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void anEmptyRegistryIsAServiceWithNoEnumerations() {
        EnumerationRegistry registry = new EnumerationRegistry(List.of());

        assertThat(registry.all()).isEmpty();
        assertThat(registry.names()).isEmpty();
    }

    private static RegisteredEnumeration<?, ?, ?, ?> registered(String name) {
        return new RegisteredEnumeration<>(new StubEnumeration(name, false), EnumerationSettingsResolver.resolve(
                name, new ReconciliationProperties.Enumeration(), new ReconciliationProperties.EnumerationDefaults()));
    }
}
