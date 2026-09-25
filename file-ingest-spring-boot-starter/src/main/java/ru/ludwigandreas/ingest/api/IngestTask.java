package ru.ludwigandreas.ingest.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

/**
 * Marks a bean as the code half of one ingest, and names the configuration block it belongs to.
 *
 * <p>One annotated bean plus one block of YAML is the whole of what a service writes. The split is
 * the same one {@code reconciliation-spring-boot-starter} uses, for the same reason: schedule, lock
 * lease, batch bounds, quarantine policy, receipt and archive behaviour are identical in shape across
 * every ingest in every service, so they are configuration; the parser and the applier genuinely
 * differ, so they are Java.
 *
 * <p>The value must match a key under {@code ludwig.ingest.tasks}. A bean naming a task nobody
 * configured, or a configured task with no bean, is refused at startup by
 * {@code FileIngestConfigurationValidator} - not at six-thirty tomorrow morning, when the schedule
 * first fires and finds half of itself missing.
 */
@Documented
@Component
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface IngestTask {

    /**
     * The task name.
     *
     * @return a key under {@code ludwig.ingest.tasks}
     */
    String value();
}
