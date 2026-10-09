package ru.ludwigandreas.odatafilter.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The shared rule library, applied to this module.
 *
 * <p>This module had no architecture test at all until {@code audit.audit-types-produce-audit-events}
 * needed one. That is worth recording rather than quietly fixing: a service's run analyses only the
 * service's own packages, so no existing test could ever have seen {@code ru.ludwigandreas.odatafilter}
 * - and the AUDIT group, whose whole subject is this module's {@code audit} package, had therefore never
 * been applied to it. The rule and this runner are one change; either alone enforces nothing.
 *
 * <h2>What is switched off, and why</h2>
 *
 * <p>Most of the library describes a <em>service</em>: controllers over a service layer over
 * repositories, each in its own package, mapped by MapStruct. This module is a library with a parser, a
 * policy registry and a QueryDSL translator, so those rules would be describing a shape it deliberately
 * does not have.
 *
 * <ul>
 *   <li>{@code layering} and the two rules about where a repository may be called from - there is no
 *       service layer and no repository here.
 *   <li>{@code rest-paths} and {@code web.controllers-do-not-call-controllers} - the only controller is
 *       the legacy {@code @RestControllerAdvice}, which is an error renderer rather than a resource.
 *   <li>{@code mappers.mappers-are-mapstruct-interfaces} - the translation here is AST to QueryDSL, which
 *       MapStruct cannot express.
 *   <li>{@code persistence.persistence-context-is-confined} - this module builds predicates and never
 *       touches an {@code EntityManager}, and the executor takes the consumer's {@code JPAQueryFactory}
 *       rather than living in a repository package.
 *   <li>{@code exceptions.custom-exceptions-extend-the-base-exception} - {@code ODataFilterException}
 *       deliberately extends {@code RuntimeException} and not {@code web-core}'s
 *       {@code LocalizedException}, so that it can be thrown from a batch job with no web-core on the
 *       classpath. Its javadoc says so.
 *   <li>{@code persistence.dynamic-paths-are-not-hand-built} - this is the module that legitimately owns
 *       the dynamic path walk, which is exactly what that rule's javadoc names it as.
 * </ul>
 *
 * <h2>Nothing is switched off as debt any more</h2>
 *
 * <p>Two rules were, until {@code fix-odata-filter-package-cycles}:
 * {@code cycles.modules-are-free-of-cycles} and
 * {@code configuration-properties.configuration-properties-are-validated}. Both are enabled. The cycles
 * were all twenty of them routed through {@code config}, fixed by moving {@code ODataFilterProperties} into
 * {@code ..properties..}; the validation needed a validation <em>implementation</em> rather than just the
 * annotation, which this module now takes as an optional dependency. Every disable above describes a
 * library that is not a service - none of them hides a finding.
 */
@AnalyzeArchitecture(
        packages = "ru.ludwigandreas.odatafilter",
        disable = {
                "layering",
                "persistence.repositories-are-used-only-by-services",
                "persistence.persistence-context-is-confined",
                "persistence.dynamic-paths-are-not-hand-built",
                "spring.transactional-methods-are-in-the-service-layer",
                "web.controllers-do-not-call-controllers",
                "rest-paths",
                "mappers.mappers-are-mapstruct-interfaces",
                "exceptions.custom-exceptions-extend-the-base-exception"
        })
class ArchitectureTest extends ArchitectureRulesTest {
}
