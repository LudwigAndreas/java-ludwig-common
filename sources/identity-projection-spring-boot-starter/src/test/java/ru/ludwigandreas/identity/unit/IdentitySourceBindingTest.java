package ru.ludwigandreas.identity.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.MapPropertySource;
import ru.ludwigandreas.identity.config.IdentityProjectionAutoConfiguration;
import ru.ludwigandreas.identity.config.IdentityProjectionProperties;
import ru.ludwigandreas.identity.config.IdentityProjectionProperties.UnmappedRoles;

class IdentitySourceBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IdentityProjectionProperties.class)
    static class Binding {

        @Bean
        @ConfigurationPropertiesBinding
        static Converter<String, IdentityProjectionProperties.Source> emptyIdentitySource() {
            return IdentityProjectionAutoConfiguration.emptyIdentitySource();
        }

        @Bean
        @ConfigurationPropertiesBinding
        static Converter<Map<?, ?>, IdentityProjectionProperties.Source> emptyIdentitySourceMap() {
            return IdentityProjectionAutoConfiguration.emptyIdentitySourceMap();
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Test
    @DisplayName("a source declared as {} - flattened by YAML to an empty value - binds with defaults")
    void emptyDeclaration() {
        runner.withPropertyValues("ludwig.identity.sources.oidc=", "ludwig.identity.sources.staff.unmapped-roles=DROP")
                .run(context -> {
                    IdentityProjectionProperties properties = context.getBean(IdentityProjectionProperties.class);
                    assertThat(properties.getSources()).containsKeys("oidc", "staff");
                    assertThat(properties.getSources().get("oidc").getUnmappedRoles()).isEqualTo(UnmappedRoles.KEEP);
                    assertThat(properties.getSources().get("staff").getUnmappedRoles()).isEqualTo(UnmappedRoles.DROP);
                });
    }

    @Test
    @DisplayName("a source a property source keeps as an empty map binds with defaults")
    void emptyMapDeclaration() {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("yaml", Map.of("ludwig.identity.sources.oidc", Map.of()))))
                .run(context -> assertThat(context.getBean(IdentityProjectionProperties.class).getSources())
                        .containsKey("oidc"));
    }

    @Test
    @DisplayName("a source declared as a plain value is refused")
    void plainValueIsRefused() {
        runner.withPropertyValues("ludwig.identity.sources.oidc=yes")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining("or {} for none"));
    }
}
