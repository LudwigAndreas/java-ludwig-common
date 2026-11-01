package ru.ludwigandreas.identity.directory.config;

import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ludwigandreas.identity.config.IdentityProjectionAutoConfiguration;
import ru.ludwigandreas.identity.directory.service.DirectoryFeed;
import ru.ludwigandreas.identity.directory.service.DirectoryJob;
import ru.ludwigandreas.identity.directory.service.DirectoryMetrics;
import ru.ludwigandreas.identity.directory.service.DirectorySync;
import ru.ludwigandreas.identity.directory.IdentityDirectory;
import ru.ludwigandreas.identity.directory.service.MicrometerDirectoryMetrics;
import ru.ludwigandreas.identity.directory.repository.DirectoryStateStore;
import ru.ludwigandreas.identity.feed.IdentityProjection;
import ru.ludwigandreas.job.core.config.JobCoreAutoConfiguration;
import ru.ludwigandreas.job.core.lock.RunLock;

/**
 * The directory feed: for every {@link IdentityDirectory} bean, a sync, its two jobs and its feed.
 *
 * <p>Active only where the service declares a directory. The per-directory beans are registered by name -
 * {@code identityDirectorySync.<bean>}, {@code identityDirectorySyncJob.<bean>},
 * {@code identityDirectorySweepJob.<bean>}, {@code identityDirectoryFeed.<bean>} - because the jobs area and the
 * projection's validation find jobs and feeds as beans, and the number of directories is the service's.
 */
@AutoConfiguration(after = {IdentityProjectionAutoConfiguration.class, JobCoreAutoConfiguration.class})
@ConditionalOnBean(IdentityDirectory.class)
@EnableConfigurationProperties(IdentityDirectoryProperties.class)
public class IdentityDirectoryAutoConfiguration {

    static final String SCHEDULER = "identityDirectoryScheduler";

    @Bean
    @ConditionalOnMissingBean
    public DirectoryStateStore identityDirectoryStateStore(DataSource dataSource) {
        return new DirectoryStateStore(new NamedParameterJdbcTemplate(dataSource));
    }

    /**
     * The directories' own scheduler: a slow provider's run must not hold up any other job of the service.
     *
     * @return the scheduler
     */
    @Bean(name = SCHEDULER, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = SCHEDULER)
    public ThreadPoolTaskScheduler identityDirectoryScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("identity-directory-");
        return scheduler;
    }

    @Bean
    DirectoryValidator identityDirectoryValidator(ObjectProvider<IdentityDirectory> directories) {
        return new DirectoryValidator(directories);
    }

    @Bean
    public static BeanDefinitionRegistryPostProcessor identityDirectoryRegistrar() {
        return new Registrar();
    }

    /** The run timer, where Micrometer is on the classpath and a registry exists. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(DirectoryMetrics.class)
        DirectoryMetrics identityDirectoryMetrics(ObjectProvider<MeterRegistry> registry) {
            MeterRegistry meters = registry.getIfAvailable();
            return meters == null ? DirectoryMetrics.NONE : new MicrometerDirectoryMetrics(meters);
        }
    }

    /** Registers one sync, two jobs and one feed per directory bean. */
    static final class Registrar implements BeanDefinitionRegistryPostProcessor {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            if (!(registry instanceof ConfigurableListableBeanFactory factory)) {
                return;
            }
            for (String name : factory.getBeanNamesForType(IdentityDirectory.class, true, false)) {
                String sync = "identityDirectorySync." + name;
                registry.registerBeanDefinition(sync, new RootBeanDefinition(DirectorySync.class,
                        () -> sync(factory, factory.getBean(name, IdentityDirectory.class))));
                registry.registerBeanDefinition("identityDirectorySyncJob." + name,
                        new RootBeanDefinition(DirectoryJob.class, () -> job(factory, sync, false)));
                registry.registerBeanDefinition("identityDirectorySweepJob." + name,
                        new RootBeanDefinition(DirectoryJob.class, () -> job(factory, sync, true)));
                registry.registerBeanDefinition("identityDirectoryFeed." + name,
                        new RootBeanDefinition(DirectoryFeed.class, () -> new DirectoryFeed(
                                factory.getBean(name, IdentityDirectory.class),
                                factory.getBean(DirectoryStateStore.class))));
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            // Everything is registered above.
        }

        private static DirectorySync sync(ConfigurableListableBeanFactory factory, IdentityDirectory directory) {
            IdentityDirectoryProperties.Source settings = factory.getBean(IdentityDirectoryProperties.class)
                    .settingsFor(directory.source());
            return new DirectorySync(directory, factory.getBean(IdentityProjection.class),
                    factory.getBean(DirectoryStateStore.class),
                    new TransactionTemplate(factory.getBean(PlatformTransactionManager.class)),
                    factory.getBeanProvider(DirectoryMetrics.class).getIfAvailable(() -> DirectoryMetrics.NONE),
                    settings.getPageSize());
        }

        private static DirectoryJob job(ConfigurableListableBeanFactory factory, String syncBean, boolean sweep) {
            DirectorySync sync = factory.getBean(syncBean, DirectorySync.class);
            IdentityDirectoryProperties.Source settings = factory.getBean(IdentityDirectoryProperties.class)
                    .settingsFor(sync.source());
            TaskScheduler scheduler = factory.getBean(SCHEDULER, TaskScheduler.class);
            RunLock lock = factory.getBean(RunLock.class);
            return sweep
                    ? DirectoryJob.sweep(sync, scheduler, settings.getSweepInterval(), lock, settings.getLease())
                    : DirectoryJob.sync(sync, scheduler, settings.getInitialDelay(), settings.getSyncInterval(), lock,
                            settings.getLease());
        }
    }
}
