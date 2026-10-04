package ru.ludwigandreas.pat.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The shared architecture rules, as they apply to this module.
 *
 * <h2>The five that matter most here are on</h2>
 *
 * <ul>
 *   <li><b>{@code credentials}</b> - the group written for this change. This is the module whose types the
 *       rules name, so it is the one place they are not vacuous: the reveal fence, the carrier containment
 *       and the single attenuation path all have real subjects here. {@code CredentialRulesTest} in
 *       {@code architecture-rules} can only prove those three do not <em>misfire</em>, because the fixtures
 *       there deliberately do not depend on this module; this run is where they are proven to fire.</li>
 *   <li><b>{@code audit}</b> - this module records a credential's whole lifecycle, so it is in a strong
 *       position to grow an audit SPI of its own, which is exactly what the rule forbids. It declares nine
 *       typed event records and no sink.</li>
 *   <li><b>{@code caching}</b> - this module caches verifications. The rule is what would catch a Caffeine
 *       builder appearing here the day somebody decides the registry is too much ceremony.</li>
 *   <li><b>{@code persistence}</b> - there is a real entity and a real repository, and the rule that
 *       repositories are reached through a service is the one that keeps the controller off the query
 *       repository.</li>
 *   <li><b>{@code presentation}</b> - every expiry, overlap and retention decision here is a comparison
 *       against now, and a single {@code Clock.systemDefaultZone()} would make all of them depend on the
 *       container's timezone. The clock is injected precisely so this rule has nothing to find.</li>
 * </ul>
 *
 * <h2>What is switched off, and why</h2>
 *
 * <p>Most of the shared library describes a <em>service</em> - controllers over a service layer over
 * repositories. This is a starter, and the departures below are shape rather than sloppiness. The same set
 * {@code file-action-spring-boot-starter} disables, for the same reasons.
 *
 * <ul>
 *   <li>{@code layering} - the packages are named for what they are ({@code entity}, {@code repository},
 *       {@code service}, {@code web}, {@code exchange}, {@code cache}, {@code metrics}, {@code problem},
 *       {@code config}). {@code exchange} is a second web surface beside {@code web}, which the rule reads
 *       as a layering violation rather than as two endpoints with different audiences - one for a human
 *       managing their tokens, one for the edge.</li>
 *   <li>{@code persistence.repositories-are-used-only-by-services} - {@code PatVerifier} reads the query
 *       repository directly, and it is not in the {@code service} package because it is not part of the
 *       lifecycle: it is the hot path. Routing verification through {@code PatService} would add a method
 *       that only forwards, on the one call that must stay as short as possible.</li>
 *   <li>{@code spring.transactional-methods-are-in-the-service-layer} - they are, but
 *       {@code PatUsageTracker}'s asynchronous write is not transactional and the rule flags its absence
 *       rather than its presence. That write is deliberately outside any transaction: it is best-effort,
 *       and enlisting it would couple a telemetry failure to the exchange it must not be able to break.</li>
 *   <li>{@code mappers.mappers-are-mapstruct-interfaces} - there is no mapper. {@code PatResponse.of} is a
 *       static factory, which is what the three existing starters with a web surface use, and the
 *       interesting part of that mapping is what it deliberately does not copy. Disabled rather than
 *       renamed, because nothing here is called a mapper.</li>
 *   <li>{@code web.controllers-do-not-call-controllers} - both controllers call their own private helpers,
 *       which the rule counts as a controller calling a controller. {@code object-storage} and
 *       {@code file-action} disable it for the same reason.</li>
 *   <li>{@code rest-paths.controllers-declare-a-versioned-base-path} - the only one disabled for a reason
 *       that is about the <em>outside world</em> rather than this module's shape. The exchange endpoint is
 *       mounted at {@code /oauth2/token} because that is where RFC 8693 token exchange lives and where an
 *       edge proxy's token-exchange support looks for it; versioning it to {@code /api/v1/oauth2/token}
 *       would be versioning somebody else's standard. The management controller's path <em>is</em>
 *       {@code /api/v1/personal-access-tokens}, but both mappings are property placeholders
 *       ({@code ${ludwig.pat.web.base-path:...}}) and the rule reads the unresolved literal - so it cannot
 *       see the compliant one either. Disabling it is therefore the honest option; narrowing the rule to
 *       resolve placeholders would be a change to the shared library for one module's benefit.</li>
 * </ul>
 *
 * <h2>What switching this on actually found</h2>
 *
 * <p>Worth recording, because the value was in the five findings rather than in the fifty-six passes. The
 * run reported a {@code config -> problem -> web -> service -> config} package cycle (the service read the
 * Spring properties tree, exactly as {@code file-ingest} had); three exceptions not extending
 * {@code LocalizedException}; a JPA entity reaching the controller; and configuration properties with no
 * constraints on them.
 *
 * <p>All four were fixed rather than disabled, and two of the fixes removed code: basing the exceptions on
 * {@code LocalizedException} deleted an entire {@code ProblemDetail} mapper class and three bean
 * definitions, because {@code web-core} renders that base type natively - and that deletion also removed
 * the {@code problem -> web} edge that was part of the cycle. One finding, three causes resolved.
 */
@AnalyzeArchitecture(
        packages = "ru.ludwigandreas.pat",
        disable = {
                "layering",
                "persistence.repositories-are-used-only-by-services",
                "spring.transactional-methods-are-in-the-service-layer",
                "mappers.mappers-are-mapstruct-interfaces",
                "web.controllers-do-not-call-controllers",
                "rest-paths.controllers-declare-a-versioned-base-path"
        })
class PatArchitectureRulesTest extends ArchitectureRulesTest {
}
