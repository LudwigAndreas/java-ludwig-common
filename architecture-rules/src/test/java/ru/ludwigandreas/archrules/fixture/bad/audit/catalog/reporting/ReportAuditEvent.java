package ru.ludwigandreas.archrules.fixture.bad.audit.catalog.reporting;

/**
 * The typed event the local SPI above carries.
 *
 * <p>Deliberately <em>not</em> a violation: keeping a typed record as the authoring surface is what the
 * platform asks for. What the rule objects to is the seam next to it, not the record.
 */
public record ReportAuditEvent(String reportKey, String actor) {
}
