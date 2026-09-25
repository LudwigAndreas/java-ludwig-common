package ru.ludwigandreas.ingest.repository;

import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import ru.ludwigandreas.ingest.api.IngestRunStatus;
import ru.ludwigandreas.ingest.entity.QFileIngestQuarantine;
import ru.ludwigandreas.ingest.entity.QFileIngestRun;

/**
 * Every predicate this module queries its own tables with, in one place and all compile-time checked.
 *
 * <h2>Why these are here rather than as derived method names</h2>
 *
 * <p>{@code findByTaskAndStatusOrderByStartedAtDesc} is a string the compiler does not read: rename a
 * field and it keeps compiling and starts failing at context refresh, or - worse, for a method whose
 * name still parses - silently queries a different column. A QueryDSL predicate over the generated
 * Q-types is checked when the module is built, which is the platform's standing rule and the reason
 * the repositories above are empty interfaces.
 *
 * <p>Gathering them in one class rather than inlining them at their call sites is a smaller decision:
 * several of them are used twice, once by the engine and once by the actuator endpoint, and two
 * copies of "which runs count as recent" is how the endpoint comes to show something different from
 * what the scheduler acts on.
 */
public final class IngestRunQueries {

    private static final QFileIngestRun RUN = QFileIngestRun.fileIngestRun;
    private static final QFileIngestQuarantine QUARANTINE = QFileIngestQuarantine.fileIngestQuarantine;

    private IngestRunQueries() {
    }

    /**
     * The run for one object, by the identity triple.
     *
     * <p>The read half of the exactly-once guard. The constraint is what enforces it; this is what
     * lets the engine ask first and skip cleanly, rather than insert and interpret a violation - which
     * would work, and would put a rolled-back transaction in the logs of every ordinary re-offer.
     *
     * @param container       the bucket
     * @param objectKey       the key
     * @param contentIdentity the etag or version
     * @return the predicate
     */
    public static Predicate byIdentity(String container, String objectKey, String contentIdentity) {
        return RUN.container.eq(container)
                .and(RUN.objectKey.eq(objectKey))
                .and(RUN.contentIdentity.eq(contentIdentity));
    }

    /**
     * Runs of one task that are still in progress.
     *
     * <p>What a resume looks for. A run left {@code RUNNING} by a crashed instance is picked up here,
     * which is why there is no separate reaper: the lock's lease already expires on its own, and this
     * predicate is what finds the work it was holding.
     *
     * @param task the task name
     * @return the predicate
     */
    public static Predicate runningFor(String task) {
        return RUN.task.eq(task).and(RUN.status.eq(IngestRunStatus.RUNNING));
    }

    /**
     * Runs of one task, whatever their status.
     *
     * @param task the task name
     * @return the predicate
     */
    public static Predicate forTask(String task) {
        return RUN.task.eq(task);
    }

    /**
     * Completed runs of one task, for the missing-file check.
     *
     * <p>The check asks "has anything landed today", and it has to mean <em>completed</em>: a run that
     * started and failed is not a file that arrived, and counting it would silence the alarm on
     * exactly the morning it should ring.
     *
     * @param task the task name
     * @return the predicate
     */
    public static BooleanExpression completedFor(String task) {
        return RUN.task.eq(task).and(RUN.status.eq(IngestRunStatus.COMPLETED));
    }

    /**
     * Newest first, which is the only order an operator ever wants a run list in.
     *
     * @return the order
     */
    public static OrderSpecifier<?> newestFirst() {
        return RUN.startedAt.desc();
    }

    /**
     * The quarantine rows of one run.
     *
     * @param runId the run
     * @return the predicate
     */
    public static Predicate quarantineOf(java.util.UUID runId) {
        return QUARANTINE.runId.eq(runId);
    }

    /**
     * Quarantine rows in source order, so a listing reads like the file.
     *
     * @return the order
     */
    public static OrderSpecifier<?> byRecordOrder() {
        return QUARANTINE.recordOrdinal.asc();
    }
}
