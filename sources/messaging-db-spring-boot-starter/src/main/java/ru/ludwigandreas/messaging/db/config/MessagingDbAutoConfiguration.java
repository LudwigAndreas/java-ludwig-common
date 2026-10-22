package ru.ludwigandreas.messaging.db.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.CompositeRecordInterceptor;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.NoopAuditSink;
import ru.ludwigandreas.messaging.api.EnvelopeReader;
import ru.ludwigandreas.messaging.cluster.DefaultKafkaClusterProducers;
import ru.ludwigandreas.messaging.cluster.KafkaClusterProducers;
import ru.ludwigandreas.messaging.config.KafkaClustersAutoConfiguration;
import ru.ludwigandreas.messaging.db.consumer.ConsumerSupport;
import ru.ludwigandreas.messaging.db.consumer.DeadLetterWriter;
import ru.ludwigandreas.messaging.db.consumer.ListenerFactoryPostProcessor;
import ru.ludwigandreas.messaging.db.log.LogStore;
import ru.ludwigandreas.messaging.db.ops.LogPurgeJob;
import ru.ludwigandreas.messaging.db.producer.LogClusterProducers;
import ru.ludwigandreas.messaging.db.settings.MessagingDbProperties;
import ru.ludwigandreas.messaging.metrics.ConsumerActivityMonitor;
import ru.ludwigandreas.messaging.metrics.MessagingMetrics;
import ru.ludwigandreas.messaging.settings.KafkaClusters;
import ru.ludwigandreas.messaging.settings.MessagingProperties;

/**
 * Puts the log behind the replaced Kafka clusters: behind messaging's producer seam, which every platform
 * write goes through, and behind every listener container factory.
 *
 * <p>No enabling property: this module is switched on by being a dependency and off by not being one,
 * which is the whole point of it.
 *
 * <p>Before {@link KafkaClustersAutoConfiguration}, because messaging registers its producer seam only when
 * no {@link KafkaClusterProducers} bean exists. Registering the decorated seam here leaves exactly one, and
 * the outbox's templates and messaging's dead-letter templates are all built from it.
 */
@AutoConfiguration(before = KafkaClustersAutoConfiguration.class)
@ConditionalOnClass(KafkaListener.class)
@EnableConfigurationProperties({MessagingDbProperties.class, MessagingProperties.class})
public class MessagingDbAutoConfiguration {

    /**
     * Swaps the listener container factories. Static, as every post-processor bean method must be, so that
     * it does not drag this configuration class into early instantiation.
     *
     * @param support the shared collaborators, resolved lazily
     * @return the post-processor
     */
    @Bean
    public static ListenerFactoryPostProcessor ludwigMessagingDbListenerFactoryPostProcessor(
            ObjectProvider<ConsumerSupport> support) {
        return new ListenerFactoryPostProcessor(support);
    }

    /**
     * The log.
     *
     * @param dataSource   the shared database
     * @param objectMapper the application's mapper, for the header list
     * @param properties   this module's configuration
     * @return the store
     */
    @Bean
    public LogStore ludwigMessagingDbLogStore(DataSource dataSource, ObjectProvider<ObjectMapper> objectMapper,
                                             MessagingDbProperties properties) {
        return new LogStore(dataSource, objectMapper.getIfAvailable(ObjectMapper::new), properties.getSchema(),
                properties.getPartitions());
    }

