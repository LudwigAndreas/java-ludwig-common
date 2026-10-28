package ru.ludwigandreas.ingest.admin.service;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Why an administrative request about an ingest task could not be carried out.
 */
public final class FileIngestAdminException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private FileIngestAdminException(ProblemStatus status, String code, Object... args) {
        super(status, code, args);
    }

    static FileIngestAdminException runNotFound(UUID runId, String task) {
        return new FileIngestAdminException(ProblemStatus.NOT_FOUND, "ludwig.ingest.admin.run-not-found", runId, task);
    }

    static FileIngestAdminException alreadyRunning(String task) {
        return new FileIngestAdminException(ProblemStatus.CONFLICT, "ludwig.ingest.admin.already-running", task);
    }
}
