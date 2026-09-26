package ru.ludwigandreas.usersettings.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;
import ru.ludwigandreas.security.authz.AuthorityLookup;
import ru.ludwigandreas.security.config.LudwigSecurityAutoConfiguration;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingDefinitionSource;
import ru.ludwigandreas.usersettings.api.SettingScopeResolver;
import ru.ludwigandreas.usersettings.api.SettingValueSource;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.redaction.DeclaredSensitivityClassifier;
import ru.ludwigandreas.audit.redaction.SensitivityClassifier;
import ru.ludwigandreas.usersettings.audit.SettingsAuditRecorder;
import ru.ludwigandreas.usersettings.audit.SettingsCorrelationIdProvider;
import ru.ludwigandreas.usersettings.cache.CaffeineSettingsCache;
import ru.ludwigandreas.usersettings.cache.NoopSettingsCache;
import ru.ludwigandreas.usersettings.cache.SettingsCache;
import ru.ludwigandreas.usersettings.api.SettingValueConverter;
import ru.ludwigandreas.usersettings.convert.SettingValueConverterRegistry;
import ru.ludwigandreas.usersettings.entity.UserSettingValueEntity;
import ru.ludwigandreas.usersettings.exception.SettingConfigurationException;
import ru.ludwigandreas.usersettings.metrics.NoopSettingsMetrics;
import ru.ludwigandreas.usersettings.metrics.SettingsMetrics;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;
import ru.ludwigandreas.usersettings.resolve.ConfiguredSettingValueSource;
import ru.ludwigandreas.usersettings.resolve.DefaultSettingsLookup;
import ru.ludwigandreas.usersettings.resolve.PersistentSettingValueSource;
import ru.ludwigandreas.usersettings.resolve.PlatformSettingScopeResolver;
import ru.ludwigandreas.usersettings.resolve.PropertiesSettingDefaultsProvider;
import ru.ludwigandreas.usersettings.resolve.RoleSettingScopeResolver;
import ru.ludwigandreas.usersettings.resolve.SecurityContextTenantResolver;
import ru.ludwigandreas.usersettings.resolve.SettingDefaultsProvider;
import ru.ludwigandreas.usersettings.resolve.SettingsAccessPolicy;
import ru.ludwigandreas.usersettings.resolve.SettingsResolutionEngine;
import ru.ludwigandreas.usersettings.resolve.SettingsTenantResolver;
import ru.ludwigandreas.usersettings.resolve.TenantSettingScopeResolver;
import ru.ludwigandreas.usersettings.resolve.UserSettingScopeResolver;
import ru.ludwigandreas.usersettings.web.SettingsProblemMapper;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;
import ru.ludwigandreas.webcore.trace.TraceIdProvider;

/**
 * The half of the module both modes share: definitions, conversion, resolution, caching, access and
 * the audit trail.
 *
 * <p>Gated on a mode having been chosen ({@link SettingsModeCondition}), so adding the dependency
 * without configuring anything contributes no beans at all - no tables consulted, no topic consumed.
 *
 * <p>Everything here is {@code @ConditionalOnMissingBean}, which is what makes each piece an
 * extension point rather than a fixed decision. A service that needs group-based inheritance
 * registers its own {@link SettingScopeResolver}; one whose tenants are not the security module's
 * tenants registers its own {@link SettingsTenantResolver}; one with a type the built-ins do not
 * cover registers a {@link SettingValueConverter}. None of those replacements require touching
 * precedence, caching, validation or auditing.
 *
 * <p>Ordered after the security module so that {@code @ConditionalOnBean(AuthorityLookup.class)}
 * below has something to look at. A {@code @ConditionalOnBean} is evaluated against the bean
 * definitions registered so far, so without the ordering the role layer would be present or absent
 * depending on which autoconfiguration Spring happened to process first - which is the kind of bug
 * that works on one machine.
 */
@AutoConfiguration(after = LudwigSecurityAutoConfiguration.class)
@ConditionalOnClass(EntityManager.class)
@ConditionalOnProperty(prefix = "ludwig.user-settings", name = "enabled", matchIfMissing = true)
@Conditional(SettingsModeCondition.class)
@EnableConfigurationProperties(UserSettingsProperties.class)
public class UserSettingsAutoConfiguration {

