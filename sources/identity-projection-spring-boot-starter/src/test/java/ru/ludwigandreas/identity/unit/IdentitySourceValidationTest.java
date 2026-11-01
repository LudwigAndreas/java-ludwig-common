package ru.ludwigandreas.identity.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.identity.config.IdentityProjectionProperties;
import ru.ludwigandreas.identity.feed.IdentityFeed;
import ru.ludwigandreas.identity.projection.IdentitySourceValidator;
import ru.ludwigandreas.identity.projection.IdentitySources;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

class IdentitySourceValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Validation.class);

    @Configuration(proxyBeanMethods = false)
    static class Validation {

        @Bean
        IdentitySources identitySources() {
            return new IdentitySources(Map.of("staff", new IdentityProjectionProperties.Source(),
                    "customers", new IdentityProjectionProperties.Source()));
        }

        @Bean
        IdentitySourceValidator validator(IdentitySources sources,
                                          ObjectProvider<IdentityFeed> feeds) {
            return new IdentitySourceValidator(sources, feeds);
        }
    }

    private static IdentityFeed feed(String source) {
        return new IdentityFeed() {
            @Override
            public String source() {
                return source;
            }

            @Override
            public String kind() {
                return "test";
            }

            @Override
            public Map<String, Object> status() {
                return Map.of();
            }
        };
    }

    @Test
    @DisplayName("a feed naming an undeclared source fails startup, naming it and the declared ones")
    void undeclaredFeedSource() {
        runner.withBean("staffFeed", IdentityFeed.class, () -> feed("partners")).run(context ->
                assertThat(context).hasFailed().getFailure().hasStackTraceContaining("'partners'")
                        .hasStackTraceContaining("customers"));
    }

    @Test
    @DisplayName("a declared source with no feed starts")
    void unservedSourceStarts() {
        runner.withBean("staffFeed", IdentityFeed.class, () -> feed("staff"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("a source name outside the grammar is refused")
    void badSourceName() {
        assertThatThrownBy(() -> new IdentitySources(Map.of("Staff_1", new IdentityProjectionProperties.Source())))
                .isInstanceOf(SecurityConfigurationException.class).hasMessageContaining("Staff_1");
    }
}
