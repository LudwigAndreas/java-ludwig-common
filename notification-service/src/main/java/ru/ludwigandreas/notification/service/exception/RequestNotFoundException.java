package ru.ludwigandreas.notification.service.exception;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/** No notification request with this id exists. Rendered as 404. */
public class RequestNotFoundException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public RequestNotFoundException(UUID id) {
        super(ProblemStatus.NOT_FOUND, "error.notification.request.not-found", id);
    }
}
