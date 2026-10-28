package ru.ludwigandreas.job.core.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The shared rules over job-core's administrative package, which is the only part of this module with a web
 * layer and the part RuleGroup.ADMIN exists for.
 */
@AnalyzeArchitecture(packages = "ru.ludwigandreas.job.core.admin")
class ArchitectureTest extends ArchitectureRulesTest {
}
