package ru.ludwigandreas.usersettings.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The architecture test of this module.
 *
 * <h2>What is switched off, and why</h2>
 *
 * <p>Most of the shared rule library describes a <em>service</em>: controllers on top, a service
 * layer under them, repositories under that, messaging entering through the service layer. This is a
 * library, and its packages are named for what they do - {@code resolve}, {@code write},
 * {@code projection}, {@code retention} - rather than for a layer. Those rules therefore fail here
 * not because the module is badly arranged but because it is arranged for something else, and the
 * honest response is to say so rather than to invent a {@code service} package that exists only to
 * satisfy a rule.
 *
 * <ul>
 *   <li>{@code layering}, {@code persistence.repositories-are-used-only-by-services},
 *       {@code spring.transactional-methods-are-in-the-service-layer} - there is no service layer to
 *       be in. The transactional units here are the writer, the consent ledger, the projection and
 *       the retention job, each of which is the thing a service would otherwise call.</li>
 *   <li>{@code web.controllers-do-not-call-controllers} - matches every class in a {@code web}
 *       package, which here also holds the response renderer the controllers legitimately use.</li>
 *   <li>{@code rest-paths} - the shipped controllers mount on a configurable, unversioned base path
 *       on purpose. They are off by default precisely so a service can own its own API shape,
 *       versioning included; imposing a version here would be this module making that decision for
 *       it.</li>
 *   <li>{@code exceptions.custom-exceptions-extend-the-base-exception} - {@code
 *       SettingConfigurationException} is thrown during context refresh, where there is no caller, no
 *       locale and no response. It is a plain {@code RuntimeException} for the same reason
 *       {@code security-spring-boot-starter}'s {@code SecurityConfigurationException} is. Every
 *       exception that <em>can</em> reach a client does extend the base one.</li>
 *   <li>{@code mappers.mappers-are-mapstruct-interfaces} - the rule matches any class whose name ends
 *       in {@code Mapper}, and the platform's own convention names every contributed problem renderer
 *       {@code *ProblemMapper} ({@code SecurityProblemMapper}, {@code DbCoreProblemMapper},
 *       {@code ODataFilterProblemMapper}). The module's one genuine mapper,
 *       {@code ConsentEntityMapper}, is a MapStruct interface.</li>
 * </ul>
 *
 * <p>Everything else is on, and three of its findings were fixed rather than suppressed: a package
 * cycle between {@code api} and {@code exception} (the violation types moved to {@code exception},
 * where the exception that carries them lives), the Kafka listener sitting outside a messaging
 * package, and {@code UserSettingsProperties} not being {@code @Validated}.
 */
@AnalyzeArchitecture(
        packages = "ru.ludwigandreas.usersettings",
        disable = {
                "layering",
                "persistence.repositories-are-used-only-by-services",
                "spring.transactional-methods-are-in-the-service-layer",
                "web.controllers-do-not-call-controllers",
                "rest-paths",
                "exceptions.custom-exceptions-extend-the-base-exception",
                "mappers.mappers-are-mapstruct-interfaces"
        })
class ArchitectureTest extends ArchitectureRulesTest {
}
