package ru.ludwigandreas.archrules;

import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.ludwigandreas.archrules.config.ArchitectureRulesProperties;
import ru.ludwigandreas.archrules.report.ColorMode;
import ru.ludwigandreas.archrules.rules.ModuleBoundaryRules;
import ru.ludwigandreas.archrules.rules.PersistenceRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Configuration is validated where it is written, not where a rule later misbehaves. */
class ConfigurationTest {

    @Test
    void basePackagesAreRequired() {
        assertThatThrownBy(() -> ArchitectureRulesConfiguration.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("At least one base package is required");
    }

    @Test
    void aPackageIdentifierIsNotABasePackage() {
        assertThatThrownBy(() -> ArchitectureRulesConfiguration.builder().basePackages("com.acme.."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("plain package name");
    }

    @Test
    void aCustomizedModuleMustLieInsideTheAnalysedPackages() {
        assertThatThrownBy(() -> ArchitectureRulesConfiguration.builder()
                .basePackages("com.acme.orders")
                .customizeModules(ModuleRuleCustomization.forModules("com.other.billing").build())
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lies outside the analysed base packages");
    }

    @Test
    void aModuleIsCustomizedOnlyOnce() {
        assertThatThrownBy(() -> ArchitectureRulesConfiguration.builder()
                .basePackages("com.acme")
                .customizeModules(ModuleRuleCustomization.forModules("com.acme.orders").disable("kafka").build())
                .customizeModules(ModuleRuleCustomization.forModules("com.acme.orders").disable("web").build())
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("customized more than once");
    }

    @Test
    void propertiesConfigureEverythingTheBuilderDoes() {
        Properties properties = new Properties();
        properties.setProperty("architecture.rules.base-packages", "com.acme.orders, com.acme.shared");
        properties.setProperty("architecture.rules.modules", "com.acme.orders.catalog");
        properties.setProperty("architecture.rules.rules.kafka", "false");
        properties.setProperty("architecture.rules.rules.domain-isolation", "true");
        properties.setProperty("architecture.rules.conventions.packages.controller", "..api.web..");
        properties.setProperty("architecture.rules.conventions.packages.entity.add", "..jpa..");
        properties.setProperty("architecture.rules.conventions.annotations.persistent-type.add", "com.acme.Aggregate");
        properties.setProperty("architecture.rules.module.legacy.packages", "com.acme.orders.legacy");
        properties.setProperty("architecture.rules.module.legacy.rules.layering", "false");
        properties.setProperty("architecture.rules.freeze", "false");

        ArchitectureRulesConfiguration configuration = ArchitectureRulesProperties
                .applyTo(properties, ArchitectureRulesConfiguration.builder())
                .build();

        assertThat(configuration.basePackages()).containsExactly("com.acme.orders", "com.acme.shared");
        assertThat(configuration.modulePackages()).containsExactly("com.acme.orders.catalog");
        assertThat(configuration.conventions().packages(PackageRole.CONTROLLER)).containsExactly("..api.web..");
        assertThat(configuration.conventions().packages(PackageRole.ENTITY))
                .contains("..entity..", "..jpa..");
        assertThat(configuration.conventions().annotations(AnnotationRole.PERSISTENT_TYPE))
                .contains("jakarta.persistence.Entity", "com.acme.Aggregate");
        assertThat(configuration.selection().overrides())
                .containsEntry("kafka", false)
                .containsEntry("domain-isolation", true);
        assertThat(configuration.moduleCustomizations()).singleElement()
                .satisfies(customization -> assertThat(customization.modulePackages())
                        .containsExactly("com.acme.orders.legacy"));
    }

    @Test
    @DisplayName("severity, reporting and free-form settings are configurable from the properties file")
    void propertiesConfigureSeverityAndReporting() {
        Properties properties = new Properties();
        properties.setProperty("architecture.rules.base-packages", "com.acme.orders");
        properties.setProperty("architecture.rules.service-name", "orders-service");
        properties.setProperty("architecture.rules.severity.modules", "warning");
        properties.setProperty("architecture.rules.report.console", "false");
        properties.setProperty("architecture.rules.report.color", "never");
        properties.setProperty("architecture.rules.report.json-file", "target/custom-report.json");
        properties.setProperty("architecture.rules.report.max-violations-per-rule", "12");
        properties.setProperty("architecture.rules.conventions.settings.rest-base-path-pattern", "/svc/v[0-9]+/.*");
        properties.setProperty("architecture.rules.conventions.types.base-entity", "com.acme.BaseEntity");

        ArchitectureRulesConfiguration configuration = ArchitectureRulesProperties
                .applyTo(properties, ArchitectureRulesConfiguration.builder())
                .build();

        assertThat(configuration.serviceName()).isEqualTo("orders-service");
        assertThat(configuration.severities().severityOf(ArchitectureRule.of(
                ModuleBoundaryRules.INTERNALS_ARE_PRIVATE, new NoOpArchRule())))
                .isEqualTo(RuleSeverity.WARNING);
        assertThat(configuration.reporting().console()).isFalse();
        assertThat(configuration.reporting().color()).isEqualTo(ColorMode.NEVER);
        assertThat(configuration.reporting().jsonFile()).isEqualTo(Path.of("target/custom-report.json"));
        assertThat(configuration.reporting().maxViolationsPerRule()).isEqualTo(12);
        assertThat(configuration.conventions().setting(ConventionSetting.REST_BASE_PATH_PATTERN))
                .contains("/svc/v[0-9]+/.*");
        assertThat(configuration.conventions().types(TypeRole.BASE_ENTITY)).containsExactly("com.acme.BaseEntity");
    }

    @Test
    void anInvalidSeverityIsRejected() {
        Properties properties = new Properties();
        properties.setProperty("architecture.rules.severity.web", "meh");

        assertThatThrownBy(() -> ArchitectureRulesProperties.applyTo(properties,
                ArchitectureRulesConfiguration.builder()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be one of error, warning");
    }

    @Test
    void anUnknownPropertyIsNotSilentlyIgnored() {
        Properties properties = new Properties();
        properties.setProperty("architecture.rules.base-package", "com.acme");

        assertThatThrownBy(() -> ArchitectureRulesProperties.applyTo(properties,
                ArchitectureRulesConfiguration.builder()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown architecture rules property");
    }

    @Test
    void aSelectorNamingNoExistingRuleLeavesTheRealRuleEnabled() {
        Properties properties = new Properties();
        // 'entities-in-entity-package' is not the id; the real one is 'entities-reside-in-entity-packages'
        properties.setProperty("architecture.rules.rules.persistence.entities-in-entity-package", "false");

        RuleSelection selection = ArchitectureRulesProperties
                .applyTo(properties, ArchitectureRulesConfiguration.builder().basePackages("com.acme"))
                .build()
                .selection();

        // The group prefix is validated, the rule name cannot be: a rule id only exists once its rule
        // set has built it. This is why the ids are published as constants and listed in the README.
        assertThat(selection.isEnabled(ArchitectureRule.of(PersistenceRules.ENTITIES_IN_ENTITY_PACKAGES,
                new NoOpArchRule()))).isTrue();
    }
}
