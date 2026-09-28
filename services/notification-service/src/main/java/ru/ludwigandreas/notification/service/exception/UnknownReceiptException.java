package ru.ludwigandreas.notification.service.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A receipt arrived for a provider message id this service has no delivery for.
 *
 * <p>202 would be the friendlier answer and it is the wrong one: providers retry on a non-2xx, and a
 * receipt for a delivery that retention has already purged should stop arriving. 404 tells the
 * provider to give up, which is the truth.
 */
public class UnknownReceiptException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public UnknownReceiptException(String providerMessageId) {
        super(ProblemStatus.NOT_FOUND, "error.notification.receipt.unknown-message", providerMessageId);
    }
}
