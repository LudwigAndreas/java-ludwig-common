package ru.ludwigandreas.asyncapi.config;

import io.github.springwolf.core.asyncapi.AsyncApiCustomizer;
import io.github.springwolf.core.asyncapi.components.ComponentsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.asyncapi.document.PlatformAsyncApiCustomizer;
import ru.ludwigandreas.messaging.config.MessagingAutoConfiguration;
import ru.ludwigandreas.messaging.consumer.ListenerContainerFactoryBuilder;
import ru.ludwigandreas.messaging.settings.MessagingProperties;
import ru.ludwigandreas.outbox.api.OutboxEventTypes;
import ru.ludwigandreas.outbox.config.OutboxAutoConfiguration;
import ru.ludwigandreas.outbox.config.OutboxProperties;

/** The platform's half of the AsyncAPI document; Springwolf's own autoconfiguration does the scanning. */
@AutoConfiguration(after = {MessagingAutoConfiguration.class, OutboxAutoConfiguration.class})
@ConditionalOnClass(AsyncApiCustomizer.class)
@ConditionalOnBean(ListenerContainerFactoryBuilder.class)
public class AsyncApiAutoConfiguration {

    /**
     * The customizer.
     *
     * @param beans      the bean factory
     * @param builder    messaging's builder
     * @param messaging  messaging's configuration
     * @param outbox     the outbox configuration, when the outbox is present
     * @param eventTypes every declared outbox event type
     * @param components Springwolf's schema service
     * @return the customizer
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a bean method assembling one collaborator from the context.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    public PlatformAsyncApiCustomizer ludwigPlatformAsyncApiCustomizer(
            ConfigurableListableBeanFactory beans, ListenerContainerFactoryBuilder builder,
            MessagingProperties messaging, ObjectProvider<OutboxProperties> outbox,
            ObjectProvider<OutboxEventTypes> eventTypes, ComponentsService components) {
        return new PlatformAsyncApiCustomizer(beans, builder, messaging, outbox.getIfAvailable(OutboxProperties::new),
                OutboxEventTypes.merge(eventTypes.orderedStream().toList()), components);
    }
}
