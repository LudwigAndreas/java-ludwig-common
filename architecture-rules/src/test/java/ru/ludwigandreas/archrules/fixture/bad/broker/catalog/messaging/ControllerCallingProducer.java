package ru.ludwigandreas.archrules.fixture.bad.broker.catalog.messaging;

import org.springframework.stereotype.Component;

import ru.ludwigandreas.archrules.fixture.bad.broker.catalog.web.OrderController;

/** Violation: messaging reaching into the web layer. */
@Component
public class ControllerCallingProducer {

    private final OrderController controller;

    public ControllerCallingProducer(OrderController controller) {
        this.controller = controller;
    }

    public String publish() {
        return controller.status();
    }
}
