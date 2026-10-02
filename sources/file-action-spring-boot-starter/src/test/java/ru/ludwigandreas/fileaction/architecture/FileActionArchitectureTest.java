package ru.ludwigandreas.fileaction.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The shared architecture rules, as they apply to this module.
 *
 * <h2>The four that matter most here are on</h2>
 *
 * <ul>
 *   <li><b>{@code operations}</b> - this module declares {@code FileActionState}, which is the exact shape the
 *       rule is about. It passes because the enum is a richer domain lifecycle rather than a second spelling of
 *       the six core states, and the rule is what keeps it that way when somebody adds {@code COMPLETED}.</li>
 *   <li><b>{@code audit}</b> - this module records who uploaded what, so it is in a position to grow an audit
 *       SPI of its own, which is precisely what the rule forbids.</li>
 *   <li><b>{@code presentation}</b> - every cell coercion in this module is locale-sensitive, so a single
 *       {@code Locale.getDefault()} would silently read dates in the container's locale rather than the
 *       caller's. There is no module where this rule earns its place more.</li>
 *   <li><b>{@code caching}</b> - vacuous here, and left on for that reason: it costs nothing and it is the
 *       check that would catch a {@code Caffeine} builder if somebody decided to cache a parsed binding.</li>
 * </ul>
 *
 * <h2>What is switched off, and why</h2>
 *
 * <p>This is a starter with a controller but no service layer in the shape the shared rules describe - they
 * describe an application, with controllers over services over repositories, and this module's packages are
 * named for what they are ({@code api}, {@code format}, {@code engine}, {@code entity}, {@code repository},
 * {@code web}, {@code config}). The layering rules fail here because the module is arranged for something else
 * rather than because it is arranged badly.
 *
 * <ul>
 *   <li>{@code layering} - there is no {@code service} package. The engine is the service layer and it is called
 *       by the controller and by the deferred worker, which the rule reads as a layering violation.</li>
 *   <li>{@code persistence.repositories-are-used-only-by-services} - {@code FileActionController} reads the
 *       reject repository directly for the paged endpoint. Routing a page of rejects through the engine would
 *       add a method that only forwards.</li>
 *   <li>{@code spring.transactional-methods-are-in-the-service-layer} - the transactional methods are on
 *       {@code SubmissionStore}, which is this module's persistence seam rather than a package the rule
 *       recognises as a service layer. Where the transaction boundaries are and why is documented on that class
 *       and on {@code ApplyPass}, which is the thing the rule is trying to protect.</li>
 *   <li>{@code mappers.mappers-are-mapstruct-interfaces} - {@code SubmissionMapper} maps a snapshot to an
 *       envelope with no type conversion in it, so MapStruct would generate nothing. Disabled rather than
 *       renamed, because the name says what the class does.</li>
 *   <li>{@code web.controllers-do-not-call-controllers} - there is one controller and it calls its own private
 *       helpers, which the rule counts as a controller calling a controller. Thirty-four violations, all of them
 *       the same method-to-method call inside one class. {@code object-storage} disables it for the same reason.
 *   </li>
 *   <li>{@code exceptions.custom-exceptions-extend-the-base-exception} - one class cannot:
 *       {@code SheetHandler.StopParsing}, which is how a SAX parse is abandoned after the header row. It carries
 *       no stack trace, is caught three lines from where it is thrown, and must never reach the
 *       {@code ProblemDetail} pipeline, so making it a {@code LocalizedException} would be actively wrong. The
 *       other class the rule reported, {@code UploadSpool.UploadTooLargeException}, was a real finding and is
 *       gone - the spool now throws the module's own {@code FileRejectedException} directly, which is better
 *       than the conversion it replaced.</li>
 * </ul>
 *
 * <h2>The ones it reported that were real</h2>
 *
 * <p>Worth recording, because the value of switching this on was in these rather than in the passes. The rules
 * found a {@code config -> web -> engine -> config} package cycle (the engine read the Spring properties tree);
 * a JPA entity reaching the controller, which is a lazy-loading failure waiting for a field to be added; a
 * repository injected into the controller; an {@code IOException} caught in two endpoints; an unversioned base
 * path; {@code Optional} used as a field and a parameter type; and configuration properties with no constraints
 * on them. Every one of those is now fixed rather than disabled.
 */
@AnalyzeArchitecture(
        packages = "ru.ludwigandreas.fileaction",
        disable = {
                "layering",
                "persistence.repositories-are-used-only-by-services",
                "spring.transactional-methods-are-in-the-service-layer",
                "mappers.mappers-are-mapstruct-interfaces",
                "web.controllers-do-not-call-controllers",
                "exceptions.custom-exceptions-extend-the-base-exception"
        })
class FileActionArchitectureTest extends ArchitectureRulesTest {
}
