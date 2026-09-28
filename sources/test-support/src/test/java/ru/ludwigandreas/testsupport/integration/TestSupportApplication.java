package ru.ludwigandreas.testsupport.integration;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The minimal application the fixtures' own integration test boots.
 *
 * <p>It exists because the composed annotations in {@code ru.ludwigandreas.testsupport.junit} are the one
 * part of this module that cannot be proven by a unit test: whether {@code @ServiceConnection} actually
 * resolves against a digest-pinned image, and whether the meta-annotation carries through, are facts about
 * Spring's own wiring. A module that shipped those annotations without ever starting a context with them
 * would be shipping three untested entry points.
 */
@SpringBootApplication
class TestSupportApplication {
}
