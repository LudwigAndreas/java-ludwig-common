package ru.ludwigandreas.archrules.fixture.bad.paths.catalog.web;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Violation: a base path outside the organisation's versioned shape. */
@RestController
@RequestMapping("/orders")
public class UnversionedController {

    public String get() {
        return "ok";
    }
}
