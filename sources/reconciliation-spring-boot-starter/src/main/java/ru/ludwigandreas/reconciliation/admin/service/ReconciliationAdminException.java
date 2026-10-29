package ru.ludwigandreas.reconciliation.admin.service;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Why an administrative request about a reconciliation task could not be carried out.
 */
public final class ReconciliationAdminException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private static final String PREFIX = "ludwig.reconciliation.admin.";

    private ReconciliationAdminException(ProblemStatus status, String code, Object... args) {
        super(status, PREFIX + code, args);
    }

    static ReconciliationAdminException taskNotFound(String task) {
        return new ReconciliationAdminException(ProblemStatus.NOT_FOUND, "task-not-found", task);
    }

    static ReconciliationAdminException enumerationNotFound(String task) {
        return new ReconciliationAdminException(ProblemStatus.NOT_FOUND, "enumeration-not-found", task);
    }

    static ReconciliationAdminException remoteJobNotFound(UUID jobId, String task) {
        return new ReconciliationAdminException(ProblemStatus.NOT_FOUND, "remote-job-not-found", jobId, task);
    }

    static ReconciliationAdminException leaseNotFound(UUID leaseId) {
        return new ReconciliationAdminException(ProblemStatus.NOT_FOUND, "lease-not-found", leaseId);
    }

    static ReconciliationAdminException keysRequired() {
        return new ReconciliationAdminException(ProblemStatus.INVALID, "keys-required");
    }

    static ReconciliationAdminException invalidTier(String tier) {
        return new ReconciliationAdminException(ProblemStatus.INVALID, "invalid-tier", tier);
    }

    static ReconciliationAdminException alreadyRunning(String pass) {
        return new ReconciliationAdminException(ProblemStatus.CONFLICT, "already-running", pass);
    }

    static ReconciliationAdminException runNotFound(UUID runId, String task) {
        return new ReconciliationAdminException(ProblemStatus.NOT_FOUND, "run-not-found", runId, task);
    }

    static ReconciliationAdminException manualRunCapacity(String task, int capacity) {
        ReconciliationAdminException refused = new ReconciliationAdminException(ProblemStatus.TOO_MANY_REQUESTS,
                "manual-run-capacity", task, capacity);
        // A property rather than a header: the problem pipeline renders exceptions, and this is how the platform's
        // other 429 (export's quota) tells a client when to come back.
        refused.withProperty("retryAfterSeconds", MANUAL_RUN_RETRY_AFTER_SECONDS);
        return refused;
    }

    /** How long a client refused for manual-run capacity should wait before asking again. */
    static final int MANUAL_RUN_RETRY_AFTER_SECONDS = 30;
}
