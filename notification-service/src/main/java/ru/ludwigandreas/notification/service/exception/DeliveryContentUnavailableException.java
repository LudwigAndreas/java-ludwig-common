package ru.ludwigandreas.notification.service.exception;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The delivery exists but its rendered body does not - it has not been rendered yet, or the
 * retention purge has already dropped it.
 *
 * <p>410 rather than 404, and the distinction is worth having: the delivery is still there, so
 * "not found" would send an operator looking for a delivery that is in front of them. Gone says what
 * happened, and the retention policy in the README says when.
 */
public class DeliveryContentUnavailableException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public DeliveryContentUnavailableException(UUID deliveryId) {
        super(ProblemStatus.GONE, "error.notification.delivery.content-unavailable", deliveryId);
    }
}
