package ru.ludwigandreas.cache.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * This module depends on nothing else in this repository, and that is checked rather than assumed.
 *
 * <h2>Why it is load-bearing</h2>
 *
 * <p>{@code security-spring-boot-starter} sits near the bottom of the reactor - identity-projection,
 * user-settings, web-core and every service depend on it - and it is one of the two modules whose
 * hand-written cache this one replaces. If this module ever acquired a dependency on {@code audit-core},
 * {@code web-core} or {@code security} itself, security could not depend on it and the consolidation would
 * have to be undone.
 *
 * <p>The failure mode is quiet and easy to reach: somebody wants to audit an eviction, or to render a cache
 * error as a {@code ProblemDetail}, and both are one import away and both look reasonable. The reactor would
 * then refuse to build with a cycle, at which point the cheapest fix looks like moving the cache module - which
 * is how a deliberate constraint becomes an accident. A named test that says why is what makes the right fix
 * obvious instead.
 *
 * <p>Main classes only. The tests legitimately use {@code test-support} for the digest-pinned Redis container,
 * and a test-scope dependency closes no cycle.
 */
class ModuleIndependenceTest {

    private static final String OWN_PACKAGES = "ru.ludwigandreas.cache..";

    private final JavaClasses mainClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("ru.ludwigandreas.cache");

    @Test
    @DisplayName("nothing here depends on another module of this platform")
    void noInRepoDependencies() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage(OWN_PACKAGES)
                .should().dependOnClassesThat()
                .resideOutsideOfPackages(OWN_PACKAGES, "java..", "javax..", "jakarta..",
                        "org.springframework..", "com.github.benmanes.caffeine..",
                        "com.fasterxml.jackson..", "io.micrometer..", "org.slf4j..", "lombok..")
                .as("cache-spring-boot-starter depends on no other module of this platform");

        rule.check(mainClasses);
    }

    /**
     * The API is usable without Redis on the classpath.
     *
     * <p>{@code spring-data-redis} is an optional dependency, so every type a module touches to declare and use
     * a cache has to resolve without it. If {@code api} reached a Redis type, a local-only service would fail
     * with a {@code NoClassDefFoundError} at the first cache lookup rather than at startup, which is the worst
     * of both.
     */
    @Test
    @DisplayName("the public API does not reach spring-data-redis, which is optional")
    void apiIsFreeOfRedis() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAnyPackage("ru.ludwigandreas.cache.api..", "ru.ludwigandreas.cache.core..",
                        "ru.ludwigandreas.cache.tx..", "ru.ludwigandreas.cache.metrics..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.data.redis..")
                .as("only the shared package and its auto-configuration touch Redis");

        rule.check(mainClasses);
    }
}
