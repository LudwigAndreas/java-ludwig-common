package ru.ludwigandreas.archrules.fixture.bad.logging.catalog.logs;

import ch.qos.logback.core.Layout;

/** A second layout: the same mistake as {@link PipeDelimitedEncoder}, one level up. */
public class AuditLineLayout implements Layout<Object> {

    @Override
    public String doLayout(Object event) {
        return "AUDIT " + event;
    }
}
