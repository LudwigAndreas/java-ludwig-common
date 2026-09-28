package ru.ludwigandreas.usersettings.integration;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.SettingDefinitionSource;

/**
 * What a consuming service supplies: its setting definitions, and a way to find a subject's roles.
 *
 * <p>The {@link AuthorityLookup} is a stub over an in-memory map rather than a mock, because the role
 * layer's behaviour depends on the <em>order</em> roles come back in and a mock returning a
 * {@code Set} would make the test's own fixture non-deterministic - which is exactly the bug the
 * ordering rule exists to prevent.
 */
@TestConfiguration(proxyBeanMethods = false)
public class UserSettingsTestConfiguration {

    /** Subject to roles, in the order the resolver should treat as significant. */
    static final Map<String, java.util.List<String>> ROLES = new LinkedHashMap<>();

    @Bean
    SettingDefinitionSource testSettingDefinitions() {
        return IntegrationSettings.source();
    }

    @Bean
    AuthorityLookup testAuthorityLookup() {
        return ref -> authoritiesFor(ref);
    }

    private static Authorities authoritiesFor(PrincipalRef ref) {
        Authorities.AuthoritiesBuilder builder = Authorities.builder();
        ROLES.getOrDefault(ref.subject(), java.util.List.of()).forEach(builder::role);
        return builder.build();
    }
}
