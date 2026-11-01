package ru.ludwigandreas.identity.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.db.core.repository.BaseRepositoryImpl;
import ru.ludwigandreas.identity.authority.DatabaseAuthorityResolver;
import ru.ludwigandreas.identity.authority.DatabaseDataScopeProvider;
import ru.ludwigandreas.identity.authority.DatabasePartnerIdentityResolver;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.feed.IdentityFeed;
import ru.ludwigandreas.identity.feed.IdentityProjection;
import ru.ludwigandreas.identity.projection.IdentityChangeMapper;
import ru.ludwigandreas.identity.projection.IdentityChangeMapperImpl;
import ru.ludwigandreas.identity.projection.IdentityMetrics;
import ru.ludwigandreas.identity.projection.IdentityProjectionService;
import ru.ludwigandreas.identity.projection.IdentitySourceValidator;
import ru.ludwigandreas.identity.projection.IdentitySources;
import ru.ludwigandreas.identity.projection.MicrometerIdentityMetrics;
import ru.ludwigandreas.identity.projection.RemovedIdentityKeys;
import ru.ludwigandreas.identity.projection.SweepStore;
import ru.ludwigandreas.identity.repository.SecurityGrantRepository;
import ru.ludwigandreas.identity.repository.SecurityPartnerRepository;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.security.authn.mtls.PartnerIdentityResolver;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.config.LudwigSecurityAutoConfiguration;
import ru.ludwigandreas.security.config.MutualTlsAutoConfiguration;
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.data.DataScopeProvider;

/**
 * Wires the projection: its entities and repositories, and the three security SPIs it implements.
 *
 * <p>Because these are registered as the security module's SPI types, adding this dependency is all a
 * service has to do - {@code security-spring-boot-starter} backs its own fallbacks off automatically
 * ({@code @ConditionalOnMissingBean}) and starts resolving roles from the projection instead.
 *
 * <p>Ordered before the two configurations that hold those fallbacks, explicitly: a fallback's
 * {@code @ConditionalOnMissingBean} only sees this module's bean if this class was processed first, and an
 * order that merely happened to hold alphabetically broke as soon as other configurations added ordering
 * constraints of their own.
 */
@AutoConfiguration(before = {LudwigSecurityAutoConfiguration.class, MutualTlsAutoConfiguration.class})
@ConditionalOnClass(EntityManager.class)
@ConditionalOnProperty(prefix = "ludwig.identity", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdentityProjectionProperties.class)
public class IdentityProjectionAutoConfiguration {

    /**
     * MapStruct generates the implementation as a {@code @Component}, but a consumer's component scan
     * never reaches this module's packages - so the generated class is registered explicitly here, the
     * same way the repositories and entities are.
     */
    @Bean
    @ConditionalOnMissingBean(IdentityChangeMapper.class)
    public IdentityChangeMapper identityChangeMapper() {
        return new IdentityChangeMapperImpl();
    }

    /**
     * Lets a source be declared with no settings, {@code staff: {}}: YAML flattens an empty map to an empty string,
     * which would otherwise fail to bind to a source.
     *
     * @return the converter; anything other than a blank string is refused
     */
    @Bean
    @ConfigurationPropertiesBinding
    public static Converter<String, IdentityProjectionProperties.Source> emptyIdentitySource() {
        return new EmptySourceConverter();
    }

    /**
     * The same for a property source that keeps {@code {}} as an empty map rather than flattening it.
     *
     * @return the converter; a non-empty map is bound property by property and never reaches it
     */
    @Bean
    @ConfigurationPropertiesBinding
    public static Converter<Map<?, ?>, IdentityProjectionProperties.Source> emptyIdentitySourceMap() {
        return new EmptySourceMapConverter();
    }

    /** See {@link #emptyIdentitySourceMap()}. */
    static final class EmptySourceMapConverter implements Converter<Map<?, ?>, IdentityProjectionProperties.Source> {

        @Override
        public IdentityProjectionProperties.Source convert(Map<?, ?> value) {
            if (value.isEmpty()) {
                return new IdentityProjectionProperties.Source();
            }
            throw new IllegalArgumentException("Unexpected identity source settings " + value.keySet());
        }
    }

    /** A class, not a lambda: binding finds a converter by its declared generic types, which a lambda erases. */
    static final class EmptySourceConverter implements Converter<String, IdentityProjectionProperties.Source> {

