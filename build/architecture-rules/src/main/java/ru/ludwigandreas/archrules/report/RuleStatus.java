package ru.ludwigandreas.archrules.report;

import java.util.Locale;

/** Whether a rule was satisfied by the analysed code. */
public enum RuleStatus {

    PASSED,

    VIOLATED;

    /** Lower-case form used in the JSON report. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
