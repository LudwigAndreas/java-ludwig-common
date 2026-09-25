package ru.ludwigandreas.storage.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The architecture test of this module.
 *
 * <h2>What is switched off, and why</h2>
 *
 * <p>Most of the shared rule library describes a <em>service</em> - controllers over a service layer
 * over repositories. This is a library with no web surface, no persistence and no service layer: its
 * packages are named for what they are ({@code api}, {@code s3}, {@code fs}, {@code exception},
 * {@code config}), and the layering rules fail here because the module is arranged for something
 * else rather than because it is arranged badly.
 *
 * <ul>
 *   <li>{@code layering}, {@code persistence.*}, {@code web.*}, {@code rest-paths},
 *       {@code spring.transactional-methods-are-in-the-service-layer} - there is no controller, no
 *       repository, no entity and no transaction in this module at all.</li>
 *   <li>{@code mappers.mappers-are-mapstruct-interfaces} - there is no mapper here; the rule matches
 *       on a name suffix and this module has nothing ending in {@code Mapper}, so it is disabled to
 *       keep the disabled set an honest description rather than because it fires.</li>
 * </ul>
 *
 * <p>Everything else is on, including the exception rules: every failure this module can raise
 * extends {@code LocalizedException}, which is what lets web-core render it without this module
 * shipping an advice of its own.
 */
@AnalyzeArchitecture(
        packages = "ru.ludwigandreas.storage",
        disable = {
                "layering",
                "persistence.repositories-are-used-only-by-services",
                "spring.transactional-methods-are-in-the-service-layer",
                "web.controllers-do-not-call-controllers",
                "rest-paths",
                "mappers.mappers-are-mapstruct-interfaces"
        })
class ArchitectureTest extends ArchitectureRulesTest {
}
