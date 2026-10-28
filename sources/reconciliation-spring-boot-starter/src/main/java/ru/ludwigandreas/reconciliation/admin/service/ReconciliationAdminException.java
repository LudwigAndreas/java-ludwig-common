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
}
