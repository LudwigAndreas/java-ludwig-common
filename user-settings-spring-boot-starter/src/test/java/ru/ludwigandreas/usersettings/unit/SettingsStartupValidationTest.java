package ru.ludwigandreas.usersettings.unit;

import ru.ludwigandreas.audit.config.AuditCoreAutoConfiguration;
import ru.ludwigandreas.security.config.SecurityAuditAutoConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingDefinitionSource;
import ru.ludwigandreas.usersettings.api.SettingValidator;
import ru.ludwigandreas.usersettings.config.UserSettingsAutoConfiguration;
import ru.ludwigandreas.usersettings.exception.SettingConfigurationException;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;

/**
 * A broken definition has to stop the <em>context</em>, not just fail a unit test of the registry.
 *
 * <p>The distinction matters because it is the whole claim: a deploy carrying a setting whose default
 * fails its own validation must not reach the point of serving traffic. Asserting that against the
 * registry in isolation would keep passing if the registry were ever built lazily, or behind a
 * condition, or after the first request - which is exactly how a startup check quietly becomes a
 * runtime one.
 */
class SettingsStartupValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    UserSettingsAutoConfiguration.class,
                    // The change trail now goes through the platform sink, so the slice needs the audit
                    // wiring - it brings the SLF4J sink alone, no database and no scheduler. And the
                    // security starter's audit autoconfiguration with it: audit-core's own two actor
                    // resolvers deliberately step aside when LudwigPrincipal is on the classpath, because
                    // only PrincipalActorResolver can read a subject out of one. A real service always has
                    // it - it is ungated and in the imports file - but a hand-listed slice has to say so.
                    AuditCoreAutoConfiguration.class,
                    SecurityAuditAutoConfiguration.class))
            .withPropertyValues(
                    "ludwig.user-settings.owner.enabled=true",
                    "spring.datasource.url=jdbc:h2:mem:settings-startup;DB_CLOSE_DELAY=-1",
                    "spring.jpa.hibernate.ddl-auto=none");

    @Test
    @DisplayName("a default that fails its own validation stops the context")
    void broken_default_fails_startup() {
        contextRunner
                .withUserConfiguration(BrokenDefaultConfiguration.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(SettingConfigurationException.class)
                        .hasMessageContaining("ui.broken-default")
                        .hasMessageContaining("fails its own validation"));
    }

    @Test
    @DisplayName("a type nothing can convert stops the context")
    void unconvertible_type_fails_startup() {
        contextRunner
                .withUserConfiguration(UnconvertibleTypeConfiguration.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(SettingConfigurationException.class)
                        .hasMessageContaining("no converter handles"));
    }

    @Test
    @DisplayName("two sources declaring one key differently stop the context")
    void conflicting_duplicate_key_fails_startup() {
        contextRunner
                .withUserConfiguration(ConflictingDefinitionsConfiguration.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(SettingConfigurationException.class)
                        .hasMessageContaining("declared twice"));
    }

    @Test
    @DisplayName("enabling both modes at once stops the context")
    void both_modes_enabled_fails_startup() {
        // The symptoms would otherwise be baffling: local writes surviving until the next projected
        // event and then silently reverting.
        contextRunner
                .withUserConfiguration(WorkingDefinitionsConfiguration.class)
                .withPropertyValues("ludwig.user-settings.projection.enabled=true")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(SettingConfigurationException.class)
                        .hasMessageContaining("both true"));
    }

    @Test
    @DisplayName("a sound set of definitions starts and is registered")
    void sound_definitions_start() {
        contextRunner
                .withUserConfiguration(WorkingDefinitionsConfiguration.class)
                .run(this::assertRegistryHoldsTheDefinitions);
    }

    @Test
    @DisplayName("a service that declares no settings starts with an empty registry")
    void no_definitions_is_not_an_error() {
        // Not an error: it has simply not declared any settings yet. Every lookup then fails with
        // "unknown setting", which is the correct answer rather than a startup refusal.
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .getBean(SettingDefinitionRegistry.class)
                .satisfies(registry -> assertThat(registry.definitions()).isEmpty()));
    }

    private void assertRegistryHoldsTheDefinitions(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed();
        SettingDefinitionRegistry registry = context.getBean(SettingDefinitionRegistry.class);
        assertThat(registry.find("user.timezone")).isPresent();
    }

    @Configuration(proxyBeanMethods = false)
    static class WorkingDefinitionsConfiguration {

        @Bean
        SettingDefinitionSource definitions() {
            return SettingDefinitionSource.of(SettingDefinition
                    .of("user.timezone", ZoneId.class)
                    .defaultValue(ZoneId.of("UTC"))
                    .build());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BrokenDefaultConfiguration {

        @Bean
        SettingDefinitionSource definitions() {
            return SettingDefinitionSource.of(SettingDefinition
                    .of("ui.broken-default", Integer.class)
                    .defaultValue(500)
                    .validator(SettingValidator.range(1, 100))
                    .build());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnconvertibleTypeConfiguration {

        @Bean
        SettingDefinitionSource definitions() {
            return SettingDefinitionSource.of(
                    SettingDefinition.of("ui.unconvertible", Thread.class).build());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ConflictingDefinitionsConfiguration {

        @Bean
        SettingDefinitionSource first() {
            return SettingDefinitionSource.of(SettingDefinition
                    .of("user.timezone", ZoneId.class)
                    .defaultValue(ZoneId.of("UTC"))
                    .build());
        }

        @Bean
        SettingDefinitionSource second() {
            return SettingDefinitionSource.of(SettingDefinition
                    .of("user.timezone", ZoneId.class)
                    .defaultValue(ZoneId.of("Europe/Moscow"))
                    .build());
        }
    }
}
