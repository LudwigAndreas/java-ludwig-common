package ru.ludwigandreas.messaging.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import ru.ludwigandreas.messaging.config.MessagingAutoConfiguration;
import ru.ludwigandreas.messaging.config.MessagingRetryTopicAutoConfiguration;
import ru.ludwigandreas.messaging.error.OrderingConflictsWithRetryTopicsException;

/**
 * The non-blocking retry opt-in: off unless asked for twice, and refused when it contradicts ordering.
 *
 * <p>The refusal is asserted through a real context rather than only through the validator, because the
 * property under test is that the <em>application does not start</em>. A validator that threw into a
 * context that started anyway would satisfy a unit test and lose the ordering guarantee in production.
 */
class MessagingRetryTopicAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class,
                    MessagingAutoConfiguration.class, MessagingRetryTopicAutoConfiguration.class));

    @Test
    @DisplayName("blocking retries are the default: no retry-topic configuration is registered")
    void doesNothingByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RetryTopicConfiguration.class);
        });
    }

    /**
     * The module-level switch alone does nothing, and that is the point of it existing separately:
     * {@code @EnableKafkaRetryTopic} changes how every annotated listener in the application is
     * bootstrapped, which no single consumer's property should be able to do to a service that was not
     * expecting it.
     */
    @Test
    @DisplayName("the module switch alone changes no consumer's retries")
    void needsBothSwitches() {
        runner.withPropertyValues("ludwig.messaging.retry-topics.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("a consumer that opts in while relying on ordering fails the context")
    void refusesTheOptInForAnOrderedConsumer() {
        runner.withPropertyValues(
                        "ludwig.messaging.retry-topics.enabled=true",
                        "ludwig.messaging.consumers.feed.retry.non-blocking=true",
                        "ludwig.messaging.consumers.feed.topics[0]=search.index.feed")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasRootCauseInstanceOf(OrderingConflictsWithRetryTopicsException.class));
    }

    @Test
    @DisplayName("a consumer that declares it gives up ordering gets a retry-topic configuration")
    void acceptsTheOptInForAnUnorderedConsumer() {
        runner.withPropertyValues(
                        "ludwig.messaging.retry-topics.enabled=true",
                        "ludwig.messaging.consumers.feed.ordered=false",
                        "ludwig.messaging.consumers.feed.retry.non-blocking=true",
                        "ludwig.messaging.consumers.feed.retry.max-attempts=5",
                        "ludwig.messaging.consumers.feed.topics[0]=search.index.feed")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RetryTopicConfiguration.class);
                    assertThat(context.getBean(RetryTopicConfiguration.class)
                            .hasConfigurationForTopics(new String[] {"search.index.feed"})).isTrue();
                });
    }

    @Test
    @DisplayName("the opt-in without topics is refused, because it would silently do nothing")
    void refusesTheOptInWithoutTopics() {
        runner.withPropertyValues(
                        "ludwig.messaging.retry-topics.enabled=true",
                        "ludwig.messaging.consumers.feed.ordered=false",
                        "ludwig.messaging.consumers.feed.retry.non-blocking=true")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage(rootCauseAbout("feed")));
    }

    private static String rootCauseAbout(String consumer) {
        return "Consumer '" + consumer + "' opts into non-blocking retries but lists no topics. Set"
                + " ludwig.messaging.consumers." + consumer + ".topics, because a retry-topic configuration"
                + " is declared independently of any listener - without them it applies to no topic and the"
                + " opt-in silently does nothing while appearing to have worked.";
    }
}
