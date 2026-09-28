package ru.ludwigandreas.notification.service.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * An inbound provider receipt did not carry a valid signature.
 *
 * <p>401 with no detail about which check failed. A receipt endpoint that explains itself is an
 * oracle: anyone could probe it until they learned how to forge a bounce, and a forged bounce
 * suppresses a real address - which makes this endpoint a denial-of-service against individual
 * recipients if it is loose about what it accepts.
 */
public class InvalidReceiptSignatureException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    public InvalidReceiptSignatureException() {
        super(ProblemStatus.UNAUTHORIZED, "error.notification.receipt.invalid-signature");
    }
}
