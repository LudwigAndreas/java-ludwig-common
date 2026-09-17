package ru.ludwigandreas.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import ru.ludwigandreas.hotreload.binding.HotReloadTypedConfigFactory;
import ru.ludwigandreas.hotreload.binding.RefreshableConfig;
import ru.ludwigandreas.notification.service.metrics.NotificationMetrics;
import ru.ludwigandreas.notification.repository.NotificationDeliveryRepository;
import ru.ludwigandreas.notification.service.channel.ChannelRegistry;
import ru.ludwigandreas.notification.service.channel.ChannelRuntime;
import ru.ludwigandreas.notification.service.channel.NotificationChannel;
import ru.ludwigandreas.notification.service.digest.DigestCollapseService;
import ru.ludwigandreas.notification.service.digest.DigestScheduler;
import ru.ludwigandreas.notification.service.lock.DistributedLock;
import ru.ludwigandreas.notification.service.lock.LockLeaseService;
import ru.ludwigandreas.notification.service.lock.PostgresDistributedLock;
import ru.ludwigandreas.notification.service.preference.SuppressionService;
import ru.ludwigandreas.notification.service.queue.ChannelRateLimiter;
import ru.ludwigandreas.notification.service.queue.DeliveryBackoffCalculator;
import ru.ludwigandreas.notification.service.queue.DeliveryClaimService;
import ru.ludwigandreas.notification.service.queue.DeliveryDispatchService;
import ru.ludwigandreas.notification.service.queue.DeliveryOutcomeRecorder;
import ru.ludwigandreas.notification.service.queue.DeliveryPollerScheduler;
import ru.ludwigandreas.notification.service.queue.InstanceIdentity;
import ru.ludwigandreas.notification.service.queue.QueueDepthReader;
import ru.ludwigandreas.notification.service.queue.QueueMetricsScheduler;
import ru.ludwigandreas.notification.service.queue.StaleLeaseReclaimScheduler;
import ru.ludwigandreas.notification.service.retention.RetentionScheduler;
import ru.ludwigandreas.notification.service.retention.RetentionService;
import ru.ludwigandreas.notification.service.template.TemplateRenderer;
import ru.ludwigandreas.notification.service.template.TemplateRevisionService;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.settings.NotificationRuntimeProperties;

