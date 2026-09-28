package ru.ludwigandreas.export.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The requester already has as many runs in flight, or as many today, as their quota allows.
 *
 * <p>The quota is per user and it applies to every run regardless of how it was requested, because
 * the failure it prevents is not abuse but a loop: a client that retries a slow report on a timeout
 * will otherwise queue a second million-row run behind the first, and then a third. By the time
 * anyone notices, the poller is saturated with copies of one report and every other report in the
 * service has stopped.
 */
@Getter
public class ExportQuotaExceededException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String quotaName;
    private final int limit;

    /**
     * Reports an exhausted quota.
     *
     * @param quotaName which quota - concurrent or daily - so the message can say which
     * @param limit     the ceiling, published as {@code limit}
     */
    public ExportQuotaExceededException(String quotaName, int limit) {
        super(ProblemStatus.TOO_MANY_REQUESTS, ExportProblemCodes.QUOTA_EXCEEDED, quotaName, limit);
        this.quotaName = quotaName;
        this.limit = limit;
        withProperty("quota", quotaName);
        withProperty("limit", limit);
    }
}
