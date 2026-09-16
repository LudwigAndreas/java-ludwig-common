package ru.ludwigandreas.archrules.fixture.bad.singletons.catalog.config;

import org.springframework.context.annotation.Configuration;

/** Violation: a @Configuration class is as singleton-scoped as a @Service. */
@Configuration
public class StatefulConfiguration {

    private String lastProfile;

    public void remember(String profile) {
        this.lastProfile = profile;
    }
}
