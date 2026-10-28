package ru.ludwigandreas.job.core.admin.service;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Why an administrative request about a job or a lease could not be carried out.
 */
public final class JobAdminException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private JobAdminException(ProblemStatus status, String code, Object... args) {
        super(status, code, args);
    }

    static JobAdminException jobNotFound(String name) {
        return new JobAdminException(ProblemStatus.NOT_FOUND, "ludwig.job-core.admin.job-not-found", name);
    }

    static JobAdminException leaseNotFound(String name) {
        return new JobAdminException(ProblemStatus.NOT_FOUND, "ludwig.job-core.admin.lease-not-found", name);
    }

    static JobAdminException leaseHeld(String name, String owner) {
        return new JobAdminException(ProblemStatus.CONFLICT, "ludwig.job-core.admin.lease-held", name, owner)
                .withProperty("owner", owner);
    }

    static JobAdminException leaseNotHeld(String name) {
        return new JobAdminException(ProblemStatus.CONFLICT, "ludwig.job-core.admin.lease-not-held", name);
    }

    static JobAdminException shuttingDown(String name) {
        return new JobAdminException(ProblemStatus.CONFLICT, "ludwig.job-core.admin.shutting-down", name);
    }
}
