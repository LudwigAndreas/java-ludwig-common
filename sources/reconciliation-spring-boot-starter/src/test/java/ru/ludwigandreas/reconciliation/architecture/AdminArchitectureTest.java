package ru.ludwigandreas.reconciliation.architecture;

import ru.ludwigandreas.archrules.junit.AnalyzeArchitecture;
import ru.ludwigandreas.archrules.junit.ArchitectureRulesTest;

/**
 * The shared rules over reconciliation's administrative package - its only web layer, and the part
 * RuleGroup.ADMIN exists for. The module's own structural rules are in {@code ReconciliationArchitectureTest}.
 */
@AnalyzeArchitecture(packages = "ru.ludwigandreas.reconciliation.admin")
class AdminArchitectureTest extends ArchitectureRulesTest {
}
