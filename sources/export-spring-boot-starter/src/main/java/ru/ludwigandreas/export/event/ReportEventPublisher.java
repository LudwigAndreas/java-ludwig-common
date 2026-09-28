package ru.ludwigandreas.export.event;

/**
 * Where "a report is ready" is announced.
 *
 * <p>A seam rather than a direct outbox call, because publishing is optional: a service that
 * produces reports for people to download has nobody to tell, and should not carry a message bus to
 * say so. The no-op is the default and the outbox implementation appears when
 * {@code outbox-spring-boot-starter} is on the classpath.
 *
 * <p>Implementations must not throw. A run that failed because an event could not be published would
 * mean a broker outage became a reporting outage, and the file is already written and stored by the
 * time this is called - there is nothing left to undo, only something left to announce.
 */
@FunctionalInterface
public interface ReportEventPublisher {

    /** A publisher that announces nothing, for a service with nobody to tell. */
    ReportEventPublisher NONE = event -> {
        // Nothing is published; see the class comment.
    };

    /** Announces a finished report. */
    void reportReady(ReportReadyEvent event);
}
