package ru.ludwigandreas.outbox.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.outbox.config.OutboxRetentionAutoConfiguration;
import ru.ludwigandreas.outbox.metrics.NoopOutboxMetrics;
import ru.ludwigandreas.outbox.metrics.OutboxMetrics;
import ru.ludwigandreas.outbox.repository.OutboxMessageRepository;
import ru.ludwigandreas.outbox.scheduler.OutboxRetentionPurge;

/** The retention purge is on by default, needs a lock, and refuses settings that would defeat idempotency. */
class OutboxRetentionAutoConfigurationTest {

    private static TaskScheduler scheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        return scheduler;
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OutboxRetentionAutoConfiguration.class))
            .withBean(OutboxMessageRepository.class, () -> Mockito.mock(OutboxMessageRepository.class))
            .withBean(OutboxMetrics.class, NoopOutboxMetrics::new)
            .withBean(AuditSink.class, () -> event -> { })
            .withBean("outboxTaskScheduler", TaskScheduler.class, OutboxRetentionAutoConfigurationTest::scheduler);

    private final ApplicationContextRunner withLock = runner
            .withBean(RunLock.class, () -> Mockito.mock(RunLock.class));

    @Test
    @DisplayName("with a lock and no configuration, the purge is registered with the defaults")
    void onByDefault() {
        withLock.run(context -> assertThat(context).hasNotFailed().hasSingleBean(OutboxRetentionPurge.class)
                .doesNotHaveBean("outboxRetentionInactiveWarning"));
    }

    @Test
    @DisplayName("disabled, no purge is registered")
    void disabled() {
        withLock.withPropertyValues("ludwig.outbox.retention.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(OutboxRetentionPurge.class));
    }

    @Test
    @DisplayName("without a lock, no purge is registered and a warning is, rather than an unlocked purge per replica")
    void withoutALock() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(OutboxRetentionPurge.class)
                .hasBean("outboxRetentionInactiveWarning")
                .getBean("outboxRetentionInactiveWarning").isInstanceOf(SmartInitializingSingleton.class));
    }

    @Test
    @DisplayName("a published period under a day fails startup, naming the property")
    void publishedTooShort() {
        withLock.withPropertyValues("ludwig.outbox.retention.published=PT1H")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("ludwig.outbox.retention.published"));
    }

    @Test
    @DisplayName("an invalid period is not refused when retention is disabled")
    void invalidButDisabled() {
        withLock.withPropertyValues("ludwig.outbox.retention.enabled=false", "ludwig.outbox.retention.published=PT1H")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
