package ru.ludwigandreas.notification.service.exception;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/** No delivery with this id exists, or retention has already removed it. Rendered as 404. */
public class DeliveryNotFoundException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public DeliveryNotFoundException(UUID id) {
        super(ProblemStatus.NOT_FOUND, "error.notification.delivery.not-found", id);
    }
}
