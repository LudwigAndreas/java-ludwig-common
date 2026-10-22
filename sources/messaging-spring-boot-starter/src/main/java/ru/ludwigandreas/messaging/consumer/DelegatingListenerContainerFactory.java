package ru.ludwigandreas.messaging.consumer;

import org.springframework.kafka.config.KafkaListenerContainerFactory;

/**
 * A listener container factory standing in for another one - the DB-log transport's routing factory is
 * one. {@link ListenerContainerFactoryBuilder#consumerNameOf} looks through it to the factory the builder
 * built, so a wrapped factory keeps its consumer name.
 */
public interface DelegatingListenerContainerFactory {

    /**
     * The factory this one stands in for.
     *
     * @return the wrapped factory
     */
    KafkaListenerContainerFactory<?> delegate();
}
