package ru.ludwigandreas.archrules.report;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ru.ludwigandreas.archrules.RuleGroup;

/**
 * The counts a dashboard reads first: how much was checked, how much failed, and where.
 *
 * @param rules          number of rules evaluated
 * @param passed         rules with no violation
 * @param failed         violated rules at severity ERROR - the ones that fail the build
 * @param warnings       violated rules the service tolerates at severity WARNING
 * @param violations     total number of individual violations across all rules
 * @param violationsByGroup violation counts per rule group, for drift tracking per area
 */
public record ReportSummary(int rules,
                            int passed,
                            int failed,
                            int warnings,
                            int violations,
                            Map<RuleGroup, Integer> violationsByGroup) {

    public ReportSummary {
        violationsByGroup = Map.copyOf(Objects.requireNonNull(violationsByGroup, "violationsByGroup"));
    }

    static ReportSummary of(List<RuleReport> rules) {
        int passed = 0;
        int failed = 0;
        int warnings = 0;
        int violations = 0;
        Map<RuleGroup, Integer> byGroup = new LinkedHashMap<>();
        for (RuleReport rule : rules) {
            violations += rule.violations().size();
            if (!rule.isViolated()) {
                passed++;
                continue;
            }
            if (rule.isFailure()) {
                failed++;
            } else {
                warnings++;
            }
            byGroup.merge(rule.group(), rule.violations().size(), Integer::sum);
        }
        return new ReportSummary(rules.size(), passed, failed, warnings, violations, byGroup);
    }

    public boolean isClean() {
        return failed == 0 && warnings == 0;
    }
}
