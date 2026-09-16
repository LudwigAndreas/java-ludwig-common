package ru.ludwigandreas.archrules.fixture.bad.tests.catalog.service;

import org.junit.jupiter.api.Assertions;
import org.springframework.stereotype.Service;

/** Violation: a test framework used as a runtime guard clause. */
@Service
public class AssertingService {

    public void check(String value) {
        Assertions.assertNotNull(value);
    }
}