    /**
     * Both modes at once is a misconfiguration, and one whose symptoms would be baffling: the service
     * would serve writes into tables that an incoming event stream also overwrites, so a user's
     * change would survive until the next projected event and then silently revert.
     */
    public UserSettingsAutoConfiguration(UserSettingsProperties properties) {
        if (properties.getOwner().isEnabled() && properties.getProjection().isEnabled()) {
            throw new SettingConfigurationException(
                    "ludwig.user-settings.owner.enabled and ludwig.user-settings.projection.enabled are"
                            + " both true. A service either owns these settings or keeps a replica of"
                            + " somebody else's; doing both means local writes are silently reverted by"
                            + " the next projected event.");
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingValueConverterRegistry settingValueConverterRegistry(
            ObjectProvider<SettingValueConverter<?>> converters,
            ObjectProvider<ObjectMapper> applicationMapper) {
        List<SettingValueConverter<?>> contributed = new ArrayList<>();
        converters.orderedStream().forEach(contributed::add);
        return new SettingValueConverterRegistry(contributed, jsonMapper(applicationMapper));
    }

    /**
     * The mapper JSON-encoded settings are serialized with: a copy of the application's, with
     * {@link JavaTimeModule} registered.
     *
     * <p>Deliberately not a bean. Publishing a second {@code ObjectMapper} would make the type
     * ambiguous at every injection point in the application that expects exactly one, which is a
     * spectacular way for a settings module to break an unrelated controller.
     *
     * <p>Copied rather than mutated, because registering a module on the application's own mapper is
     * this library reaching into a bean it does not own. The explicit registration matters: the
     * shipped quiet-hours setting is a record containing {@code LocalTime}, and without the module it
     * fails to serialize.
     */
    private static ObjectMapper jsonMapper(ObjectProvider<ObjectMapper> applicationMapper) {
        return applicationMapper.getIfAvailable(ObjectMapper::new).copy().registerModule(new JavaTimeModule());
    }

    /**
     * Built from every contributed source and validated here, at startup.
     *
     * <p>A service that contributes no source gets an empty registry and every lookup fails with
     * "unknown setting", which is the correct outcome: it has not declared any settings.
     */
    @Bean
    @ConditionalOnMissingBean
    public SettingDefinitionRegistry settingDefinitionRegistry(
            ObjectProvider<SettingDefinitionSource> sources, SettingValueConverterRegistry converters) {
        return new SettingDefinitionRegistry(sources.orderedStream().toList(), converters);
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingsMetrics settingsMetrics() {
        return new NoopSettingsMetrics();
    }

    /**
     * Falls back to web-core's trace id when the observability module is absent, and to nothing when
     * neither is present - so an audit row is written either way, one field poorer.
     */
    @Bean
    @ConditionalOnMissingBean
    public SettingsCorrelationIdProvider settingsCorrelationIdProvider(
            ObjectProvider<TraceIdProvider> traceIds) {
        TraceIdProvider provider = traceIds.getIfAvailable();
        return provider == null ? SettingsCorrelationIdProvider.none() : provider::currentTraceId;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(Caffeine.class)
    @ConditionalOnProperty(prefix = "ludwig.user-settings.cache", name = "enabled", matchIfMissing = true)
    public SettingsCache settingsCache(UserSettingsProperties properties) {
        return new CaffeineSettingsCache(
                properties.getCache().getTtl(), properties.getCache().getMaximumSize());
    }

    /** Registered when Caffeine is absent or caching is switched off; every lookup then re-resolves. */
    @Bean
    @ConditionalOnMissingBean(SettingsCache.class)
    public SettingsCache noopSettingsCache() {
        return new NoopSettingsCache();
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingsTenantResolver settingsTenantResolver(UserSettingsProperties properties) {
        return new SecurityContextTenantResolver(properties.getDefaultTenant());
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingsAccessPolicy settingsAccessPolicy(UserSettingsProperties properties) {
        return new SettingsAccessPolicy(
                properties.getAccess().getAdminAuthority(),
                properties.getAccess().isAllowUnauthenticated(),
                properties.getAccess().isAllowServicePrincipals());
    }

    @Bean
    @ConditionalOnMissingBean(UserSettingScopeResolver.class)
    public SettingScopeResolver userSettingScopeResolver() {
        return new UserSettingScopeResolver();
    }

    /**
     * Only when an {@link AuthorityLookup} exists. Without one there is no way to find out which
     * roles a subject holds, and a role layer that silently resolved to no roles would look like
     * "nobody inherits anything" rather than like a missing dependency.
     */
    @Bean
    @ConditionalOnMissingBean(RoleSettingScopeResolver.class)
    @ConditionalOnBean(AuthorityLookup.class)
    public SettingScopeResolver roleSettingScopeResolver(AuthorityLookup authorities) {
        return new RoleSettingScopeResolver(authorities);
    }

    @Bean
    @ConditionalOnMissingBean(TenantSettingScopeResolver.class)
    public SettingScopeResolver tenantSettingScopeResolver() {
        return new TenantSettingScopeResolver();
    }

    @Bean
    @ConditionalOnMissingBean(PlatformSettingScopeResolver.class)
    public SettingScopeResolver platformSettingScopeResolver() {
        return new PlatformSettingScopeResolver();
    }

    /**
     * Reads the configured defaults through the properties bean.
     *
     * <p>Replaced by the hot-reload flavour when that module is present - see
     * {@code UserSettingsHotReloadAutoConfiguration}, which registers its own before this one runs.
     */
    @Bean
    @ConditionalOnMissingBean
    public SettingDefaultsProvider settingDefaultsProvider(UserSettingsProperties properties) {
        return new PropertiesSettingDefaultsProvider(
                properties::getPlatformDefaults, properties::getTenantDefaults);
    }

    /** Stored rows. Present in both modes: a projection reads exactly the same tables. */
    @Bean
    @ConditionalOnMissingBean(PersistentSettingValueSource.class)
    public SettingValueSource persistentSettingValueSource(UserSettingValueRepository repository) {
        return new PersistentSettingValueSource(repository);
    }

    @Bean
    @ConditionalOnMissingBean(ConfiguredSettingValueSource.class)
    public SettingValueSource configuredSettingValueSource(SettingDefaultsProvider defaults,
                                                           SettingDefinitionRegistry registry) {
        return new ConfiguredSettingValueSource(defaults, registry);
    }

    /**
     * The change trail, written to the platform's shared sink.
     *
     * <p>No {@code UserSettingAuditRepository} any more: {@code user_setting_audit} migrated into
     * {@code audit_event} and its entity and repositories are gone. What this bean still owns is the
     * declarative PII rule - {@code SettingDefinition.isPii()} - which no heuristic and no configured list
     * can replace, and which is contributed to the platform classifier by
     * {@link #settingsSensitivityClassifier}.
     *
     * @param auditSink   the platform trail
     * @param actors      the platform's one actor resolution, shared with db-core's created_by stamping
     * @param correlation joins an event to the logs and traces of the same request
     * @param userSettingsClock stamps the event
     * @return the recorder
     */
    @Bean
    @ConditionalOnMissingBean
    public SettingsAuditRecorder settingsAuditRecorder(AuditSink auditSink, ActorResolver actors,
                                                       SettingsCorrelationIdProvider correlation,
                                                       Clock userSettingsClock) {
        return new SettingsAuditRecorder(auditSink, actors, correlation, userSettingsClock);
    }

    /**
     * This module's classification rule, contributed to the platform's redaction.
     *
     * <p>The declarative classifier - the only one of the four that recognises <em>personal</em> data rather
     * than <em>secret</em> data. A setting called {@code mobile} matches no secret-name heuristic and appears
     * in no partner's header list, and a phone number in a trail retained for years outlives every erasure
     * request that was meant to remove it.
     *
     * <p>Contributed as a bean so that anything else redacting in this service - a problem document, an
     * outbound call carrying a settings payload - masks the same keys this module does, rather than each
     * place having to ask the registry for itself.
     *
     * @param registry the declared settings
     * @return the classifier
     */
    @Bean
    public SensitivityClassifier settingsSensitivityClassifier(SettingDefinitionRegistry registry) {
        return new DeclaredSensitivityClassifier(key -> registry.find(key)
                .map(SettingDefinition::isPii)
                .orElse(false));
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingsResolutionEngine settingsResolutionEngine(SettingDefinitionRegistry registry,
                                                              ObjectProvider<SettingScopeResolver> resolvers,
                                                              ObjectProvider<SettingValueSource> sources,
                                                              SettingsMetrics metrics) {
        return new SettingsResolutionEngine(registry,
                new ArrayList<>(resolvers.orderedStream().toList()),
                new ArrayList<>(sources.orderedStream().toList()),
                metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingsLookup settingsLookup(SettingsResolutionEngine engine,
                                          SettingsCache cache,
                                          SettingsAccessPolicy accessPolicy,
                                          SettingsTenantResolver tenantResolver,
                                          SettingDefinitionRegistry registry,
                                          SettingsAuditRecorder audit,
                                          SettingsMetrics metrics) {
        return new DefaultSettingsLookup(engine, cache, accessPolicy, tenantResolver, registry, audit, metrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public Clock userSettingsClock() {
        return Clock.systemUTC();
    }

    /**
     * Built over a <em>shared</em> entity manager: a proxy that delegates to whatever
     * transaction-bound persistence context the calling thread is in, which is what makes one
     * application-scoped factory safe to share.
     */
    @Bean
    @ConditionalOnMissingBean
    public JPAQueryFactory jpaQueryFactory(EntityManagerFactory entityManagerFactory) {
        return new JPAQueryFactory(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
    }

    /** Contributed so this module's errors come back in the caller's language. */
    @Bean
    @ConditionalOnMissingBean(name = "userSettingsProblemMessageBundle")
    @ConditionalOnClass(ProblemMessageBundle.class)
    public ProblemMessageBundle userSettingsProblemMessageBundle() {
        return ProblemMessageBundle.of("i18n/ludwig-user-settings-messages");
    }

    @Bean
    @ConditionalOnMissingBean(SettingsProblemMapper.class)
    @ConditionalOnClass(ExceptionProblemMapper.class)
    public SettingsProblemMapper userSettingsProblemMapper() {
        return new SettingsProblemMapper();
    }

    /**
     * Registers this module's entities and repositories additively, without disturbing the consuming
     * application's own {@code @EntityScan}/{@code @EnableJpaRepositories} - so a service using the
     * module needs no scanning configuration of its own.
     */
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = UserSettingValueEntity.class)
    @EnableJpaRepositories(basePackageClasses = UserSettingValueRepository.class,
            repositoryBaseClass = BaseRepositoryImpl.class)
    static class UserSettingsJpaConfiguration {
    }
}
