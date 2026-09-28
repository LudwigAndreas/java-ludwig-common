package ru.ludwigandreas.archrules.fixture.bad.contracts.catalog.messaging;

/** The contract every publisher is supposed to implement. */
public interface EventPublisher {

    void publish(String topic, Object payload);
}
