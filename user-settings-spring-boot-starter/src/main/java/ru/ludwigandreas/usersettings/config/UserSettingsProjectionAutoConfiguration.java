package ru.ludwigandreas.usersettings.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.usersettings.audit.SettingsCorrelationIdProvider;
import ru.ludwigandreas.usersettings.cache.SettingsCache;
import ru.ludwigandreas.usersettings.consent.ConsentService;
import ru.ludwigandreas.usersettings.consent.DefaultConsentService;
import ru.ludwigandreas.usersettings.event.NoopSettingsEventPublisher;
import ru.ludwigandreas.usersettings.event.SettingsEventPublisher;
import ru.ludwigandreas.usersettings.consent.ConsentEntityMapper;
import ru.ludwigandreas.usersettings.consent.ConsentEntityMapperImpl;
import ru.ludwigandreas.usersettings.metrics.SettingsMetrics;
import ru.ludwigandreas.usersettings.projection.SettingsProjectionService;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.repository.UserConsentRepository;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;
import ru.ludwigandreas.usersettings.resolve.SettingsAccessPolicy;
import ru.ludwigandreas.usersettings.resolve.SettingsTenantResolver;
import ru.ludwigandreas.usersettings.write.RawSettingWriter;

/**
 * Projection mode: this service keeps a read-only replica of another service's settings.
 *
 * <p>No {@code SettingsWriter} bean is registered at all. The {@link RawSettingWriter} below is
 * constructed without one, so the shipped REST controller - which is mounted in both modes because
 * it has to answer an HTTP request either way - refuses writes with a localized 409 saying where the
 * settings are actually managed.
 *
 * <p>Publishing is a no-op here. A replica that republished what it consumed would put its own
 * events back on the stream it reads, which is a loop with a delay in it.
 */
@AutoConfiguration(after = UserSettingsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "ludwig.user-settings.projection", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(UserSettingsProperties.class)
public class UserSettingsProjectionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ConsentEntityMapper.class)
    public ConsentEntityMapper consentEntityMapper() {
        return new ConsentEntityMapperImpl();
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingsEventPublisher settingsEventPublisher() {
        return new NoopSettingsEventPublisher();
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingsProjectionService settingsProjectionService(UserSettingValueRepository values,
                                                                UserConsentRepository consents,
                                                                SettingsCache cache,
                                                                SettingsMetrics metrics,
                                                                UserSettingsProperties properties) {
        return new SettingsProjectionService(values, consents, cache, metrics,
                properties.getProjection().getSourceSystem());
    }

    /** The read half of the same ledger; {@code grant} and {@code revoke} refuse. */
    @Bean
    @ConditionalOnMissingBean
    public ConsentService consentService(UserConsentRepository repository,
                                          ConsentEntityMapper mapper,
                                          SettingsAccessPolicy accessPolicy,
                                          SettingsTenantResolver tenantResolver,
                                          SettingsEventPublisher events,
                                          SettingsCorrelationIdProvider correlation,
                                          SettingsMetrics metrics,
                                          Clock userSettingsClock) {
        return new DefaultConsentService(repository, mapper, accessPolicy, tenantResolver, events,
                correlation, metrics, userSettingsClock, false);
    }

    @Bean
    @ConditionalOnMissingBean
    public RawSettingWriter rawSettingWriter(SettingDefinitionRegistry registry) {
        return new RawSettingWriter(null, registry);
    }
}
