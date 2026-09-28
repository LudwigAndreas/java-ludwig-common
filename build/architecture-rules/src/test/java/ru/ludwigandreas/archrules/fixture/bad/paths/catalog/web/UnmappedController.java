package ru.ludwigandreas.archrules.fixture.bad.paths.catalog.web;

import org.springframework.web.bind.annotation.RestController;

/** Violation: no base path at all, so the gateway cannot route it by prefix. */
@RestController
public class UnmappedController {

    public String get() {
        return "ok";
    }
}
