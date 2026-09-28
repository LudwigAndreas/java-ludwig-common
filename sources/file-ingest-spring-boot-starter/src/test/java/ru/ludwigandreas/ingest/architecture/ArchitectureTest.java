package ru.ludwigandreas.ingest.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The shared rule library, applied to this module.
 *
 * <h2>What is switched off, and why</h2>
 *
 * <p>Most of the library describes a <em>service</em> - controllers over a service layer over
 * repositories. This is a library whose only inbound surface is an actuator endpoint, and its
 * packages are named for what they do ({@code engine}, {@code bulk}, {@code api}) rather than for a
 * layer. Those rules fail here because the module is arranged for something else, not because it is
 * arranged badly.
 *
 * <ul>
 *   <li>{@code layering}, {@code persistence.repositories-are-used-only-by-services},
 *       {@code spring.transactional-methods-are-in-the-service-layer} - there is no service layer.
 *       The transactional unit is {@code BatchCommitter}, which is the thing a service would
 *       otherwise call, and it is deliberately the only one.</li>
 *   <li>{@code web.controllers-do-not-call-controllers}, {@code rest-paths} - there is no controller
 *       and no REST path in this module at all.</li>
 *   <li>{@code mappers.mappers-are-mapstruct-interfaces} - there is no mapper here. The module has no
 *       web surface, so the three model layers and MapStruct do not apply to it; records go from the
 *       author's type straight to staging columns through {@code RecordApplier}.</li>
 *   <li>{@code persistence.persistence-context-is-confined} - the one rule this module replaces rather
 *       than merely switches off. {@code ru.ludwigandreas.ingest.bulk} touches JDBC directly, under
 *       the carve-out that package documents and that {@code CLAUDE.md} records: Postgres
 *       {@code COPY} and a set-based upsert cannot be written in QueryDSL, and the alternative is to
 *       read four million staged rows into this process. The shared rule says "persistence belongs in
 *       the persistence layer", which is the right rule for a service and cannot express "JDBC belongs
 *       in exactly one package of this library". {@link SqlConfinementTest} says the latter, and it is
 *       <em>stricter</em> than what is being turned off here - it fails the build on a single JDBC
 *       reference anywhere else in the module, which the shared rule would have permitted in any
 *       package it recognised as persistence.</li>
 *   <li>{@code exceptions.custom-exceptions-extend-the-base-exception} - almost every failure here
 *       happens in a scheduled job with no caller, no locale and no response, so it is a plain
 *       {@code IngestException} recorded on the run row. The one failure that <em>can</em> reach a
 *       caller, {@code UnknownIngestTaskException} from the actuator endpoint, does extend
 *       {@code LocalizedException}.</li>
 * </ul>
 *
 * <p>Everything else is on. The rules specific to this module - the SQL carve-out's boundary, the
 * JDBC confinement, and the prohibition on reading a whole object - are in
 * {@link SqlConfinementTest}, because a shared bytecode analyser cannot express a boundary that only
 * exists inside one module.
 */
@AnalyzeArchitecture(
        packages = "ru.ludwigandreas.ingest",
        disable = {
                "layering",
                "persistence.repositories-are-used-only-by-services",
                "spring.transactional-methods-are-in-the-service-layer",
                "web.controllers-do-not-call-controllers",
                "rest-paths",
                "mappers.mappers-are-mapstruct-interfaces",
                "persistence.persistence-context-is-confined",
                "exceptions.custom-exceptions-extend-the-base-exception"
        })
class ArchitectureTest extends ArchitectureRulesTest {
}