/**
 * Wires the queue: its pollers, its lock, its rate limiter, and the consistency checks that decide
 * whether this deployment is allowed to start at all.
 *
 * <p>The schedulers are registered as plain beans rather than annotated {@code @Component}s for two
 * reasons. They need values from {@link NotificationProperties} that only exist as bound
 * {@link Duration}s, which {@code @Scheduled} cannot take. And each holds a {@code ScheduledFuture} -
 * mutable state, which the architecture rules forbid on a {@code @Component} and rightly so, since a
 * component with mutable state is shared across every concurrent request.
 *
 * <p>Every scheduler drives itself from an injected {@link TaskScheduler}, so this service works
 * without the application enabling {@code @EnableScheduling} - the same arrangement the outbox module
 * uses, and for the same reason: a starter that requires an annotation on the consuming application
 * is not plug-and-play.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NotificationProperties.class, NotificationRuntimeProperties.class})
public class NotificationQueueConfig {

    /** Threads for the pollers, the sweeper, the gauges, the digest and the retention purge. */
    private static final int SCHEDULER_POOL_SIZE = 4;

    /**
     * How long the shutdown waits for a scheduled task to finish before giving up on the pool.
     *
     * <p>Longer than a poll cycle and shorter than a pod's usual termination grace period, so an
     * orderly stop completes and a stuck one does not hold the pod open past its deadline.
     */
    private static final Duration SCHEDULER_SHUTDOWN_GRACE = Duration.ofSeconds(30);

    /**
     * This instance's identity, recorded in delivery leases and in distributed locks.
     *
     * <p>One value shared by both, so an operator looking at a stranded lease and a held lock can see
     * they belong to the same pod. Resolved once at startup rather than per call: a value that
     * changed between the claim and the release would make the shutdown drain release nothing.
     */
    @Bean
    public String notificationInstanceId(NotificationProperties properties) {
        String instanceId = InstanceIdentity.resolve(properties.getInstanceId());
        log.info("Notification instance identity: {}", instanceId);
        return instanceId;
    }

    /**
     * Dedicated scheduler, so queue work never competes with anything else for threads.
     *
     * <p>Sized to the number of scheduled jobs rather than to the load: each job is a loop that
     * dispatches a batch and returns, and a pool smaller than the job count would let a slow retention
     * purge delay the delivery poller - which is the one job whose latency anybody notices.
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "notificationTaskScheduler")
    public TaskScheduler notificationTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(SCHEDULER_POOL_SIZE);
        scheduler.setThreadNamePrefix("notification-sched-");
        // Lets the context finish stopping the pollers - which cancel their own futures - before the
        // pool itself goes away, so a cancelled task is never rejected during shutdown.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds((int) SCHEDULER_SHUTDOWN_GRACE.toSeconds());
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public DeliveryBackoffCalculator deliveryBackoffCalculator(NotificationProperties properties) {
        return new DeliveryBackoffCalculator(properties.getRetry());
    }

    @Bean
    public ChannelRegistry channelRegistry(java.util.List<NotificationChannel> channels) {
        log.info("Registered notification channels: {}",
                channels.stream().map(NotificationChannel::name).toList());
        return new ChannelRegistry(channels);
    }

    /**
     * The hot-reloadable overlay: channel on/off and rate limits, changeable without a redeploy.
     *
     * <p>Created through the hot-reload module's factory rather than bound once as a
     * {@code @ConfigurationProperties} bean, which is the whole point - the factory re-binds it when a
     * watched file or a Vault secret changes, and keeps serving the last valid value if the new one
     * fails validation. A malformed edit during an incident leaves the channels as they were rather
     * than taking them down a second way.
     */
    @Bean
    public RefreshableConfig<NotificationRuntimeProperties> notificationRuntimeConfig(
            HotReloadTypedConfigFactory factory) {
        return factory.create("ludwig.notification.runtime", NotificationRuntimeProperties.class);
    }

    @Bean
    public ChannelRuntime channelRuntime(NotificationProperties properties,
                                         RefreshableConfig<NotificationRuntimeProperties> runtime) {
        return new ChannelRuntime(properties, runtime);
    }

    @Bean
    public DistributedLock distributedLock(LockLeaseService leases, NotificationMetrics metrics,
                                           String notificationInstanceId) {
        return new PostgresDistributedLock(leases, metrics, notificationInstanceId);
    }

    @Bean
    @SuppressWarnings("checkstyle:ParameterNumber")
    public DeliveryDispatchService deliveryDispatchService(
            DeliveryClaimService claimService,
            DeliveryOutcomeRecorder outcomeRecorder,
            ChannelRateLimiter rateLimiter,
            ChannelRegistry channelRegistry,
            ChannelRuntime channelRuntime,
            TemplateRenderer templateRenderer,
            TemplateRevisionService templateRevisionService,
            SuppressionService suppressionService,
            CorrelationContext correlationContext,
            NotificationProperties properties,
            NotificationMetrics metrics,
            ObjectMapper objectMapper,
            String notificationInstanceId) {
        return new DeliveryDispatchService(claimService, outcomeRecorder, rateLimiter, channelRegistry,
                channelRuntime, templateRenderer, templateRevisionService, suppressionService,
                correlationContext, properties, metrics, objectMapper, notificationInstanceId);
    }

    /**
     * The delivery poller.
     *
     * <p>Its drain timeout is derived from the lease rather than configured separately, because the
     * two answer the same question - how long a cycle may legitimately take - and two independent
     * numbers for one question drift apart. Half the lease leaves room for the shutdown to finish
     * inside the pod's termination grace period.
     */
    @Bean
    @ConditionalOnProperty(prefix = "ludwig.notification.queue", name = "poller-enabled",
            matchIfMissing = true)
    public DeliveryPollerScheduler deliveryPollerScheduler(DeliveryDispatchService dispatchService,
                                                           DeliveryClaimService claimService,
                                                           TaskScheduler notificationTaskScheduler,
                                                           NotificationProperties properties) {
        NotificationProperties.Queue queue = properties.getQueue();
        return new DeliveryPollerScheduler(dispatchService, claimService, notificationTaskScheduler,
                queue.getInitialDelay(), queue.getPollInterval(), queue.getLeaseTimeout().dividedBy(2));
    }

    /**
     * The stale-lease sweeper, which runs whether or not this instance polls.
     *
     * <p>Deliberately not conditional on {@code poller-enabled}: an instance with polling switched off
     * is exactly the sort of instance an operator leaves running to keep the queue tidy, and leases
     * stranded by a crashed poller elsewhere still need recovering.
     */
    @Bean
    public StaleLeaseReclaimScheduler staleLeaseReclaimScheduler(DeliveryClaimService claimService,
                                                                  TaskScheduler notificationTaskScheduler,
                                                                  NotificationMetrics metrics,
                                                                  NotificationProperties properties) {
        NotificationProperties.Queue queue = properties.getQueue();
        return new StaleLeaseReclaimScheduler(claimService, notificationTaskScheduler, metrics,
                queue.getLeaseTimeout(), queue.getReclaimInterval());
    }

    @Bean
    public QueueMetricsScheduler queueMetricsScheduler(QueueDepthReader depthReader,
                                                        TaskScheduler notificationTaskScheduler,
                                                        NotificationMetrics metrics,
                                                        NotificationProperties properties) {
        return new QueueMetricsScheduler(depthReader, notificationTaskScheduler, metrics,
                properties.getQueue().getMetricsInterval());
    }

    @Bean
    @ConditionalOnProperty(prefix = "ludwig.notification.digest", name = "enabled",
            havingValue = "true")
    public DigestScheduler digestScheduler(DistributedLock distributedLock,
                                           DigestCollapseService collapseService,
                                           NotificationDeliveryRepository deliveryRepository,
                                           TaskScheduler notificationTaskScheduler,
                                           NotificationProperties properties) {
        return new DigestScheduler(distributedLock, collapseService, deliveryRepository,
                notificationTaskScheduler, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ludwig.notification.retention", name = "enabled",
            matchIfMissing = true)
    public RetentionScheduler retentionScheduler(DistributedLock distributedLock,
                                                  RetentionService retentionService,
                                                  TaskScheduler notificationTaskScheduler,
                                                  NotificationProperties properties) {
        return new RetentionScheduler(distributedLock, retentionService, notificationTaskScheduler,
                properties);
    }

    /**
     * Refuses to start on a configuration that would double-send or lose work.
     *
     * <p>Every check here describes a relationship between two settings that are individually valid
     * and jointly wrong. Bean Validation cannot express those - it sees one field at a time - and each
     * of them produces a failure that is intermittent, rare, and extremely hard to attribute weeks
     * later. Failing the pod is the cheapest possible way to find out.
     *
     * <p>{@link ObjectProvider} for the environment so this bean has no ordering requirement on it.
     */
    @Bean
    public NotificationConfigurationValidator notificationConfigurationValidator(
            NotificationProperties properties, ObjectProvider<ConfigurableEnvironment> environment) {
        return new NotificationConfigurationValidator(properties, environment.getIfAvailable());
    }
}
