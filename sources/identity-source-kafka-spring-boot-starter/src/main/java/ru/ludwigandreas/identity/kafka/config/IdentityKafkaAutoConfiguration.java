package ru.ludwigandreas.identity.kafka.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import ru.ludwigandreas.identity.config.IdentityProjectionAutoConfiguration;
import ru.ludwigandreas.identity.feed.IdentityProjection;
import ru.ludwigandreas.identity.kafka.IdentityKafkaFeed;
import ru.ludwigandreas.identity.kafka.IdentityKafkaSubscriptions;
import ru.ludwigandreas.identity.kafka.IdentityUserEventListener;
import ru.ludwigandreas.identity.kafka.IdentityUserEventParser;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;

/**
 * The Kafka feed: one listener over every subscribed topic, on the platform's consumer, feeding the projection.
 *
 * <p>Nothing is registered without a subscription, so the starter on the classpath of a service that does not
 * subscribe changes nothing. Every subscription is also registered as an {@code IdentityFeed} bean, which is how the
 * projection validates that its source is declared and lists it under {@code GET /admin/v1/identity/sources}.
 */
/*
 * After the messaging auto-configuration, by name. The container factory is behind
 * @ConditionalOnBean(ListenerContainerFactoryBuilder.class), and a bean condition sees only what has been registered
 * so far; ru.ludwigandreas.identity sorts before ru.ludwigandreas.messaging, so without this the condition would be
 * evaluated first and answer false. IdentityKafkaOrderingTest holds the order.
 */
@AutoConfiguration(after = IdentityProjectionAutoConfiguration.class,
        afterName = "ru.ludwigandreas.messaging.config.MessagingAutoConfiguration")
@ConditionalOnClass(KafkaListener.class)
@ConditionalOnProperty(prefix = IdentityKafkaProperties.PREFIX, name = "enabled", matchIfMissing = true)
@Conditional(OnIdentitySubscriptions.class)
@EnableConfigurationProperties(IdentityKafkaProperties.class)
public class IdentityKafkaAutoConfiguration {

    /**
     * The validated subscriptions; named, because the listener's annotation reads its topics and group from it.
     *
     * @param properties {@code ludwig.identity.kafka}
     * @return the subscriptions
     */
    @Bean(name = "identityKafkaSubscriptions")
    public IdentityKafkaSubscriptions identityKafkaSubscriptions(IdentityKafkaProperties properties) {
        return new IdentityKafkaSubscriptions(properties);
    }

    /**
     * The identity consumer's container, from the platform's builder: retry with growing gaps, then the dead-letter
     * topic {@code <topic>.dlt}; a text payload, because the listener parses its own JSON.
     *
     * @param builder       the platform's container factory builder
     * @param subscriptions the subscribed topics, for the silence signal
     * @return the factory
     */
    @Bean(name = IdentityKafkaFeed.CONTAINER_FACTORY)
    @ConditionalOnMissingBean(name = IdentityKafkaFeed.CONTAINER_FACTORY)
    @ConditionalOnBean(ListenerContainerFactoryBuilder.class)
    public ConcurrentKafkaListenerContainerFactory<String, String> identityKafkaListenerContainerFactory(
            ListenerContainerFactoryBuilder builder, IdentityKafkaSubscriptions subscriptions) {
        return builder.forTextPayload(IdentityKafkaFeed.CONSUMER_NAME, subscriptions.topics());
    }

    /**
     * The listener. A private {@link ObjectMapper} with {@link JavaTimeModule} when the application has none, because
     * {@code occurredAt} is the ordering key and must parse.
     *
     * @param projection    the projection
     * @param subscriptions which topic feeds which source
     * @param objectMapper  the application's mapper, if any
     * @return the listener
     */
    @Bean
    @ConditionalOnMissingBean(IdentityUserEventListener.class)
    public IdentityUserEventListener identityUserEventListener(IdentityProjection projection,
                                                               IdentityKafkaSubscriptions subscriptions,
                                                               ObjectProvider<ObjectMapper> objectMapper) {
        return new IdentityUserEventListener(projection, subscriptions, new IdentityUserEventParser(
                objectMapper.getIfAvailable(() -> new ObjectMapper().registerModule(new JavaTimeModule()))));
    }

    /**
     * One {@code IdentityFeed} bean per subscription, named {@code identityKafkaFeed.<source>}.
     *
     * @return the registrar
     */
    @Bean
    public static BeanDefinitionRegistryPostProcessor identityKafkaFeedRegistrar() {
        return new FeedRegistrar();
    }

    static final class FeedRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            String groupId = environment.getProperty(IdentityKafkaProperties.PREFIX + ".group-id", "");
            OnIdentitySubscriptions.bind(environment).forEach((source, subscription) -> {
                RootBeanDefinition feed = new RootBeanDefinition(IdentityKafkaFeed.class,
                        () -> new IdentityKafkaFeed(source, subscription.getTopic(), groupId));
                registry.registerBeanDefinition("identityKafkaFeed." + source, feed);
            });
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            // Everything is registered above.
        }
    }
}
