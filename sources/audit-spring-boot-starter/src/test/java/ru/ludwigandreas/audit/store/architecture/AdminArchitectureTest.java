package ru.ludwigandreas.audit.store.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The shared rules over the trail search's administrative package - the starter's only web layer.
 */
@AnalyzeArchitecture(packages = "ru.ludwigandreas.audit.store.admin")
class AdminArchitectureTest extends ArchitectureRulesTest {
}
