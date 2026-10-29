package ru.ludwigandreas.job.core.admin.service;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Why a replica control could not be submitted or a command not read.
 */
public final class ReplicaAdminException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private static final String PREFIX = "ludwig.job-core.admin.";

    private ReplicaAdminException(ProblemStatus status, String code, Object... args) {
        super(status, PREFIX + code, args);
    }

    static ReplicaAdminException controlNotFound(String control) {
        return new ReplicaAdminException(ProblemStatus.NOT_FOUND, "control-not-found", control);
    }

    static ReplicaAdminException invalidArguments(String control, String detail) {
        return new ReplicaAdminException(ProblemStatus.INVALID, "control-arguments-invalid", control, detail);
    }

    static ReplicaAdminException commandNotFound(Object id) {
        return new ReplicaAdminException(ProblemStatus.NOT_FOUND, "command-not-found", id);
    }
}
