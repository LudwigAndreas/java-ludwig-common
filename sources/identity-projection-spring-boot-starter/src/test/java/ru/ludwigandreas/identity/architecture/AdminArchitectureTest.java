package ru.ludwigandreas.identity.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The shared rules over the identity projection's administrative package - its only web layer.
 */
@AnalyzeArchitecture(packages = "ru.ludwigandreas.identity.admin")
class AdminArchitectureTest extends ArchitectureRulesTest {
}
