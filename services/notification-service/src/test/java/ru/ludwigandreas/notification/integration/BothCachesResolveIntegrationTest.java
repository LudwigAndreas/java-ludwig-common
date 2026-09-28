package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.cache.api.LudwigCacheRegistry;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.SettingsSubject;

/**
 * Two platform caches in one context, injected by their generic types.
 *
 * <p>This service is the only one carrying both {@code security-spring-boot-starter} and
 * {@code user-settings-spring-boot-starter}, so it is the only place the question can be asked: after the
 * caching consolidation there are two {@code LudwigCache} beans on the context, distinguished only by their
 * type arguments. Spring resolves that from each {@code @Bean} method's declared return type, which works -
 * and is exactly the kind of thing that works until somebody simplifies a bean method's signature to the raw
 * type, at which point every injection point becomes ambiguous and the failure is a context that will not
 * start with a message about two candidates.
 *
 * <p>Worth its own test rather than being implied by the service booting, because the service would boot
 * either way if only one of the two were ever injected.
 */
class BothCachesResolveIntegrationTest extends NotificationTestBase {

    @Autowired
    private LudwigCacheRegistry registry;

    @Autowired
    private LudwigCache<PrincipalRef, Authorities> authorities;

    @Autowired
    private LudwigCache<SettingsSubject, ResolvedSettings> settings;

    @Test
    @DisplayName("both declared caches are registered, and each injects by its own type arguments")
    void bothCachesAreDistinct() {
        assertThat(registry.names()).contains("authorities", "settings");
        assertThat(authorities.name()).isEqualTo("authorities");
        assertThat(settings.name()).isEqualTo("settings");
        assertThat(authorities).isNotSameAs(settings);
    }
}
