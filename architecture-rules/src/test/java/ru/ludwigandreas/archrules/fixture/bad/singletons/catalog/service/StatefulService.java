package ru.ludwigandreas.archrules.fixture.bad.singletons.catalog.service;

import org.springframework.stereotype.Service;

/** Violation: a counter shared by every concurrent request the bean serves. */
@Service
public class StatefulService {

    private int handled;

    public int record() {
        return ++handled;
    }
}