        @Override
        public IdentityProjectionProperties.Source convert(String value) {
            if (value.isBlank()) {
                return new IdentityProjectionProperties.Source();
            }
            throw new IllegalArgumentException("An identity source is a map of settings (role-mapping, unmapped-roles),"
                    + " or {} for none - not '" + value + "'");
        }
    }

    @Bean
    public IdentitySources identitySources(IdentityProjectionProperties properties) {
        return new IdentitySources(properties.getSources());
    }

    @Bean
    public IdentitySourceValidator identitySourceValidator(IdentitySources sources,
                                                           ObjectProvider<IdentityFeed> feeds) {
        return new IdentitySourceValidator(sources, feeds);
    }

    @Bean
    public RemovedIdentityKeys removedIdentityKeys(ConfigurableEnvironment environment) {
        return new RemovedIdentityKeys(environment);
    }

    @Bean
    public SweepStore identitySweepStore(DataSource dataSource, PlatformTransactionManager transactionManager,
                                         LudwigCache<PrincipalRef, Authorities> authorityCache,
                                         ApplicationEventPublisher events, ObjectProvider<IdentityMetrics> metrics,
                                         IdentityProjectionProperties properties) {
        return new SweepStore(new NamedParameterJdbcTemplate(dataSource), new TransactionTemplate(transactionManager),
                authorityCache, events, metrics.getIfAvailable(() -> IdentityMetrics.NONE),
                properties.getSweep().getMaxDisableRatio());
    }

    @Bean
    @ConditionalOnMissingBean(IdentityProjection.class)
    public IdentityProjectionService identityProjection(SecurityUserRepository users, IdentityChangeMapper mapper,
                                                        IdentitySources sources,
                                                        LudwigCache<PrincipalRef, Authorities> authorityCache,
                                                        ApplicationEventPublisher events,
                                                        ObjectProvider<IdentityMetrics> metrics, SweepStore sweeps,
                                                        IdentityProjectionProperties properties) {
        return new IdentityProjectionService(users, mapper, sources, authorityCache, events,
                metrics.getIfAvailable(() -> IdentityMetrics.NONE), sweeps, properties.getContact().isEnabled());
    }

    @Bean
    @ConditionalOnMissingBean(AuthorityResolver.class)
    public AuthorityResolver databaseAuthorityResolver(SecurityUserRepository users,
                                                        SecurityPartnerRepository partners,
                                                        IdentityProjectionProperties properties) {
        return new DatabaseAuthorityResolver(users, partners, properties);
    }

    @Bean
    @ConditionalOnMissingBean(DatabaseDataScopeProvider.class)
    @ConditionalOnProperty(prefix = "ludwig.identity", name = "grants-enabled", matchIfMissing = true)
    public DataScopeProvider databaseDataScopeProvider(SecurityGrantRepository grants) {
        return new DatabaseDataScopeProvider(grants);
    }

    @Bean
    @ConditionalOnMissingBean(PartnerIdentityResolver.class)
    @ConditionalOnProperty(prefix = "ludwig.identity", name = "partner-registry-enabled",
            matchIfMissing = true)
    public PartnerIdentityResolver databasePartnerIdentityResolver(SecurityPartnerRepository partners,
                                                                    SecurityProperties securityProperties) {
        return new DatabasePartnerIdentityResolver(partners, securityProperties);
    }

    /** The projection's counters, where Micrometer is on the classpath and a registry exists. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class IdentityMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(IdentityMetrics.class)
        IdentityMetrics identityMetrics(ObjectProvider<MeterRegistry> registry) {
            MeterRegistry meters = registry.getIfAvailable();
            return meters == null ? IdentityMetrics.NONE : new MicrometerIdentityMetrics(meters);
        }
    }

    /**
     * Registers this module's entities and repositories additively, without disturbing the consuming
     * application's own {@code @EntityScan}/{@code @EnableJpaRepositories} - so a service using the module
     * needs no scanning configuration of its own.
     */
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = SecurityUserEntity.class)
    @EnableJpaRepositories(basePackageClasses = SecurityUserRepository.class,
            repositoryBaseClass = BaseRepositoryImpl.class)
    static class IdentityJpaConfiguration {
    }
}
