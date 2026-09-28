package ru.ludwigandreas.webcore.integration;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A minimal service that adds nothing but a controller: no advice, no {@code MessageSource}, no
 * locale resolver, no validator wiring. Everything the integration test asserts therefore comes from
 * the starter's autoconfiguration, which is the claim being tested - that a service becomes a
 * properly localized REST API by adding the dependency.
 */
@SpringBootApplication
public class TestApplication {
}
