package ru.ludwigandreas.archrules.fixture.bad.exceptions.catalog.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Violation: the message is acknowledged as processed even though handling it failed. */
@Component
public class SwallowingListener {

    @KafkaListener(topics = "orders")
    public void on(String payload) {
        try {
            handle(payload);
        } catch (Exception failure) {
            // swallowed: the container will never retry or dead-letter this message
        }
    }

    private void handle(String payload) throws Exception {
        throw new Exception(payload);
    }
}
