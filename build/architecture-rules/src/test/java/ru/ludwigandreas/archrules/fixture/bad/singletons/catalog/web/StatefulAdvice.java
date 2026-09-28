package ru.ludwigandreas.archrules.fixture.bad.singletons.catalog.web;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Violation: advice classes are singletons too, and this one accumulates state across requests. */
@RestControllerAdvice
public class StatefulAdvice {

    private String lastMessage;

    public String handle(RuntimeException exception) {
        lastMessage = exception.getMessage();
        return lastMessage;
    }
}
