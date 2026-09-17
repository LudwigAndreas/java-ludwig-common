package ru.ludwigandreas.notification.service.exception;

import java.util.UUID;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * An operator asked for a transition the delivery's current state does not allow - retrying one
 * that is not {@code DEAD}, cancelling one that has already been sent.
 *
 * <p>409, and it names both states: "cannot cancel" tells an operator nothing, "cannot cancel a
 * delivery that is already SENT" tells them the send they were trying to stop has happened.
 */
public class IllegalDeliveryTransitionException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public IllegalDeliveryTransitionException(UUID id, String currentState, String requestedAction) {
        super(ProblemStatus.CONFLICT, "error.notification.delivery.illegal-transition",
                id, currentState, requestedAction);
        withProperty("deliveryId", id);
        withProperty("currentState", currentState);
    }
}