    /**
     * Messaging's producer seam, with each replaced cluster appending to the log while active. While
     * draining every cluster gets its own producer, so a replaced cluster's writes go to the address
     * {@code ludwig.kafka.clusters.<name>} gives it.
     *
     * @param clusters   the declared clusters
     * @param store      the log
     * @param properties this module's configuration
     * @return the seam
     * @throws IllegalStateException naming a replaced cluster that is not declared
     */
    @Bean
    public KafkaClusterProducers ludwigKafkaClusterProducers(KafkaClusters clusters, LogStore store,
                                                            MessagingDbProperties properties) {
        for (String cluster : properties.getClusters()) {
            if (!clusters.contains(cluster)) {
                throw new IllegalStateException(MessagingDbProperties.PREFIX + ".clusters names '" + cluster
                        + "', which is not a declared Kafka cluster. Declare it under " + KafkaClusters.PREFIX + "."
                        + cluster + " (no address is needed until draining). Declared: " + clusters.names());
            }
        }
        boolean active = properties.getMode() == MessagingDbProperties.Mode.ACTIVE;
        return new LogClusterProducers(new DefaultKafkaClusterProducers(clusters), store,
                active ? properties.getClusters() : Set.of());
    }

    /**
     * What every log container shares with messaging's own containers.
     *
     * @param properties      this module's configuration
     * @param messaging       messaging's configuration
     * @param store           the log
     * @param metrics         messaging's counters
     * @param envelopeReader  messaging's envelope reader
     * @param auditSink       the audit sink, when there is one
     * @param clock           the application's clock, when there is exactly one
     * @param interceptors    every record interceptor in the context
     * @param producers       the producer seam, which every dead letter goes through
     * @param activityMonitor messaging's activity monitor, so log records count as consumed
     * @param objectMapper    the application's mapper
     * @return the shared collaborators
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a bean method assembling one collaborator from the context.
    @SuppressWarnings({"checkstyle:ParameterNumber", "unchecked"})
    @Bean
    public ConsumerSupport ludwigMessagingDbConsumerSupport(
            MessagingDbProperties properties, MessagingProperties messaging, LogStore store,
            MessagingMetrics metrics, EnvelopeReader envelopeReader, ObjectProvider<AuditSink> auditSink,
            ObjectProvider<Clock> clock, ObjectProvider<RecordInterceptor<Object, Object>> interceptors,
            KafkaClusterProducers producers, ConsumerActivityMonitor activityMonitor,
            ObjectProvider<ObjectMapper> objectMapper) {
        ObjectMapper mapper = objectMapper.getIfAvailable(ObjectMapper::new);
        // Every interceptor in the context, as ListenerContainerFactoryBuilder composes them: messaging's
        // activity monitor and observability's correlation interceptor among them. A container's own
        // interceptor is not reachable through spring-kafka's public API, so it is rebuilt from the same beans.
        List<RecordInterceptor<Object, Object>> all = interceptors.orderedStream().toList();
        RecordInterceptor<Object, Object> interceptor = all.isEmpty() ? null : all.size() == 1 ? all.get(0)
                : new CompositeRecordInterceptor<>(all.toArray(new RecordInterceptor[0]));
        return new ConsumerSupport(properties, messaging, store, metrics, envelopeReader,
                auditSink.getIfAvailable(NoopAuditSink::new), clock.getIfUnique(Clock::systemUTC),
                new DeadLetterWriter(producers, mapper), interceptor, activityMonitor);
    }

    /**
     * The scheduler the purge and the lag refresh run on, owned here so neither depends on the application
     * enabling scheduling.
     *
     * @return the scheduler
     */
    @Bean(name = "messagingDbTaskScheduler")
    public TaskScheduler messagingDbTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("messaging-db-");
        return scheduler;
    }

    /**
     * The retention purge.
     *
     * @param store      the log
     * @param scheduler  this module's scheduler
     * @param properties this module's configuration
     * @param clock      the application's clock, when there is exactly one
     * @return the job
     */
    @Bean
    public LogPurgeJob ludwigMessagingDbPurgeJob(LogStore store,
                                                @Qualifier("messagingDbTaskScheduler") TaskScheduler scheduler,
                                                MessagingDbProperties properties, ObjectProvider<Clock> clock) {
        return new LogPurgeJob(store, scheduler, properties.getRetention(), properties.getPurgeInterval(),
                clock.getIfUnique(Clock::systemUTC));
    }
}
