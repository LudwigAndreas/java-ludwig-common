package ru.ludwigandreas.hotreload.audit;

import java.time.Instant;

/**
 * SPI for recording hot-reload activity in whatever form a regulated environment needs: structured logs,
 * a database table, a SIEM sink. The default ({@link Slf4jHotReloadAuditLogger}) logs; supply your own
 * {@code @Bean} of this type (e.g. persisting to a table, mirroring {@code
 * outbox-spring-boot-starter}'s {@code OutboxAuditLogger}/{@code PersistingOutboxAuditLogger} pattern) to
 * replace it - {@code HotReloadAutoConfiguration} only provides one if the application hasn't.
 */
public interface HotReloadAuditLogger {

    /** A reload succeeded and changed at least one key. */
    void record(HotReloadAuditEntry entry);

    /** A reload attempt failed; the previous, valid content kept serving. */
    default void recordError(String sourceId, Instant timestamp, String actor, Exception exception) {
        // no-op by default
    }
}
