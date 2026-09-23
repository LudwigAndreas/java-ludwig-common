package ru.ludwigandreas.usersettings.config;

import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.outbox.api.OutboxEventPublisher;
import ru.ludwigandreas.usersettings.api.SettingsWriter;
import ru.ludwigandreas.usersettings.audit.SettingsAuditRecorder;
import ru.ludwigandreas.usersettings.audit.SettingsCorrelationIdProvider;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillBatchPublisher;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillService;
import ru.ludwigandreas.usersettings.cache.SettingsCache;
import ru.ludwigandreas.usersettings.consent.ConsentService;
import ru.ludwigandreas.usersettings.consent.DefaultConsentService;
import ru.ludwigandreas.usersettings.event.NoopSettingsEventPublisher;
import ru.ludwigandreas.usersettings.event.OutboxSettingsEventPublisher;
import ru.ludwigandreas.usersettings.event.SettingsEventPublisher;
import ru.ludwigandreas.usersettings.consent.ConsentEntityMapper;
import ru.ludwigandreas.usersettings.consent.ConsentEntityMapperImpl;
import ru.ludwigandreas.usersettings.metrics.SettingsMetrics;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.repository.UserConsentRepository;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;
import ru.ludwigandreas.usersettings.resolve.SettingsAccessPolicy;
import ru.ludwigandreas.usersettings.resolve.SettingsResolutionEngine;
import ru.ludwigandreas.usersettings.resolve.SettingsTenantResolver;
import ru.ludwigandreas.usersettings.write.DefaultSettingsWriter;
import ru.ludwigandreas.usersettings.write.RawSettingWriter;

/**
 * Owner mode: this service owns the tables, serves writes and publishes the change stream.
 *
 * <p>The {@link SettingsWriter} bean exists only here, and that is the point. Business code that
 * injects one fails to start in projection mode rather than failing on the first user who tries to
 * save a preference - a wiring error found at deploy time instead of a support ticket found at
 * three in the morning.
 */
@AutoConfiguration(after = UserSettingsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "ludwig.user-settings.owner", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(UserSettingsProperties.class)
public class UserSettingsOwnerAutoConfiguration {

    /**
     * MapStruct generates the implementation as a {@code @Component}, but a consumer's component scan
     * never reaches this module's packages - so the generated class is registered explicitly, the
     * same way the repositories and entities are.
     */
    @Bean
    @ConditionalOnMissingBean(ConsentEntityMapper.class)
    public ConsentEntityMapper consentEntityMapper() {
        return new ConsentEntityMapperImpl();
    }

    /**
     * Publishes through the transactional outbox when it is on the classpath and publication is on.
     *
     * <p>Falls back to publishing nothing rather than failing, because a service that owns its users'
     * settings and is the only thing that reads them has no reason to run an outbox. Turning
     * publication on later is a dependency and a property, not a code change.
     */
    @Bean
    @ConditionalOnMissingBean(SettingsEventPublisher.class)
    @ConditionalOnClass(OutboxEventPublisher.class)
    @ConditionalOnProperty(prefix = "ludwig.user-settings.owner", name = "publish-events",
            matchIfMissing = true)
    public SettingsEventPublisher outboxSettingsEventPublisher(ObjectProvider<OutboxEventPublisher> outbox) {
        OutboxEventPublisher publisher = outbox.getIfAvailable();
        return publisher == null
                ? new NoopSettingsEventPublisher()
                : new OutboxSettingsEventPublisher(publisher);
    }

    @Bean
    @ConditionalOnMissingBean(SettingsEventPublisher.class)
    public SettingsEventPublisher noopSettingsEventPublisher() {
        return new NoopSettingsEventPublisher();
    }

    // SUPPRESS CHECKSTYLE ParameterNumber - a wiring method, not a call site. Every parameter is a
    // collaborator Spring injects by type; grouping them into a holder would add a class whose only
    // purpose is to satisfy a count.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean
    public SettingsWriter settingsWriter(UserSettingValueRepository repository,
                                          SettingDefinitionRegistry registry,
                                          SettingsResolutionEngine engine,
                                          SettingsCache cache,
                                          SettingsAccessPolicy accessPolicy,
                                          SettingsTenantResolver tenantResolver,
                                          SettingsAuditRecorder audit,
                                          SettingsEventPublisher events,
                                          SettingsMetrics metrics,
                                          Clock userSettingsClock) {
        return new DefaultSettingsWriter(repository, registry, engine, cache, accessPolicy,
                tenantResolver, audit, events, metrics, userSettingsClock);
    }

    /** Writable here; the projection registers the same class with writes refused. */
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
                correlation, metrics, userSettingsClock, true);
    }

    /**
     * Owner-mode only, and unconditionally registered rather than gated behind a property.
     *
     * <p>The bean does nothing until somebody calls it, and the thing an operator needs at the moment
     * they need it is for it to already be there. A flag would mean discovering during an incident
     * that seeding a replica requires a redeploy first.
     */
    @Bean
    @ConditionalOnMissingBean
    public SettingsBackfillBatchPublisher settingsBackfillBatchPublisher(
            UserSettingValueRepository values,
            UserConsentRepository consents,
            SettingsEventPublisher events) {
        return new SettingsBackfillBatchPublisher(values, consents, events);
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingsBackfillService settingsBackfillService(SettingsBackfillBatchPublisher batches,
                                                            SettingsEventPublisher events,
                                                            SettingsMetrics metrics) {
        return new SettingsBackfillService(batches, events, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public RawSettingWriter rawSettingWriter(SettingsWriter writer, SettingDefinitionRegistry registry) {
        return new RawSettingWriter(writer, registry);
    }
}
