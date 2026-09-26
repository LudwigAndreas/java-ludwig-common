package ru.ludwigandreas.audit.store.integration;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A minimal application for this module's integration tests.
 *
 * <p>This module is a library and has no application class of its own, but its autoconfiguration can only be
 * exercised the way a consumer will actually get it: through the real {@code AutoConfiguration.imports}
 * files, against a real context and a real database. A sliced test with hand-wired beans would pass while
 * the wiring a service depends on was broken.
 */
@SpringBootApplication
public class AuditTestApplication {
}
