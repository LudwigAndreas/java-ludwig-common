package ru.ludwigandreas.archrules.fixture.bad.logging.catalog.logs;

/**
 * Not an encoder: it implements neither Logback contract, so nothing can attach it to an appender.
 * A class that merely formats a string for a log message must not be reported.
 */
public class LogLineFormatter {

    public String format(String orderId) {
        return "order " + orderId + " accepted";
    }
}
