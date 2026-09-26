package ru.ludwigandreas.archrules.fixture.bad.audit.catalog.reporting;

import java.util.List;

/**
 * Also deliberately not a violation, and the reason the rule is not keyed on the name alone.
 *
 * <p>The name matches - it is an audit something - but nothing here is a recording seam: every method
 * returns a value, so there is no "hand me an event and write it down" for a deployment to reimplement.
 * A rule that flagged this would flag every query-side and marker type that mentions auditing.
 */
public interface ReportAuditQuery {

    /** The trail for one report. */
    List<ReportAuditEvent> forReport(String reportKey);
}
