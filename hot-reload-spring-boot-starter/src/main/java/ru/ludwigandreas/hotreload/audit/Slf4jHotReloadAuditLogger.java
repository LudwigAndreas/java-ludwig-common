package ru.ludwigandreas.hotreload.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/** Default {@link HotReloadAuditLogger}: one structured log line per changed key. */
public class Slf4jHotReloadAuditLogger implements HotReloadAuditLogger {

    private static final Logger log = LoggerFactory.getLogger("ru.ludwigandreas.hotreload.audit");

    @Override
    public void record(HotReloadAuditEntry entry) {
        entry.changes().forEach((key, change) -> log.info(
                "source='{}' actor='{}' at={} key='{}' oldValue='{}' newValue='{}'",
                entry.sourceId(), entry.actor(), entry.timestamp(), key, change.oldValue(), change.newValue()));
    }

    @Override
    public void recordError(String sourceId, Instant timestamp, String actor, Exception exception) {
        log.warn("source='{}' actor='{}' at={} reload FAILED", sourceId, actor, timestamp, exception);
    }
}
