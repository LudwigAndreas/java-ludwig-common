package ru.ludwigandreas.testsupport.local.integration.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The minimal service this module's own tests launch.
 *
 * <p>No service consumes this module in the change that adds it, so the module carries the smallest
 * application that has everything the launcher handles: one {@code @LudwigRestClient}, one table
 * created by a migration, and one topic with a listener. It has a real {@code main} because the
 * launcher's whole claim is that it starts a service through its own entry point.
 */
@SpringBootApplication
public class SampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}
