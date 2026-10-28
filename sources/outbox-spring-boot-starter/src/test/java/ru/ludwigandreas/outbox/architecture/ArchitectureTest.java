package ru.ludwigandreas.outbox.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The shared rules over the outbox's administrative package - its only web layer, and the part RuleGroup.ADMIN
 * exists for.
 */
@AnalyzeArchitecture(packages = "ru.ludwigandreas.outbox.admin")
class ArchitectureTest extends ArchitectureRulesTest {
}
