package ru.ludwigandreas.archrules.fixture.bad.properties.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Violation: a missing or misspelled ConfigMap value binds silently and fails much later. */
@ConfigurationProperties("catalog")
public class UnvalidatedProperties {

    private final String endpoint = null;

    public String endpoint() {
        return endpoint;
    }
}
