package ru.ludwigandreas.archrules.fixture.bad.environment.catalog.service;

import org.springframework.stereotype.Service;

/** Violation: configuration read in business code instead of injected. */
@Service
public class EnvironmentReadingService {

    public String region() {
        String fromEnvironment = System.getenv("AWS_REGION");
        return fromEnvironment != null ? fromEnvironment : System.getProperty("aws.region", "eu-central-1");
    }
}
