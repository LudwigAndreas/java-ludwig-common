package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingDefinitionSource;
import ru.ludwigandreas.usersettings.api.SettingValidator;
import ru.ludwigandreas.usersettings.exception.SettingViolation;
import ru.ludwigandreas.usersettings.convert.SettingValueConverterRegistry;
import ru.ludwigandreas.usersettings.exception.SettingConfigurationException;
import ru.ludwigandreas.usersettings.exception.UnknownSettingException;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;

/**
 * The startup checks. Each of these is a deploy that must fail rather than a user who finds out.
 */
class SettingDefinitionRegistryTest {

    private static SettingDefinitionRegistry registryOf(SettingDefinitionSource... sources) {
        return new SettingDefinitionRegistry(List.of(sources),
                new SettingValueConverterRegistry(List.of(), new ObjectMapper()));
    }

    @Test
    @DisplayName("accepts a well-formed set of definitions")
    void accepts_valid_definitions() {
        SettingDefinitionRegistry registry = registryOf(TestSettings.source());

        assertThat(registry.definitions()).hasSize(5);
        assertThat(registry.find("user.timezone")).contains(TestSettings.TIMEZONE);
        assertThat(registry.converterFor(TestSettings.TIMEZONE).typeId()).isEqualTo("zone-id");
    }

    @Test
    @DisplayName("rejects two declarations of the same key that differ")
    void rejects_conflicting_duplicate_keys() {
        SettingDefinition<ZoneId> other = SettingDefinition
                .of("user.timezone", ZoneId.class)
                .defaultValue(ZoneId.of("Europe/Moscow"))
                .category("locale")
                .userEditable(true)
                .build();

        assertThatThrownBy(() -> registryOf(TestSettings.source(), SettingDefinitionSource.of(other)))
                .isInstanceOf(SettingConfigurationException.class)
                .hasMessageContaining("user.timezone")
                .hasMessageContaining("declared twice");
    }

    @Test
    @DisplayName("accepts the same declaration contributed by two sources")
    void accepts_identical_duplicate_declarations() {
        // A service that builds definitions from a factory - one opt-out per notification category -
        // legitimately produces two equal instances, and must not have to deduplicate them itself.
        SettingDefinition<ZoneId> same = SettingDefinition
                .of("user.timezone", ZoneId.class)
                .defaultValue(ZoneId.of("UTC"))
                .category("locale")
                .userEditable(true)
                .build();

        assertThatCode(() -> registryOf(TestSettings.source(), SettingDefinitionSource.of(same)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a default that fails the definition's own validation")
    void rejects_default_failing_its_own_validation() {
        SettingDefinition<Integer> broken = SettingDefinition
                .of("ui.broken-default", Integer.class)
                .defaultValue(500)
                .validator(SettingValidator.range(1, 100))
                .build();

        assertThatThrownBy(() -> registryOf(SettingDefinitionSource.of(broken)))
                .isInstanceOf(SettingConfigurationException.class)
                .hasMessageContaining("ui.broken-default")
                .hasMessageContaining("fails its own validation");
    }

    @Test
    @DisplayName("rejects a type nothing can convert")
    void rejects_unconvertible_type() {
        SettingDefinition<Thread> broken = SettingDefinition
                .of("ui.unconvertible", Thread.class)
                .build();

        assertThatThrownBy(() -> registryOf(SettingDefinitionSource.of(broken)))
                .isInstanceOf(SettingConfigurationException.class)
                .hasMessageContaining("ui.unconvertible")
                .hasMessageContaining("no converter handles");
    }

    @Test
    @DisplayName("rejects a default that does not survive a storage round trip")
    void rejects_default_that_does_not_round_trip() {
        // A converter that loses information: the default would silently change the first time
        // anybody saved the setting, which is the worst kind of "works on my machine".
        SettingDefinition<String> lossy = SettingDefinition
                .of("ui.lossy", String.class)
                .defaultValue("Hello")
                .converter(new ru.ludwigandreas.usersettings.api.FunctionalSettingValueConverter<>(
                        String.class, "string", value -> value, raw -> raw.toLowerCase(java.util.Locale.ROOT)))
                .build();

        assertThatThrownBy(() -> registryOf(SettingDefinitionSource.of(lossy)))
                .isInstanceOf(SettingConfigurationException.class)
                .hasMessageContaining("does not survive a round trip");
    }

    @Test
    @DisplayName("a definition nobody registered fails loudly at first reference")
    void unregistered_definition_fails_at_first_reference() {
        // The one check that cannot move to startup: nothing handed this definition to the registry,
        // so there was nothing to validate. It must not resolve silently to a default.
        SettingDefinitionRegistry registry = registryOf(TestSettings.source());
        SettingDefinition<String> neverRegistered = SettingDefinition
                .of("ui.never-registered", String.class)
                .build();

        assertThatThrownBy(() -> registry.require(neverRegistered))
                .isInstanceOf(UnknownSettingException.class);
        assertThatThrownBy(() -> registry.require("ui.never-registered"))
                .isInstanceOf(UnknownSettingException.class);
    }

    @Test
    @DisplayName("a definition whose declaration differs from the registered one is rejected at use")
    void mismatched_declaration_is_rejected_at_use() {
        SettingDefinitionRegistry registry = registryOf(TestSettings.source());
        SettingDefinition<ZoneId> impostor = SettingDefinition
                .of("user.timezone", ZoneId.class)
                .defaultValue(ZoneId.of("Europe/Moscow"))
                .build();

        assertThatThrownBy(() -> registry.require(impostor))
                .isInstanceOf(SettingConfigurationException.class)
                .hasMessageContaining("must be the one that was registered");
    }

    @Test
    @DisplayName("a validator's violation carries its code and arguments")
    void validator_reports_code_and_arguments() {
        SettingViolation violation = SettingValidator.range(1, 100).validate(500).orElseThrow();

        assertThat(violation.code()).isEqualTo("ludwig.user-settings.validation.range");
        assertThat(violation.args()).containsExactly(1, 100);
    }
}
